package org.janelia.stitching;

import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.dataaccess.CloudURI;
import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.dataaccess.DataProviderType;
import org.janelia.dataaccess.PathResolver;
import org.janelia.flatfield.FlatfieldCorrectedRandomAccessible;
import org.janelia.flatfield.FlatfieldCorrection;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.scijava.Context;
import org.scijava.plugin.Parameter;

import ij.ImagePlus;
import net.imagej.ops.OpService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.ClampingConverter;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealConverter;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.RandomAccessiblePairNullable;
import net.imglib2.view.Views;
import scala.Tuple2;

public class DeconvolutionSpark
{
	private static class DeconvolutionCmdArgs implements Serializable
	{
		private static final long serialVersionUID = 215043103837732209L;

		@Option(name = "-i", aliases = { "--inputConfigurationPath" }, required = true,
				usage = "Path to an input tile configuration file. Multiple configurations (channels) can be passed at once.")
		private List< String > inputChannelsPaths;

		@Option(name = "-p", aliases = { "--psfPath" }, required = true,
				usage = "Path to the point-spread function images. In case of multiple input channels, their corresponding PSFs must be passed in the same order.")
		private List< String > psfPaths;

		@Option(name = "-n", aliases = { "--numIterations" }, required = false,
				usage = "Number of iterations to perform for the deconvolution algorithm.")
		private int numIterations = 10;

		@Option(name = "-b", aliases = { "--backgroundValue" }, required = false,
				usage = "Background value of the data for each channel. If omitted, the pivot value estimated in the Flatfield Correction step will be used (default).")
		private Double backgroundValue = null;

		@Option(name = "-f", aliases = { "--outputFloat" }, required = false,
				usage = "If specified, the output images are saved as 32-bit float images. If omitted, they are rescaled into the value range of the input datatype (default).")
		private boolean exportAsFloat = false;

		private boolean parsedSuccessfully = false;

		public DeconvolutionCmdArgs( final String... args ) throws IllegalArgumentException
		{
			final CmdLineParser parser = new CmdLineParser( this );
			try
			{
				parser.parseArgument( args );
				parsedSuccessfully = true;
			}
			catch ( final CmdLineException e )
			{
				System.err.println( e.getMessage() );
				parser.printUsage( System.err );
			}

			if ( inputChannelsPaths.size() != psfPaths.size() )
				throw new IllegalArgumentException( "Number of input channels should match the number of PSFs" );

			// make sure that input paths are absolute file paths if running on a traditional filesystem
			for ( int i = 0; i < inputChannelsPaths.size(); ++i )
				if ( !CloudURI.isCloudURI( inputChannelsPaths.get( i ) ) )
					inputChannelsPaths.set( i, Paths.get( inputChannelsPaths.get( i ) ).toAbsolutePath().toString() );

			for ( int i = 0; i < psfPaths.size(); ++i )
				if ( !CloudURI.isCloudURI( psfPaths.get( i ) ) )
					psfPaths.set( i, Paths.get( psfPaths.get( i ) ).toAbsolutePath().toString() );
		}
	}

	private static class OpServiceContainer
	{
		private static final OpServiceContainer INSTANCE = new OpServiceContainer();

		public static OpServiceContainer getInstance()
		{
			return INSTANCE;
		}

		@Parameter
		private Context context;

		@Parameter
		private OpService ops;

		private OpServiceContainer()
		{
			new Context( OpService.class ).inject( this );
		}

		public OpService ops()
		{
			return ops;
		}
	}

	private static final int MAX_PARTITIONS = 15000;
	private static final String FILENAME_SUFFIX_FLOAT = "-float";

