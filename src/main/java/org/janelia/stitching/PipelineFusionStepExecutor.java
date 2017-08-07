package org.janelia.stitching;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.flatfield.FlatfieldCorrection;
import org.janelia.saalfeldlab.n5.CompressionType;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.bdv.N5ExportMetadata;
import org.janelia.saalfeldlab.n5.bdv.N5ExportMetadata.DisplayRange;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.N5DownsamplingSpark;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.RandomAccessiblePairNullable;

/**
 * Fuses a set of tiles within a set of small square cells using linear blending.
 * Saves fused tile configuration on the disk.
 *
 * @author Igor Pisarev
 */

public class PipelineFusionStepExecutor< T extends NativeType< T > & RealType< T >, U extends NativeType< U > & RealType< U > > extends PipelineStepExecutor
{
	private static final long serialVersionUID = -8151178964876747760L;

	final TreeMap< Integer, long[] > levelToImageDimensions = new TreeMap<>(), levelToCellSize = new TreeMap<>();

	double[] normalizedVoxelDimensions;

	Broadcast< Map< Integer, Set< Integer > > > broadcastedPairwiseConnectionsMap;
	Broadcast< RandomAccessiblePairNullable< U, U > > broadcastedFlatfieldCorrection;

	public PipelineFusionStepExecutor( final StitchingJob job, final JavaSparkContext sparkContext )
	{
		super( job, sparkContext );
	}

	@Override
	public void run() throws PipelineExecutionException
	{
		try {
			runImpl();
		} catch (final IOException e) {
			throw new PipelineExecutionException( e.getMessage(), e.getCause() );
		}
	}

	// TODO: add comprehensive support for 4D images where multiple channels are encoded as the 4th dimension
	private void runImpl() throws PipelineExecutionException, IOException
	{
		for ( int channel = 0; channel < job.getChannels(); channel++ )
			TileOperations.translateTilesToOriginReal( job.getTiles( channel ) );

		final String overlapsPathSuffix = job.getArgs().exportOverlaps() ? "-overlaps" : "";

		// determine the best location for storing the export files (near the tile configurations by default)
		String baseExportPath = null;
		for ( final String inputFilePath : job.getArgs().inputTileConfigurations() )
		{
			final String inputFolderPath = Paths.get( inputFilePath ).getParent().toString();
			if ( baseExportPath == null )
			{
				baseExportPath = inputFolderPath;
			}
			else if ( !baseExportPath.equals( inputFolderPath ) )
			{
				// go one level upper since channels are stored in individual subfolders
				baseExportPath = Paths.get( inputFolderPath ).getParent().toString();
				break;
			}
		}

		final N5Writer n5 = N5.openFSWriter( baseExportPath );
		if ( !overlapsPathSuffix.isEmpty() )
			n5.createGroup( overlapsPathSuffix );

		double[][] downsamplingFactors = null;

		final double[] voxelDimensions = job.getPixelResolution();
		normalizedVoxelDimensions = Utils.normalizeVoxelDimensions( voxelDimensions );
		System.out.println( "Normalized voxel size = " + Arrays.toString( normalizedVoxelDimensions ) );

		// loop over channels
		for ( int ch = 0; ch < job.getChannels(); ch++ )
		{
			final int channel = ch;
			System.out.println( "Processing channel #" + channel );

			final String absoluteChannelPath = job.getArgs().inputTileConfigurations().get( channel );
			final String absoluteChannelPathNoExt = absoluteChannelPath.lastIndexOf( '.' ) != -1 ? absoluteChannelPath.substring( 0, absoluteChannelPath.lastIndexOf( '.' ) ) : absoluteChannelPath;

			final String channelGroupPath = overlapsPathSuffix + "/c" + channel;
			n5.createGroup( channelGroupPath );

			// special mode which allows to export only overlaps of tile pairs that have been used for final stitching
			final Map< Integer, Set< Integer > > pairwiseConnectionsMap = getPairwiseConnectionsMap( absoluteChannelPath );
			if ( pairwiseConnectionsMap != null )
				System.out.println( "[Export overlaps mode] Broadcasting pairwise connections map" );
			broadcastedPairwiseConnectionsMap = sparkContext.broadcast( pairwiseConnectionsMap );

			// prepare flatfield correction images
			// use it as a folder with the input file's name
			final RandomAccessiblePairNullable< U, U >  flatfieldCorrection = FlatfieldCorrection.loadCorrectionImages(
					channelPathNoExt + "/v.tif",
					channelPathNoExt + "/z.tif"
				);
			if ( flatfieldCorrection != null )
				System.out.println( "[Flatfield correction] Broadcasting flatfield correction images" );
			broadcastedFlatfieldCorrection = sparkContext.broadcast( flatfieldCorrection );

			// Generate export of the first scale level
			fuse( baseExportPath, channelGroupPath, job.getTiles( channel ) );

			// Generate lower scale levels
			final N5DownsamplingSpark< T > n5Downsampler = new N5DownsamplingSpark<>( sparkContext );
			downsamplingFactors = n5Downsampler.downsampleIsotropic( baseExportPath, channelGroupPath, new FinalVoxelDimensions( "um", voxelDimensions ) );

			broadcastedPairwiseConnectionsMap.destroy();
			broadcastedFlatfieldCorrection.destroy();
		}

		System.out.println( "All channels have been exported" );

		final N5ExportMetadata exportMetadata = new N5ExportMetadata( baseExportPath );
		exportMetadata.setDefaultScales( downsamplingFactors );
		exportMetadata.setDefaultPixelResolution( new FinalVoxelDimensions( "um", job.getPixelResolution() ) );
		exportMetadata.setDefaultDisplayRange( new DisplayRange( 50, 1000 ) ); // TODO: extract display range from the data
	}


