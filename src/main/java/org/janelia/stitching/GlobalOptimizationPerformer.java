package org.janelia.stitching;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.NotImplementedException;
import org.janelia.util.ComparableTuple;

import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.IndexedTile;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.InterpolatedModel;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.RigidModel3D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.SimilarityModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class GlobalOptimizationPerformer
{
	private static final double DAMPNESS_FACTOR = 0.9;
	private static final int OPTIMIZER_ITERATIONS = 5000;

	private static final int fixedIndex = 0;
	private static final int movingIndex = 1;

	public Map< Integer, IndexedTile< ? > > lostTiles;

	public boolean translationOnlyStitching = false;
	public int numCollinearTileConfigs = 0, numCoplanarTileConfigs = 0;

	public int remainingGraphSize, remainingPairs;
	public double avgDisplacement, maxDisplacement;

	public List< IndexedTile< ? > > optimize(
			final List< SubTilePairwiseMatch > subTilePairwiseMatches,
			final SerializableStitchingParameters params ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		return optimize( subTilePairwiseMatches, params, null );
	}

	public List< IndexedTile< ? > > optimize(
			final List< SubTilePairwiseMatch > subTilePairwiseMatches,
			final SerializableStitchingParameters params,
			final PrintWriter logWriter ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		final LinkedHashMap< IndexedTile< ? >, Map< IndexedTile< ? >, List< PointMatch > > > connectedTilesMap = new LinkedHashMap<>();
		final Map< IndexedTile< ? >, List< Interval > > tileToMatchedSubTiles = new HashMap<>();

		for ( final SubTilePairwiseMatch subTilePairwiseMatch : subTilePairwiseMatches )
		{
			final SubTile[] subTiles = subTilePairwiseMatch.getPairwiseResult().getSubTilePair().toArray();
			final AffineGet[] estimatedFullTileTransforms = subTilePairwiseMatch.getPairwiseResult().getEstimatedFullTileTransformPair().toArray();

			final IndexedTile< ? >[] tileModels = new IndexedTile< ? >[ 2 ];
			tileModels[ fixedIndex ] = subTilePairwiseMatch.getFixedTile();
			tileModels[ movingIndex ] = subTilePairwiseMatch.getMovingTile();

			// add empty collections if not present
			for ( int i = 0; i < 2; ++i )
			{
				if ( !connectedTilesMap.containsKey( tileModels[ i ] ) )
					connectedTilesMap.put( tileModels[ i ], new HashMap<>() );

				if ( !connectedTilesMap.get( tileModels[ i ] ).containsKey( tileModels[ ( i + 1 ) % 2 ] ) )
					connectedTilesMap.get( tileModels[ i ] ).put( tileModels[ ( i + 1 ) % 2 ], new ArrayList<>() );
			}

			// create point match to map the middle point of the moving subtile into its new position in the fixed tile space
			final PointMatch movingIntoFixedPointMatch = createMovingIntoFixedPointMatch(
					subTiles,
					estimatedFullTileTransforms,
					subTilePairwiseMatch.getPairwiseResult().getOffset(),
					subTilePairwiseMatch.getPairwiseResult().getCrossCorrelation()
				);

			connectedTilesMap.get( tileModels[ movingIndex ] ).get( tileModels[ fixedIndex ] ).add( movingIntoFixedPointMatch );

			// record the matched subtiles for both tiles
			for ( int i = 0; i < 2; ++i )
			{
				if ( !tileToMatchedSubTiles.containsKey( tileModels[ i ] ) )
					tileToMatchedSubTiles.put( tileModels[ i ], new ArrayList<>() );
				tileToMatchedSubTiles.get( tileModels[ i ] ).add( subTiles[ i ] );
			}
		}

		// connect the tiles
		for ( final Entry< IndexedTile< ? >, Map< IndexedTile< ? >, List< PointMatch > > > connectedTiles : connectedTilesMap.entrySet() )
		{
			final IndexedTile< ? > tile = connectedTiles.getKey();
			for ( final Entry< IndexedTile< ? >, List< PointMatch > > connectedTile : connectedTiles.getValue().entrySet() )
				tile.connect( connectedTile.getKey(), connectedTile.getValue() );
		}

		writeLog( logWriter, "Added " + subTilePairwiseMatches.size() + " pairwise matches" );

		final Set< IndexedTile< ? > > tilesSet = new LinkedHashSet<>( connectedTilesMap.keySet() );
		if ( tilesSet.isEmpty() )
			return new ArrayList<>();

		// print graph sizes
		final TreeMap< Integer, Integer > graphSizeToCount = getGraphsSize( tilesSet );
		writeLog( logWriter, "Number of tile graphs = " + graphSizeToCount.values().stream().mapToInt( Number::intValue ).sum() );
		for ( final Entry< Integer, Integer > entry : graphSizeToCount.descendingMap().entrySet() )
			writeLog( logWriter, "   " + entry.getKey() + " tiles: " + entry.getValue() + " graphs" );

		// trash everything but the largest graph
		final int numTilesBeforeRetainingLargestGraph = tilesSet.size();
		retainOnlyLargestGraph( tilesSet );
		final int numTilesAfterRetainingLargestGraph = tilesSet.size();

		writeLog( logWriter, "Using the largest graph of size " + numTilesAfterRetainingLargestGraph + " (throwing away " + ( numTilesBeforeRetainingLargestGraph - numTilesAfterRetainingLargestGraph ) + " tiles from smaller graphs)" );
		remainingGraphSize = numTilesAfterRetainingLargestGraph;

		remainingPairs = countRemainingPairs( tilesSet, subTilePairwiseMatches );

		// if all tiles have underlying translation models, consider this stitching configuration to be translation-only
		translationOnlyStitching = true;
		for ( final IndexedTile< ? > tile : tilesSet )
		{
			if ( !( tile.getModel() instanceof TranslationModel2D || tile.getModel() instanceof TranslationModel3D ) )
			{
				translationOnlyStitching = false;
				break;
			}
		}

		if ( !translationOnlyStitching )
		{
			// if some of the tiles do not have enough point matches for a high-order model, fall back to simpler model
			final Pair< Integer, Integer > tileModelsSimplificationResult = simplifyTileModelsIfNeeded( tilesSet, tileToMatchedSubTiles );
			numCollinearTileConfigs = tileModelsSimplificationResult.getA();
			numCoplanarTileConfigs = tileModelsSimplificationResult.getB();
		}

		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles( tilesSet );

		long elapsed = System.nanoTime();

		if ( translationOnlyStitching )
			optimizeTranslation( tc );
		else
			optimizeHigherOrder( tc );

		elapsed = System.nanoTime() - elapsed;

		writeLog( logWriter, String.format( "Optimization round took %.2fs", elapsed / 1e9 ) );

		final double avgError = tc.getError();
		final double maxError = tc.getMaxError();

		// new way of finding biggest error to look for the largest displacement
		double longestDisplacement = 0;
		for ( final Tile< ? > t : tc.getTiles() )
			for ( final PointMatch p :  t.getMatches() )
				longestDisplacement = Math.max( p.getDistance(), longestDisplacement );

		writeLog( logWriter, "" );
		writeLog( logWriter, String.format( "Max pairwise match displacement: %.2fpx", longestDisplacement ) );
		writeLog( logWriter, String.format( "avg error: %.2fpx", avgError ) );
		writeLog( logWriter, String.format( "max error: %.2fpx", maxError ) );

		avgDisplacement = avgError;
		maxDisplacement = maxError;

		// find out what tiles have been thrown out
		lostTiles = getLostTiles( tilesSet, subTilePairwiseMatches );
		writeLog( logWriter, "Tiles lost: " + lostTiles.size() );

		final List< IndexedTile< ? > > resultingTiles = new ArrayList< >();
		for ( final Tile< ? > tile : tc.getTiles() )
			resultingTiles.add( ( IndexedTile< ? > ) tile );
		return resultingTiles;
	}

	private PointMatch createMovingIntoFixedPointMatch(
			final SubTile[] subTiles,
			final AffineGet[] estimatedFullTileTransforms,
			final double[] subTilesOffset,
			final double pointMatchWeight )
	{
		// get the middle point of the moving subtile in the local (moving) tile space
		final Point movingSubTileMiddlePoint = new Point( SubTileOperations.getSubTileMiddlePoint( subTiles[ movingIndex ] ) );

		// get the new middle point position of the 'moving' subtile in the coordinate space of the 'fixed' tile
		final Point newTransformedMovingSubTileMiddlePoint = new Point( PairwiseTileOperations.mapMovingSubTileMiddlePointIntoFixedTile(
				subTiles,
				estimatedFullTileTransforms,
				subTilesOffset
			) );

		final PointMatch movingIntoFixedPointMatch = new PointMatch(
				movingSubTileMiddlePoint,
				newTransformedMovingSubTileMiddlePoint,
				pointMatchWeight
			);

		return movingIntoFixedPointMatch;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static Pair< Integer, Integer > simplifyTileModelsIfNeeded(
			final Set< IndexedTile< ? > > tilesSet,
			final Map< IndexedTile< ? >, List< Interval > > tileToMatchedSubTiles )
	{
		int numCollinearTileConfigs = 0, numCoplanarTileConfigs = 0;

		for ( final IndexedTile< ? > tile : tilesSet )
		{
			if ( tile.getMatches().isEmpty() )
				throw new RuntimeException( "tile does not have any point matches" );

			// group subtiles by their local positions
			final TreeMap< ComparableTuple< Long >, Integer > localSubTilePositions = CheckSubTileMatchesCoplanarity.groupSubTilesByTheirLocalPosition( tileToMatchedSubTiles.get( tile ) );

			final int dim = tile.getMatches().iterator().next().getP1().getL().length;
			final Model< ? > replacementModel;

			if ( dim == 2 )
			{
				if ( CheckSubTileMatchesCoplanarity.isCollinear( localSubTilePositions ) )
				{
					// collinear, fallback to translation
					replacementModel = new TranslationModel2D();
					++numCollinearTileConfigs;
				}
				else
				{
					replacementModel = null;
				}
			}
			else if ( dim == 3 )
			{
				if ( CheckSubTileMatchesCoplanarity.isCollinear( localSubTilePositions ) )
				{
					// collinear, fallback to translation
					replacementModel = new TranslationModel3D();
					++numCollinearTileConfigs;
				}
				else if ( CheckSubTileMatchesCoplanarity.isCoplanar( localSubTilePositions ) )
				{
					// coplanar, fallback to similarity
					replacementModel = new SimilarityModel3D();
					++numCoplanarTileConfigs;
				}
				else
				{
					replacementModel = null;
				}
			}
			else
			{
				throw new RuntimeException( "wrong dimensionality: " + dim );
			}

			if ( replacementModel != null )
			{
				if ( tile.getModel() instanceof InterpolatedModel< ?, ?, ? > && !( replacementModel instanceof TranslationModel2D || replacementModel instanceof TranslationModel3D ) )
				{
					final InterpolatedModel< ?, ?, ? > interpolatedModel = ( InterpolatedModel< ?, ?, ? > ) tile.getModel();
					final Model< ? > replacementInterpolatedModel = TileModelFactory.createInterpolatedModel(
							dim,
							( Model ) replacementModel,
							( Model ) interpolatedModel.getB(),
							interpolatedModel.getLambda()
						);
					( ( IndexedTile ) tile ).setModel( replacementInterpolatedModel );
				}
				else
				{
					( ( IndexedTile ) tile ).setModel( replacementModel );
				}
			}
		}

		return new ValuePair<>( numCollinearTileConfigs, numCoplanarTileConfigs );
	}

	private static TreeMap< Integer, Integer > getGraphsSize( final Set< IndexedTile< ? > > tilesSet )
	{
		final TreeMap< Integer, Integer > graphSizeToCount = new TreeMap<>();

		final List< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tilesSet );
		for ( final Set< Tile< ? > > graph : graphs )
		{
			final int graphSize = graph.size();
			graphSizeToCount.put( graphSize, graphSizeToCount.getOrDefault( graphSize, 0 ) + 1 );
		}

		return graphSizeToCount;
	}

	private static void retainOnlyLargestGraph( final Set< IndexedTile< ? > > tilesSet )
	{
		// get components
		final List< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tilesSet );
		int largestGraphSize = 0, largestGraphId = -1;
		for ( int i = 0; i < graphs.size(); ++i )
		{
			final int graphSize = graphs.get( i ).size();
			if ( graphSize > largestGraphSize )
			{
				largestGraphSize = graphSize;
				largestGraphId = i;
			}
		}

		// retain the largest component
		final List< Tile< ? > > largestGraph = new ArrayList<>();
		largestGraph.addAll( graphs.get( largestGraphId ) );
		tilesSet.clear();

		for ( final Tile< ? > tile : largestGraph )
			tilesSet.add( ( IndexedTile< ? > ) tile );
	}

	private static int countRemainingPairs( final Set< IndexedTile< ? > > remainingTilesSet, final List< SubTilePairwiseMatch > subTilePairwiseMatches )
	{
		int remainingPairs = 0;
		for ( final SubTilePairwiseMatch subTilePairwiseMatch : subTilePairwiseMatches )
			if ( remainingTilesSet.contains( subTilePairwiseMatch.getFixedTile() ) && remainingTilesSet.contains( subTilePairwiseMatch.getMovingTile() ) )
				++remainingPairs;
		return remainingPairs;
	}

	private static void optimizeTranslation( final TileConfiguration tc ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		tc.preAlign();
		tc.optimizeSilently(
				new ErrorStatistic( OPTIMIZER_ITERATIONS + 1 ),
				0, // max allowed error -- does not matter as maxPlateauWidth=maxIterations
				OPTIMIZER_ITERATIONS,
				OPTIMIZER_ITERATIONS,
				1
			);
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static void optimizeHigherOrder( final TileConfiguration tc ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final Set< IndexedTile< ? > > tilesSet = ( Set ) tc.getTiles();

		// first, prealign with translation-only
		final Map< IndexedTile< ? >, Model< ? > > fullModels = new HashMap<>();
		for ( final IndexedTile< ? > tile : tilesSet )
		{
			fullModels.put( tile, tile.getModel() );
			final int dim = tile.getMatches().iterator().next().getP1().getL().length;
			tile.setModel( TileModelFactory.createTranslationModel( dim ) );
		}
		optimizeTranslation( tc );

		// then, solve using full models
		for ( final IndexedTile tile : tilesSet )
		{
			final Model< ? > fullModel = fullModels.get( tile );
			initializeModelWithTranslation( fullModel, tile.getModel() );
			tile.setModel( fullModel );
		}
		tc.optimizeSilently(
				new ErrorStatistic( OPTIMIZER_ITERATIONS + 1 ),
				0, // max allowed error -- does not matter as maxPlateauWidth=maxIterations
				OPTIMIZER_ITERATIONS,
				OPTIMIZER_ITERATIONS,
				DAMPNESS_FACTOR
			);
	}

	private static void initializeModelWithTranslation( final Model< ? > model, final Model< ? > translationModel )
	{
		if ( model instanceof InterpolatedModel< ?, ?, ? > )
			initializeInterpolatedModelWithTranslation( ( InterpolatedModel< ?, ?, ? > ) model, translationModel );
		else if ( translationModel instanceof TranslationModel2D )
			initializeModelWithTranslation2D( model, ( TranslationModel2D ) translationModel );
		else if ( translationModel instanceof TranslationModel3D )
			initializeModelWithTranslation3D( model, ( TranslationModel3D ) translationModel );
		else
			throw new IllegalArgumentException( "expected TranslationModel2D or TranslationModel3D, got " + translationModel );
	}

	private static void initializeInterpolatedModelWithTranslation( final InterpolatedModel< ?, ?, ? > interpolatedModel, final Model< ? > translationModel )
	{
		initializeModelWithTranslation( interpolatedModel.getA(), translationModel );
		initializeModelWithTranslation( interpolatedModel.getB(), translationModel );

		if ( interpolatedModel instanceof InterpolatedAffineModel2D< ?, ? > )
			( ( InterpolatedAffineModel2D< ?, ? > ) interpolatedModel ).interpolate();
		else if ( interpolatedModel instanceof InterpolatedAffineModel3D< ?, ? > )
			( ( InterpolatedAffineModel3D< ?, ? > ) interpolatedModel ).interpolate();
		else
			throw new IllegalArgumentException( "expected InterpolatedAffineModel2D or InterpolatedAffineModel3D, got " + interpolatedModel );
	}

	private static void initializeModelWithTranslation2D( final Model< ? > model, final TranslationModel2D translationModel )
	{
		if ( model instanceof TranslationModel2D )
		{
			( ( TranslationModel2D ) model ).set( translationModel );
		}
		else if ( model instanceof RigidModel2D )
		{
			( ( RigidModel2D ) model ).set( translationModel );
		}
		else if ( model instanceof SimilarityModel2D )
		{
			( ( SimilarityModel2D ) model ).set( translationModel );
		}
		else if ( model instanceof AffineModel2D )
		{
			( ( AffineModel2D ) model ).set( translationModel );
		}
		else
		{
			throw new NotImplementedException( "model initialization is not implemented yet for " + model );
		}
	}

	private static void initializeModelWithTranslation3D( final Model< ? > model, final TranslationModel3D translationModel )
	{
		if ( model instanceof TranslationModel3D )
		{
			( ( TranslationModel3D ) model ).set( translationModel );
		}
		else if ( model instanceof RigidModel3D )
		{
			( ( RigidModel3D ) model ).set( translationModel );
		}
		else if ( model instanceof SimilarityModel3D )
		{
			( ( SimilarityModel3D ) model ).set( translationModel );
		}
		else if ( model instanceof AffineModel3D )
		{
			( ( AffineModel3D ) model ).set( translationModel );
		}
		else
		{
			throw new NotImplementedException( "model initialization is not implemented yet for " + model );
		}
	}

	private static Map< Integer, IndexedTile< ? > > getLostTiles( final Set< IndexedTile< ? > > tilesSet, final List< SubTilePairwiseMatch > subTilePairwiseMatches )
	{
		final Map< Integer, IndexedTile< ? > > lostTiles = new TreeMap<>();

		for ( final SubTilePairwiseMatch subTilePairwiseMatch : subTilePairwiseMatches )
			for ( final IndexedTile< ? > tile : new IndexedTile< ? >[] { subTilePairwiseMatch.getFixedTile(), subTilePairwiseMatch.getMovingTile() } )
				lostTiles.put( tile.getIndex(), tile );

		for ( final IndexedTile< ? > tile : tilesSet )
			lostTiles.remove( tile.getIndex() );

		return lostTiles;
	}

	private static void writeLog( final PrintWriter logWriter, final String log )
	{
		if ( logWriter != null )
			logWriter.println( log );
		System.out.println( log );
	}
}
