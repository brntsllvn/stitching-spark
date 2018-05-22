package org.janelia.stitching;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.stitching.TileSearchRadiusEstimator.EstimatedTileBoxRelativeSearchRadius;
import org.janelia.stitching.TileSearchRadiusEstimator.EstimatedTileBoxWorldSearchRadius;
import org.janelia.util.Conversions;

import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.algorithm.neighborhood.DiamondShape.NeighborhoodsIterableInterval;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.img.imageplus.IntImagePlus;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class AffineStitchingVisualization
{
	public static void main( final String[] args ) throws Exception
	{
		final String logDirectory = args.length > 0 ? args[ 0 ] : null;
		new AffineStitchingVisualization( logDirectory );
	}

	final long[] tileSize = new long[] { 100, 150 };

	final int[] tilePairIndexes = new int[] { 10, 11 };

	final int[] tileBoxPairIndexes = new int[] { 3, 1 };

	final double searchRadiusMultiplier = 3; // 3 * sigma

	final int[] tileEstimationWindow = new int[] { 4, 4 }; // 3x size of a tile

	final double[] simulatedMovingTileBoxOffset = new double[] { 3, -15 };

	private AffineStitchingVisualization( final String logDirectory ) throws Exception
	{
		new ImageJ();

		final TileInfo[] tiles = getTiles();

		final List< SubdividedTileBox > tileBoxes = SplitTileOperations.splitTilesIntoBoxes( tiles, new int[] { 2, 2 } );
		@SuppressWarnings( "unchecked" )
		final List< SubdividedTileBox >[] tilePairBoxes = new ArrayList[] { new ArrayList<>(), new ArrayList<>() };
		for ( final SubdividedTileBox tileBox : tileBoxes )
			for ( int i = 0; i < 2; ++i )
				if ( tileBox.getFullTile().getIndex().intValue() == tilePairIndexes[ i ] )
					tilePairBoxes[ i ].add( tileBox );

		final TileSearchRadiusEstimator searchRadiusEstimator = new TileSearchRadiusEstimator( tiles, searchRadiusMultiplier, tileEstimationWindow );

		final AffineGet[] estimatedTileTransforms = new AffineGet[ 2 ];
		// fixed tile already has an existing transformation
		estimatedTileTransforms[ 0 ] = tiles[ tilePairIndexes[ 0 ] ].getTransform();

		// estimate linear component of the transformation for the moving tile
		final Pair< AffineGet, TranslationGet > estimatedMovingTileLinearAndTranslationComponents = StitchSubdividedTileBoxPair.estimateLinearAndTranslationAffineComponents(
				tiles[ tilePairIndexes[ 1 ] ],
				searchRadiusEstimator
			);
		final AffineGet estimatedMovingTileLinearComponent = estimatedMovingTileLinearAndTranslationComponents.getA();
		final TranslationGet estimatedMovingTileTranslationComponent = estimatedMovingTileLinearAndTranslationComponents.getB();
		// add some noise to it so it is slightly different from the transformation of the fixed tile
		final double[][] estimatedMovingTileLinearComponentWithNoiseAffineMatrix = new double[ estimatedMovingTileLinearComponent.numDimensions() ][ estimatedMovingTileLinearComponent.numDimensions() + 1 ];
		final double[][] noise = new double[][] {
			new double[] { 0.0004, -0.1, 0 },
			new double[] { -0.06, 0.0009, 0 },
		};
		for ( int dRow = 0; dRow < estimatedMovingTileLinearComponent.numDimensions(); ++dRow )
			for ( int dCol = 0; dCol < estimatedMovingTileLinearComponent.numDimensions(); ++dCol )
				estimatedMovingTileLinearComponentWithNoiseAffineMatrix[ dRow ][ dCol ] = estimatedMovingTileLinearComponent.get( dRow, dCol )  + noise[ dRow ][ dCol ];
		final AffineGet estimatedMovingTileLinearComponentWithNoise = TransformUtils.createTransform( estimatedMovingTileLinearComponentWithNoiseAffineMatrix );
		// build the estimated moving tile transform by adding offsets
		estimatedTileTransforms[ 1 ] = StitchSubdividedTileBoxPair.estimateAffineTransformation(
				new ValuePair<>( estimatedMovingTileLinearComponentWithNoise, estimatedMovingTileTranslationComponent )
			);


		final TilePair tilePair = new TilePair( tiles[ tilePairIndexes[ 0 ] ], tiles[ tilePairIndexes[ 1 ] ] );

		final SubdividedTileBoxPair tileBoxPair = new SubdividedTileBoxPair(
				tilePairBoxes[ 0 ].get( tileBoxPairIndexes[ 0 ] ),
				tilePairBoxes[ 1 ].get( tileBoxPairIndexes[ 1 ] )
			);

		final EstimatedTileBoxRelativeSearchRadius movingBoxRelativeSearchRadius = StitchSubdividedTileBoxPair.getCombinedSearchRadiusForMovingBox(
				searchRadiusEstimator,
				tileBoxPair.toArray()
			);

		// write out offset stats
		if  ( logDirectory != null )
		{
			writeOutOffsetStats( movingBoxRelativeSearchRadius, logDirectory );
			System.out.println( "offset stats have been written to file" );
		}

		// draw configuration
		{
			final int[] displaySize = new int[] { 1600, 1200 };
			final IntImagePlus< ARGBType > img = ImagePlusImgs.argbs( displaySize[ 0 ], displaySize[ 1 ] );
			final RandomAccess< ARGBType > imgRandomAccess = img.randomAccess();

			drawStageTiles( tiles, displaySize, imgRandomAccess, new ARGBType( ARGBType.rgba( 255, 0, 0, 32 ) ) );
			drawStitchedTiles( tiles, displaySize, imgRandomAccess, new ARGBType( ARGBType.rgba( 0, 255, 0, 32 ) ) );

			drawTileBoxes( tilePairBoxes[ 0 ], displaySize, imgRandomAccess, new ARGBType( ARGBType.rgba( 0, 96, 96, 32 ) ) );
			drawTileBoxes( tilePairBoxes[ 1 ], displaySize, imgRandomAccess, new ARGBType( ARGBType.rgba( 96, 0, 96, 32 ) ) );

			// draw transformed tiles
			drawTransformedRectangle(
					getLocalRealIntervalCorners( tilePair.getA() ),
					estimatedTileTransforms[ 0 ],
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 64, 64, 255, 255 ) )
				);
			drawTransformedRectangle(
					getLocalRealIntervalCorners( tilePair.getB() ),
					estimatedTileTransforms[ 1 ],
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 255, 255, 255, 64 ) )
				);

			// draw transformed tile boxes
			drawTransformedRectangle(
					getRealIntervalCorners( tileBoxPair.getA() ),
					estimatedTileTransforms[ 0 ],
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 64, 64, 255, 255 ) )
				);
			drawTransformedRectangle(
					getRealIntervalCorners( tileBoxPair.getB() ),
					estimatedTileTransforms[ 1 ],
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 192, 192, 192, 255 ) )
				);


			// draw offset tiles (where the linear part of the transformation has been undone)
			for ( final TileInfo tile : tiles )
			{
				if ( tile.getTransform() != null )
				{
					final InvertibleRealTransform linearComponent = TransformUtils.getLinearComponent( tile.getTransform() );
					final RealTransformSequence seq = new RealTransformSequence();
					seq.add( tile.getTransform() );
					seq.add( linearComponent.inverse() );

					drawTransformedRectangle(
							getLocalRealIntervalCorners( tile ),
							seq,
							displaySize,
							imgRandomAccess,
							new ARGBType( ARGBType.rgba( 0, 0, 255, 255 ) )
						);
				}
			}


			final RealTransformSequence searchRadiusDisplayTransform = new RealTransformSequence();
			searchRadiusDisplayTransform.add( estimatedTileTransforms[ 1 ] );
			searchRadiusDisplayTransform.add( TransformUtils.getLinearComponent( estimatedTileTransforms[ 1 ] ).inverse() );

			// draw combined error ellipse (moving+fixed)
			drawErrorEllipse(
					movingBoxRelativeSearchRadius.combinedErrorEllipse,
					searchRadiusDisplayTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 255, 255, 0, 255 ) )
				);



			final ImagePlus imp = img.getImagePlus();
			imp.show();
		}

		{
			final int[] displaySize = new int[] { 800, 400 };
			final Translation2D displayOffsetTransform = new Translation2D( displaySize[ 0 ] / 2, displaySize[ 1 ] / 2 );

			final InvertibleRealTransform movingTileToFixedBoxTransform = StitchSubdividedTileBoxPair.getMovingTileToFixedBoxTransform(
					tileBoxPair,
					estimatedTileTransforms
				);

			final InvertibleRealTransformSequence movingTileToDisplayTransform = new InvertibleRealTransformSequence();
			movingTileToDisplayTransform.add( movingTileToFixedBoxTransform );
			movingTileToDisplayTransform.add( displayOffsetTransform );

			final IntImagePlus< ARGBType > img = ImagePlusImgs.argbs( displaySize[ 0 ], displaySize[ 1 ] );
			final RandomAccess< ARGBType > imgRandomAccess = img.randomAccess();

			drawAxes( displaySize, imgRandomAccess );

			// draw bounding box of the moving tile box
			final RealInterval transformedMovingBoxInterval = TileOperations.getTransformedBoundingBoxReal(
					tileBoxPair.getB(),
					movingTileToFixedBoxTransform
				);
			drawRectangle(
					getRealIntervalCorners( transformedMovingBoxInterval ),
					displayOffsetTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 64, 64, 64, 64 ) )
				);

			// draw zero-min fixed tile box
			drawRectangle(
					getLocalRealIntervalCorners( tileBoxPair.getA() ),
					displayOffsetTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 64, 64, 255, 255 ) )
				);

			// draw transformed moving tile box
			drawTransformedRectangle(
					getRealIntervalCorners( tileBoxPair.getB() ),
					movingTileToDisplayTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 192, 192, 192, 255 ) )
				);


			// account for the offset between zero-min of the transformed tile and the ROI
			final double[] transformedMovingTileBoxToBoundingBoxOffset = StitchSubdividedTileBoxPair.getTransformedMovingBoxToBoundingBoxOffset(
					tileBoxPair,
					estimatedTileTransforms
				);
			printDoubleArray(
					System.lineSeparator() + "Offset between transformed top-left point and bounding box: %s",
					transformedMovingTileBoxToBoundingBoxOffset,
					2
				);

			movingBoxRelativeSearchRadius.combinedErrorEllipse.setErrorEllipseTransform(
					StitchSubdividedTileBoxPair.getErrorEllipseTransform( tileBoxPair, estimatedTileTransforms )
				);

			// draw transformed search radius in the fixed box space
			System.out.println( System.lineSeparator() + "Drawing world->local transformed error ellipse..." );
			drawErrorEllipse(
					movingBoxRelativeSearchRadius.combinedErrorEllipse,
					displayOffsetTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 255, 255, 0, 255 ) )
				);

			final ImagePlus imp = img.getImagePlus();
			imp.show();

			Thread.sleep( 3000 );


			// validate ellipse test by drawing boundaries after testing all display points
			System.out.println( System.lineSeparator() + "Drawing result of testing display points against the transformed error ellipse..." );
			drawErrorEllipseByInverseTest(
					movingBoxRelativeSearchRadius.combinedErrorEllipse,
					displayOffsetTransform,
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 0, 255, 255, 255 ) )
				);
			imp.updateAndDraw();
			System.out.println( "Done" );

			Thread.sleep( 3000 );


			// draw new offset position of moving tile box
			drawRectangle(
					getLocalRealIntervalCorners( transformedMovingBoxInterval ),
					new Translation2D( simulatedMovingTileBoxOffset[ 0 ] + displayOffsetTransform.getTranslation( 0 ), simulatedMovingTileBoxOffset[ 1 ] + displayOffsetTransform.getTranslation( 1 ) ),
					displaySize,
					imgRandomAccess,
					new ARGBType( ARGBType.rgba( 64, 64, 0, 64 ) )
				);
			imp.updateAndDraw();



			// test that simulated offset is within the error ellipse
			System.out.println();
			if ( movingBoxRelativeSearchRadius.combinedErrorEllipse.testOffset( simulatedMovingTileBoxOffset ) )
				System.out.println( "New offset is inside the accepted error range" );
			else
				System.out.println( "New offset is outside the accepted error range" );
		}

		System.out.println( "OK" );
	}

	private TileInfo[] getTiles()
	{
		final double[][] stagePositions = new double[][] {
				new double[] { 200, 100 },
				new double[] { 281, 105 },
				new double[] { 358, 102 },
				new double[] { 430, 108 },

				new double[] { 193, 240 },
				new double[] { 274, 231 },
				new double[] { 362, 237 },
				new double[] { 447, 241 },

				new double[] { 204, 378 },
				new double[] { 288, 382 },
				new double[] { 377, 389 },
				new double[] { 460, 385 },
		};

		final double[][] stitchedPositions = new double[][] {
			new double[] { 800, 100 },
			new double[] { 888, 108 },
			new double[] { 974, 116 },
			new double[] { 1060, 125 },

			new double[] { 802, 236 },
			new double[] { 890, 247 },
			new double[] { 980, 254 },
			new double[] { 1060, 260 },

			new double[] { 798, 380 },
			new double[] { 885, 388 },
			new double[] { 970, 400 },
			null // new double[] { 1065, 408 },
		};

		final double rotationAngle = 30;
//		final double[] postTranslation = new double[] { 400, -400 };

		final List< TileInfo > tiles = new ArrayList<>();
		for ( int i = 0; i < stagePositions.length; ++i )
		{
			final TileInfo tile = new TileInfo( 2 );
			tile.setIndex( i );
			tile.setSize( tileSize );
			tile.setPosition( stagePositions[ i ] );

			if ( stitchedPositions[ i ] != null )
			{
				final AffineTransform2D stitchedTransform = new AffineTransform2D();
				stitchedTransform.translate( stitchedPositions[ i ] );
				stitchedTransform.rotate( Math.toRadians( rotationAngle ) );
//				stitchedTransform.translate( postTranslation );
				tile.setTransform( stitchedTransform );
			}

			tiles.add( tile );
		}

		return tiles.toArray( new TileInfo[ 0 ] );
	}

	private void writeOutOffsetStats( final EstimatedTileBoxRelativeSearchRadius combinedSearchRadius, final String logDirectory ) throws IOException
	{
		final String baseFileName = "offset-stats.csv";
		final String[] fileNameSuffixes = new String[] { "fixed", "moving" };
		final EstimatedTileBoxWorldSearchRadius[] searchRadiusStats = new EstimatedTileBoxWorldSearchRadius[] {
				combinedSearchRadius.fixedAndMovingSearchRadiusStats.getA(),
				combinedSearchRadius.fixedAndMovingSearchRadiusStats.getB()
			};

		final String[] axesStr = new String[] { "x", "y", "z" };
		final String delimiter = " ";

		for ( int i = 0; i < 2; ++i )
		{
			final String fileName = Paths.get( logDirectory, Utils.addFilenameSuffix( baseFileName, "_" + fileNameSuffixes[ i ] ) ).toString();
			try ( final PrintWriter writer = new PrintWriter(fileName ) )
			{
				for ( int d = 0; d < searchRadiusStats[ i ].errorEllipse.numDimensions(); ++d )
					writer.print( "stage_" + axesStr[ d ] + delimiter );

				for ( int d = 0; d < searchRadiusStats[ i ].errorEllipse.numDimensions(); ++d )
					writer.print( ( d == 0 ? "" : delimiter ) + "world_" + axesStr[ d ] );

				writer.println();

				for ( final Pair< RealPoint, RealPoint > stageAndWorld : searchRadiusStats[ i ].stageAndWorldCoordinates )
				{
					for ( int d = 0; d < stageAndWorld.getA().numDimensions(); ++d )
						writer.print( String.format( "%.2f", stageAndWorld.getA().getDoublePosition( d ) ) + delimiter );

					for ( int d = 0; d < stageAndWorld.getB().numDimensions(); ++d )
						writer.print( ( d == 0 ? "" : delimiter ) + String.format( "%.2f", stageAndWorld.getB().getDoublePosition( d ) ) );

					writer.println();
				}
			}
		}



		System.out.println();
		for ( int i = 0; i < 2; ++i )
		{
			System.out.println( fileNameSuffixes[ i ] + " search radius offsets:" );
			for ( final Pair< RealPoint, RealPoint > stageAndWorld : searchRadiusStats[ i ].stageAndWorldCoordinates )
			{
				final double[] offset = new double[ Math.max( stageAndWorld.getA().numDimensions(), stageAndWorld.getB().numDimensions() ) ];
				for ( int d = 0; d < offset.length; ++d )
					offset[ d ] = stageAndWorld.getB().getDoublePosition( d ) - stageAndWorld.getA().getDoublePosition( d );
				printDoubleArray( offset, 2 );
			}
			System.out.println();
		}
	}

	private void drawStageTiles( final TileInfo[] tiles, final int[] displaySize, final RandomAccess< ARGBType > imgRandomAccess, final ARGBType color )
	{
		for ( final TileInfo tile : tiles )
		{
			drawRectangle(
					getRealIntervalCorners( tile ),
					null,
					displaySize,
					imgRandomAccess,
					color
				);
		}
	}

	private void drawStitchedTiles( final TileInfo[] tiles, final int[] displaySize, final RandomAccess< ARGBType > imgRandomAccess, final ARGBType color )
	{
		for ( final TileInfo tile : tiles )
		{
			if ( tile.getTransform() != null )
			{
				drawTransformedRectangle(
						getLocalRealIntervalCorners( tile ),
						tile.getTransform(),
						displaySize,
						imgRandomAccess,
						color
					);
			}
		}
	}

	private void drawTileBoxes( final List< SubdividedTileBox > tileBoxes, final int[] displaySize, final RandomAccess< ARGBType > imgRandomAccess, final ARGBType color )
	{
		for ( final SubdividedTileBox tileBox : tileBoxes )
		{
			final AffineTransform2D tileTransform = new AffineTransform2D();
			for ( int d = 0; d < tileBox.getFullTile().numDimensions(); ++d )
				tileTransform.set( tileBox.getFullTile().getPosition( d ), d, 2 );

			drawRectangle(
					getRealIntervalCorners( tileBox ),
					tileTransform,
					displaySize,
					imgRandomAccess,
					color
				);
		}
	}

	private void drawRectangle(
			final double[][] rectCorners,
			final AffineGet displayTransform,
			final int[] displaySize,
			final RandomAccess< ARGBType > imgRandomAccess,
			final ARGBType color )
	{
		final int dim = displaySize.length;
		final double[][] rectDisplayCorners = new double[ 2 ][ dim ];

		for ( int i = 0; i < 2; ++i )
		{
			rectDisplayCorners[ i ] = rectCorners[ i ].clone();
			if ( displayTransform != null )
				displayTransform.apply( rectDisplayCorners[ i ], rectDisplayCorners[ i ] );
		}

		drawTransformedRectangle( rectDisplayCorners, null, displaySize, imgRandomAccess, color );
	}

	private Set< Integer > drawTransformedRectangle(
			final double[][] rectCorners,
			final RealTransform pointTransform,
			final int[] displaySize,
			final RandomAccess< ARGBType > imgRandomAccess,
			final ARGBType color )
	{
		final int dim = displaySize.length;
		final double[] pos = new double[ dim ];
		final int[] posDisplay = new int[ dim ];

		final Set< Integer > visitedPixels = new HashSet<>();

		for ( int dMove = 0; dMove < dim; ++dMove )
		{
			final int[] fixedDimsPossibilities = new int[ dim - 1 ];
			Arrays.fill( fixedDimsPossibilities, 2 );
			final IntervalIterator fixedDimsPossibilitiesIterator = new IntervalIterator( fixedDimsPossibilities );

			final List< Integer > fixedDims = new ArrayList<>();
			for ( int d = 0; d < dim; ++d )
				if ( d != dMove )
					fixedDims.add( d );

			while ( fixedDimsPossibilitiesIterator.hasNext() )
			{
				fixedDimsPossibilitiesIterator.fwd();

				for ( double c = rectCorners[ 0 ][ dMove ]; c <= rectCorners[ 1 ][ dMove ]; c += 0.1 )
				{
					pos[ dMove ] = c;
					for ( int fixedDimIndex = 0; fixedDimIndex < fixedDims.size(); ++fixedDimIndex )
					{
						final int dFixed = fixedDims.get( fixedDimIndex );
						final int minOrMax = fixedDimsPossibilitiesIterator.getIntPosition( fixedDimIndex );
						pos[ dFixed ] = rectCorners[ minOrMax ][ dFixed ];
					}

					if ( pointTransform != null )
						pointTransform.apply( pos, pos );

					for ( int d = 0; d < dim; ++d )
						posDisplay[ d ] = ( int ) Math.round( pos[ d ] );

					boolean insideView = true;
					for ( int d = 0; d < dim; ++d )
						insideView &= ( posDisplay[ d ] >= 0 && posDisplay[ d ] < displaySize[ d ] );

					if ( insideView )
					{
						final int pixelIndex = IntervalIndexer.positionToIndex( posDisplay, displaySize );
						if ( !visitedPixels.contains( pixelIndex ) )
						{
							imgRandomAccess.setPosition( posDisplay );
							imgRandomAccess.get().set( color );
							visitedPixels.add( pixelIndex );
						}
					}
				}
			}
		}

		return visitedPixels;
	}

	private Set< Integer > drawErrorEllipse(
			final ErrorEllipse errorEllipse,
			final RealTransform displayTransform,
			final int[] displaySize,
			final RandomAccess< ARGBType > imgRandomAccess,
			final ARGBType color )
	{
		final int dim = displaySize.length;
		final double[] pos = new double[ dim ];
		final int[] posDisplay = new int[ dim ];

		final Interval errorEllipseBoundingBox = Intervals.smallestContainingInterval( errorEllipse.estimateBoundingBox() );

		System.out.println( "Error ellipse bounding box size: " + Arrays.toString( Intervals.dimensionsAsLongArray( errorEllipseBoundingBox ) ) );

		final RandomAccessibleInterval< BitType > errorEllipseTestImg = Views.translate(
				ArrayImgs.bits( Intervals.dimensionsAsLongArray( errorEllipseBoundingBox ) ),
				Intervals.minAsLongArray( errorEllipseBoundingBox )
			);

		final Cursor< BitType > errorEllipseTestImgCursor = Views.iterable( errorEllipseTestImg ).localizingCursor();
		while ( errorEllipseTestImgCursor.hasNext() )
		{
			errorEllipseTestImgCursor.fwd();
			errorEllipseTestImgCursor.localize( pos );
			if ( errorEllipse.testOffset( pos ) )
				errorEllipseTestImgCursor.get().setOne();
		}

		final List< RealPoint > boundaryPoints = findBoundaryPoints( errorEllipseTestImg );
		final Set< Integer > visitedPixels = new HashSet<>();

		for ( final RealPoint pt : boundaryPoints )
		{
			pt.localize( pos );

			if ( displayTransform != null )
				displayTransform.apply( pos, pos );

			for ( int d = 0; d < dim; ++d )
				posDisplay[ d ] = ( int ) Math.round( pos[ d ] );

			boolean insideView = true;
			for ( int d = 0; d < dim; ++d )
				insideView &= ( posDisplay[ d ] >= 0 && posDisplay[ d ] < displaySize[ d ] );

			if ( insideView )
			{
				final int pixelIndex = IntervalIndexer.positionToIndex( posDisplay, displaySize );
				if ( !visitedPixels.contains( pixelIndex ) )
				{
					imgRandomAccess.setPosition( posDisplay );
					imgRandomAccess.get().set( color );
					visitedPixels.add( pixelIndex );
				}
			}
		}

		return visitedPixels;
	}

	private Set< Integer > drawErrorEllipseByInverseTest(
			final ErrorEllipse errorEllipse,
			final AffineGet displayTransform,
			final int[] displaySize,
			final RandomAccess< ARGBType > imgRandomAccess,
			final ARGBType color )
	{
		final int dim = displaySize.length;
		final double[] pos = new double[ dim ];
		final int[] posDisplay = new int[ dim ];

		final RandomAccessibleInterval< BitType > displayTestImg = ArrayImgs.bits( Conversions.toLongArray( displaySize ) );
		final Cursor< BitType > displayTestImgCursor = Views.iterable( displayTestImg ).localizingCursor();
		while ( displayTestImgCursor.hasNext() )
		{
			displayTestImgCursor.fwd();
			displayTestImgCursor.localize( pos );
			displayTransform.applyInverse( pos, pos );
			if ( errorEllipse.testOffset( pos ) )
				displayTestImgCursor.get().setOne();
		}

		final List< RealPoint > boundaryPoints = findBoundaryPoints( displayTestImg );
		final Set< Integer > visitedPixels = new HashSet<>();

		for ( final RealPoint pt : boundaryPoints )
		{
			pt.localize( pos );
			for ( int d = 0; d < dim; ++d )
				posDisplay[ d ] = ( int ) Math.round( pos[ d ] );

			boolean insideView = true;
			for ( int d = 0; d < dim; ++d )
				insideView &= ( posDisplay[ d ] >= 0 && posDisplay[ d ] < displaySize[ d ] );

			if ( insideView )
			{
				final int pixelIndex = IntervalIndexer.positionToIndex( posDisplay, displaySize );
				if ( !visitedPixels.contains( pixelIndex ) )
				{
					imgRandomAccess.setPosition( posDisplay );
					imgRandomAccess.get().set( color );
					visitedPixels.add( pixelIndex );
				}
			}
		}

		return visitedPixels;
	}

	private List< RealPoint > findBoundaryPoints( final RandomAccessibleInterval< BitType > binaryImg )
	{
		final List< RealPoint > transformedBoundaryPoints = new ArrayList<>();
		final NeighborhoodsIterableInterval< BitType > neighborhoods = new DiamondShape( 1 ).neighborhoodsSafe( binaryImg );
		final Cursor< Neighborhood< BitType > > neighborhoodsCursor = neighborhoods.localizingCursor();
		final RandomAccess< BitType > binaryImgRandomAccess = binaryImg.randomAccess();
		while ( neighborhoodsCursor.hasNext() )
		{
			final Neighborhood< BitType > neighborhood = neighborhoodsCursor.next();
			binaryImgRandomAccess.setPosition( neighborhoodsCursor );
			if ( binaryImgRandomAccess.get().get() )
			{
				// it is a contour point if it is inside the ellipse but at least one of the neighbors is outside the ellipse
				boolean isInnerPoint = true;

				final Cursor< BitType > neighborhoodCursor = neighborhood.cursor();
				while ( neighborhoodCursor.hasNext() )
				{
					neighborhoodCursor.fwd();

					boolean insideInterval = true;
					for ( int d = 0; d < neighborhoodCursor.numDimensions(); ++d )
						insideInterval &= neighborhoodCursor.getLongPosition( d ) >= binaryImg.min( d ) && neighborhoodCursor.getLongPosition( d ) <= binaryImg.max( d );

					if ( insideInterval )
						isInnerPoint &= neighborhoodCursor.get().get();
				}

				if ( !isInnerPoint )
					transformedBoundaryPoints.add( new RealPoint( neighborhoodsCursor ) );
			}
		}
		return transformedBoundaryPoints;
	}

	private void drawAxes(
			final int[] displaySize,
			final RandomAccess< ARGBType > imgRandomAccess )
	{
		final ARGBType axesColor = new ARGBType( ARGBType.rgba( 32, 32, 32, 32 ) );
		for ( int d = 0; d < displaySize.length; ++d )
		{
			final int[] displayPos = new int[ displaySize.length ];
			for ( int i = 0; i < displaySize.length; ++i )
				displayPos[ i ] = displaySize[ i ] / 2;

			for ( int c = 0; c < displaySize[ d ]; ++c )
			{
				displayPos[ d ] = c;
				imgRandomAccess.setPosition( displayPos );
				imgRandomAccess.get().set( axesColor );
			}
		}
	}

	private double[][] getRealIntervalCorners( final RealInterval realInterval )
	{
		return new double[][] {
				Intervals.minAsDoubleArray( realInterval ),
				Intervals.maxAsDoubleArray( realInterval )
		};
	}

	private double[][] getLocalRealIntervalCorners( final RealInterval realInterval )
	{
		final double[] localMax = new double[ realInterval.numDimensions() ];
		for ( int d = 0; d < realInterval.numDimensions(); ++d )
			localMax[ d ] = realInterval.realMax( d ) - realInterval.realMin( d );

		return new double[][] {
				new double[ realInterval.numDimensions() ],
				localMax
		};
	}

	private static void printDoubleArray( final double[] arr, final int numDecimalPlaces )
	{
		printDoubleArray( "%s", arr, numDecimalPlaces );
	}
	private static void printDoubleArray( final String format, final double[] arr, final int numDecimalPlaces )
	{
		final String[] arrStr = new String[ arr.length ];
		for ( int d = 0; d < arrStr.length; ++d )
			arrStr[ d ] = String.format( "%." + numDecimalPlaces + "f", arr[ d ] );
		System.out.println( String.format( format, Arrays.toString( arrStr ) ) );
	}
}