	private int[] getOptimalCellSize()
	{
		final int[] cellSize = new int[ job.getDimensionality() ];
		for ( int d = 0; d < job.getDimensionality(); d++ )
			cellSize[ d ] = ( int ) Math.round( job.getArgs().fusionCellSize() / normalizedVoxelDimensions[ d ] );
		return cellSize;
	}

	private void fuse( final String baseExportPath, final String channelPath, final TileInfo[] tiles ) throws IOException
	{
		final int[] cellSize = getOptimalCellSize();

		final Boundaries boundingBox;
		if ( job.getArgs().minCoord() != null && job.getArgs().maxCoord() != null )
			boundingBox = new Boundaries( job.getArgs().minCoord(), job.getArgs().maxCoord() );
		else
			boundingBox = TileOperations.getCollectionBoundaries( tiles );

		final long[] offset = Intervals.minAsLongArray( boundingBox );
		final long[] dimensions = Intervals.dimensionsAsLongArray( boundingBox );

		final int[] biggerCellSize = new int[ cellSize.length ];
		for ( int d = 0; d < biggerCellSize.length; ++d )
			biggerCellSize[ d ] = cellSize[ d ] * 4;

		final List< TileInfo > biggerCells = TileOperations.divideSpace( boundingBox, new FinalDimensions( biggerCellSize ) );
		final String scaleLevelPath = channelPath + "/s0";
		N5.openFSWriter( baseExportPath ).createDataset( scaleLevelPath, Intervals.dimensionsAsLongArray( boundingBox ), cellSize, getN5DataType( tiles[ 0 ].getType() ), CompressionType.GZIP );

		sparkContext.parallelize( biggerCells ).foreach( biggerCell ->
			{
				final List< TileInfo > tilesWithinCell = TileOperations.findTilesWithinSubregion( tiles, biggerCell );
				if ( tilesWithinCell.isEmpty() )
					return;

				final Boundaries biggerCellBox = biggerCell.getBoundaries();
				final long[] biggerCellOffsetCoordinates = new long[ biggerCellBox.numDimensions() ];
				for ( int d = 0; d < biggerCellOffsetCoordinates.length; d++ )
					biggerCellOffsetCoordinates[ d ] = biggerCellBox.min( d ) - offset[ d ];

				final long[] biggerCellGridPosition = new long[ biggerCell.numDimensions() ];
				final CellGrid cellGrid = new CellGrid( dimensions, cellSize );
				cellGrid.getCellPosition( biggerCellOffsetCoordinates, biggerCellGridPosition );

				final ImagePlusImg< T, ? > outImg = FusionPerformer.fuseTilesWithinCell(
							job.getArgs().blending() ? FusionMode.BLENDING : FusionMode.MAX_MIN_DISTANCE,
							tilesWithinCell,
							biggerCellBox,
							broadcastedFlatfieldCorrection.value(),
							broadcastedPairwiseConnectionsMap.value() );

				final N5Writer n5Local = N5.openFSWriter( baseExportPath );
				N5Utils.saveBlock( outImg, n5Local, scaleLevelPath, biggerCellGridPosition );
			}
		);
	}

	private DataType getN5DataType( final ImageType imageType )
	{
		switch ( imageType )
		{
		case GRAY8:
			return DataType.UINT8;
		case GRAY16:
			return DataType.UINT16;
		case GRAY32:
			return DataType.FLOAT32;
		default:
			return null;
		}
	}

	private Map< Integer, Set< Integer > > getPairwiseConnectionsMap( final String channelPath ) throws PipelineExecutionException
	{
		if ( !job.getArgs().exportOverlaps() )
			return null;

		final Map< Integer, Set< Integer > > pairwiseConnectionsMap = new HashMap<>();
		try
		{
			final List< SerializablePairWiseStitchingResult > pairwiseShifts = TileInfoJSONProvider.loadPairwiseShifts( Utils.addFilenameSuffix( channelPath, "_pairwise" ) );
			for ( final SerializablePairWiseStitchingResult pairwiseShift : pairwiseShifts )
			{
				if ( pairwiseShift.getIsValidOverlap() )
				{
					final TileInfo[] pairArr = pairwiseShift.getTilePair().toArray();
					for ( int i = 0; i < 2; ++i )
					{
						if ( !pairwiseConnectionsMap.containsKey( pairArr[ i ].getIndex() ) )
							pairwiseConnectionsMap.put( pairArr[ i ].getIndex(), new HashSet<>() );
						pairwiseConnectionsMap.get( pairArr[ i ].getIndex() ).add( pairArr[ ( i + 1 ) % 2 ].getIndex() );
					}
				}
			}
		}
		catch ( final IOException e )
		{
			throw new PipelineExecutionException( "--overlaps mode is requested but the pairwise shifts file is not available", e );
		}

		return pairwiseConnectionsMap;
	}
}