	public static < T extends NativeType< T > & RealType< T >, U extends NativeType< U > & RealType< U > > void main( final String[] args ) throws Exception
	{
		final DeconvolutionCmdArgs parsedArgs = new DeconvolutionCmdArgs( args );
		if ( !parsedArgs.parsedSuccessfully )
			throw new IllegalArgumentException( "argument format mismatch" );

		final DataProviderType dataProviderType = DataProviderFactory.detectType( parsedArgs.inputChannelsPaths.iterator().next() );
		final DataProvider dataProvider = DataProviderFactory.create( dataProviderType );

		final String outputImagesPath = PathResolver.get( PathResolver.getParent( parsedArgs.inputChannelsPaths.iterator().next() ), "decon-tiles" );
		dataProvider.createFolder( outputImagesPath );

		final Map< Integer, Map< Integer, TileInfo > > channelDeconTilesMap;

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "DeconvolutionSpark" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
				.set( "spark.speculation", "true" ) // will restart tasks that run for too long (if decon hangs which may happen occasionally)
			) )
		{
			// initialize input tiles
			final List< Tuple2< TileInfo, Integer > > tilesAndChannelIndices = new ArrayList<>();
			for ( int ch = 0; ch < parsedArgs.inputChannelsPaths.size(); ++ch )
				for ( final TileInfo tile : dataProvider.loadTiles( parsedArgs.inputChannelsPaths.get( ch ) ) )
					tilesAndChannelIndices.add( new Tuple2<>( tile, ch ) );

			// initialize background value for each channel
			final List< Double > channelBackgroundValues = new ArrayList<>();
			for ( final String channelPath : parsedArgs.inputChannelsPaths )
				channelBackgroundValues.add( parsedArgs.backgroundValue != null ? parsedArgs.backgroundValue : FlatfieldCorrection.getPivotValue( dataProvider, channelPath ) );

			// initialize flatfields for each channel
			final List< RandomAccessiblePairNullable< U, U > > channelFlatfields = new ArrayList<>();
			for ( final String channelPath : parsedArgs.inputChannelsPaths )
				channelFlatfields.add( FlatfieldCorrection.loadCorrectionImages( dataProvider, channelPath, tilesAndChannelIndices.iterator().next()._1().numDimensions() ) );
			final Broadcast< List< RandomAccessiblePairNullable< U, U > > > broadcastedChannelFlatfields = sparkContext.broadcast( channelFlatfields );

			final ImageType inputImageType = tilesAndChannelIndices.iterator().next()._1().getType();
			if ( inputImageType.equals( ImageType.GRAY32 ) && !parsedArgs.exportAsFloat )
			{
				System.out.println( "Input data type is already float, no conversion is needed" );
				parsedArgs.exportAsFloat = true;
			}

			final List< Tuple2< TileInfo, Integer > > deconTilesAndChannelIndices = sparkContext.parallelize(
					tilesAndChannelIndices,
					Math.min( tilesAndChannelIndices.size(), MAX_PARTITIONS )
			).map( tileAndChannelIndex->
				{
					final TileInfo tile = tileAndChannelIndex._1();
					final int channelIndex = tileAndChannelIndex._2();
					final DataProvider localDataProvider = DataProviderFactory.create( dataProviderType );

					// load tile image
					final RandomAccessibleInterval< T > tileImg = TileLoader.loadTile( tile, localDataProvider );

					// load PSF image
					final ImagePlus psfImp = localDataProvider.loadImage( parsedArgs.psfPaths.get( channelIndex ) );
					Utils.workaroundImagePlusNSlices( psfImp );
					final RandomAccessibleInterval< T > psfImg = ImagePlusImgs.from( psfImp );

					// convert to float type for the deconvolution to work properly
					final RandomAccessibleInterval< FloatType > tileImgFloat = Converters.convert( tileImg, new RealConverter<>(), new FloatType() );
					final RandomAccessibleInterval< FloatType > psfImgFloat = Converters.convert( psfImg, new RealConverter<>(), new FloatType() );

					// apply flatfield correction
					final RandomAccessibleInterval< FloatType > sourceImgFloat;
					final RandomAccessiblePairNullable< U, U > flatfield = broadcastedChannelFlatfields.value().get( channelIndex );
					if ( flatfield != null )
					{
						final FlatfieldCorrectedRandomAccessible< FloatType, U > flatfieldCorrectedTileImg = new FlatfieldCorrectedRandomAccessible<>( tileImgFloat, flatfield.toRandomAccessiblePair() );
						final RandomAccessibleInterval< U > correctedImg = Views.interval( flatfieldCorrectedTileImg, tileImgFloat );
						sourceImgFloat = Converters.convert( correctedImg, new RealConverter<>(), new FloatType() );
					}
					else
					{
						sourceImgFloat = tileImgFloat;
					}

					// subtract background
					final double backgroundValue = channelBackgroundValues.get( channelIndex );
					final RandomAccessibleInterval< FloatType > sourceImgNoBackground = subtractBackground( sourceImgFloat, backgroundValue );
					final RandomAccessibleInterval< FloatType > psfImgNoBackground = subtractBackground( psfImgFloat, backgroundValue );

					// normalize the PSF
					double psfSum = 0;
					for ( final FloatType val : Views.iterable( psfImgNoBackground ) )
						psfSum += val.get();
					for ( final FloatType val : Views.iterable( psfImgNoBackground ) )
						val.set( ( float ) ( val.get() / psfSum ) );

					// run decon
					final RandomAccessibleInterval< FloatType > deconImg = OpServiceContainer.getInstance().ops().deconvolve().richardsonLucy(
							sourceImgNoBackground,
							psfImgNoBackground,
							parsedArgs.numIterations
						);

					// create output image data
					final ImagePlus deconImp = Utils.copyToImagePlus( deconImg );

					// save resulting decon tile
					final String deconTilePath = PathResolver.get( outputImagesPath,
							Utils.addFilenameSuffix(
									Utils.addFilenameSuffix( PathResolver.getFileName( tile.getFilePath() ), "-decon" ),
									FILENAME_SUFFIX_FLOAT
								)
						);
					localDataProvider.saveImage( deconImp, deconTilePath );

					// create metadata for resulting decon tile
					final TileInfo deconTile = tile.clone();
					deconTile.setFilePath( deconTilePath );
					deconTile.setType( ImageType.valueOf( deconImp.getType() ) );

					return new Tuple2<>( deconTile, channelIndex );
				}
			).collect();

			broadcastedChannelFlatfields.destroy();

			if ( !parsedArgs.exportAsFloat )
			{
				System.out.println( "Need to rescale data, collecting min/max values of the resulting decon stack for each channel..." );

				// collect stack min and max values of the resulting deconvolved collection of tiles for each channel
				final Map< Integer, Map< Integer, TileInfo > > deconChannelGroups = groupTilesIntoChannels( deconTilesAndChannelIndices );
				final Map< Integer, Tuple2< Double, Double > > channelGlobalMinMaxIntensityValues = new TreeMap<>();
				for ( final Entry< Integer, Map< Integer, TileInfo > > channelIndexAndDeconTiles : deconChannelGroups.entrySet() )
				{
					final Tuple2< Double, Double > globalDeconMinMaxValues = sparkContext.parallelize(
							new ArrayList<>( channelIndexAndDeconTiles.getValue().values() ),
							Math.min( channelIndexAndDeconTiles.getValue().size(), MAX_PARTITIONS )
					).map( deconTile ->
						{
							final DataProvider localDataProvider = DataProviderFactory.create( dataProviderType );

							// load 32-bit decon tile image
							final RandomAccessibleInterval< FloatType > deconTileImg = TileLoader.loadTile( deconTile, localDataProvider );

							// find min and max intensity values of the image
							double minValue = Double.POSITIVE_INFINITY, maxValue = Double.NEGATIVE_INFINITY;
							for ( final FloatType val : Views.iterable( deconTileImg ) )
							{
								minValue = Math.min( val.get(), minValue );
								maxValue = Math.max( val.get(), maxValue );
							}

							return new Tuple2<>( minValue, maxValue );
						}
					).treeReduce( ( a, b ) -> new Tuple2<>(
							Math.min( a._1(), b._1() ),
							Math.max( a._2(), b._2() )
						),
						Integer.MAX_VALUE  // max possible aggregation depth
					);

					channelGlobalMinMaxIntensityValues.put( channelIndexAndDeconTiles.getKey(), globalDeconMinMaxValues );
				}

				System.out.println( "Rescaling the intensity range of the resulting decon data..." );
				for ( final Entry< Integer, Tuple2< Double, Double > > entry : channelGlobalMinMaxIntensityValues.entrySet() )
				{
					System.out.println( String.format(
							"  Channel %d: [%.2f, %.2f] -> [%.2f, %.2f]",
							entry.getKey(),
							entry.getValue()._1(),
							entry.getValue()._2(),
							inputImageType.getType().getMinValue(),
							inputImageType.getType().getMaxValue()
						) );
				}

				final List< Tuple2< TileInfo, Integer > > rescaledDeconTilesAndChannelIndices = sparkContext.parallelize(
						deconTilesAndChannelIndices,
						Math.min( deconTilesAndChannelIndices.size(), MAX_PARTITIONS )
				).map( deconTileAndChannelIndex ->
					{
						final TileInfo deconTile = deconTileAndChannelIndex._1();
						final int channelIndex = deconTileAndChannelIndex._2();
						final DataProvider localDataProvider = DataProviderFactory.create( dataProviderType );

						// load 32-bit decon tile image
						final RandomAccessibleInterval< FloatType > deconTileImg = TileLoader.loadTile( deconTile, localDataProvider );

						// convert the image data to original data type mapping the intensities of the resulting stack into the value range of the target data type
						final Tuple2< Double, Double > globalDeconMinMaxValues = channelGlobalMinMaxIntensityValues.get( channelIndex );
						final ClampingConverter< FloatType, T > rescalingConverter = new ClampingConverter<>(
								globalDeconMinMaxValues._1(), globalDeconMinMaxValues._2(),
								inputImageType.getType().getMinValue(), inputImageType.getType().getMaxValue()
							);
						@SuppressWarnings( "unchecked" )
						final RandomAccessibleInterval< T > rescaledDeconTileImg = Converters.convert( deconTileImg, rescalingConverter, ( T ) inputImageType.getType() );
						final ImagePlus rescaledDeconImp = Utils.copyToImagePlus( rescaledDeconTileImg );

						// save the rescaled decon tile image
						final String rescaledDeconTilePath = Utils.addFilenameSuffix(
								Utils.removeFilenameSuffix( deconTile.getFilePath(), FILENAME_SUFFIX_FLOAT ),
								"-rescaled-intensity"
							);
						localDataProvider.saveImage( rescaledDeconImp, rescaledDeconTilePath );

						// delete intermediate 32-bit decon tile image
						localDataProvider.deleteFile( deconTile.getFilePath() );

						// create metadata for rescaled decon tile
						final TileInfo rescaledDeconTile = deconTile.clone();
						rescaledDeconTile.setFilePath( rescaledDeconTilePath );
						rescaledDeconTile.setType( ImageType.valueOf( rescaledDeconImp.getType() ) );

						return new Tuple2<>( rescaledDeconTile, channelIndex );
					}
				).collect();

				channelDeconTilesMap = groupTilesIntoChannels( rescaledDeconTilesAndChannelIndices );
			}
			else
			{
				channelDeconTilesMap = groupTilesIntoChannels( deconTilesAndChannelIndices );
			}
		}

		// save resulting decon tiles metadata
		for ( final Entry< Integer, Map< Integer, TileInfo > > channelDeconTiles : channelDeconTilesMap.entrySet() )
		{
			dataProvider.saveTiles(
					channelDeconTiles.getValue().values().toArray( new TileInfo[ 0 ] ),
					Utils.addFilenameSuffix( parsedArgs.inputChannelsPaths.get( channelDeconTiles.getKey() ), "-decon" )
				);
		}

		System.out.println( "Done" );
	}

	private static < T extends NativeType< T > & RealType< T > > RandomAccessibleInterval< T > subtractBackground(
			final RandomAccessibleInterval< T > img,
			final double backgroundValue )
	{
		final RandomAccessibleInterval< T > ret = new ArrayImgFactory<>( Util.getTypeFromInterval( img ) ).create( img );
		final Cursor< T > imgCursor = Views.flatIterable( img ).cursor();
		final Cursor< T > retCursor = Views.flatIterable( ret ).cursor();
		while ( imgCursor.hasNext() || retCursor.hasNext() )
			retCursor.next().setReal( Math.max( imgCursor.next().getRealDouble() - backgroundValue, 0 ) );
		return ret;
	}

	private static Map< Integer, Map< Integer, TileInfo > > groupTilesIntoChannels( final Collection< Tuple2< TileInfo, Integer > > tilesAndChannelIndices )
	{
		// group tiles into channels
		final Map< Integer, Map< Integer, TileInfo > > channelToTiles = new TreeMap<>();
		for ( final Tuple2< TileInfo, Integer > tileAndChannelIndex : tilesAndChannelIndices )
		{
			final TileInfo tile = tileAndChannelIndex._1();
			final int channelIndex = tileAndChannelIndex._2();
			if ( !channelToTiles.containsKey( channelIndex ) )
				channelToTiles.put( channelIndex, new TreeMap<>() );
			channelToTiles.get( channelIndex ).put( tile.getIndex(), tile );
		}
		return channelToTiles;
	}
}