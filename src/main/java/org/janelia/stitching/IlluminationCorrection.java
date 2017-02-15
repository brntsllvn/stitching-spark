package org.janelia.stitching;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.MapSerializer;

import ij.IJ;
import ij.ImagePlus;
import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.ConstantAffineModel1D;
import mpicbg.models.FixedScalingAffineModel1D;
import mpicbg.models.FixedTranslationAffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.Cursor;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.img.array.ArrayRandomAccess;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import scala.Tuple2;


public class IlluminationCorrection implements Serializable, AutoCloseable
{
	private static final long serialVersionUID = -8987192045944606043L;

	private static final double WINDOW_POINTS_PERCENT = 0.25;
	private static final double INTERPOLATION_LAMBDA = 0.1;

	private enum ModelType
	{
		AffineModel,
		FixedScalingAffineModel,
		FixedTranslationAffineModel
	}
	private enum RegularizerModelType
	{
		AffineModel,
		IdentityModel
	}

	private final String histogramsPath, solutionPath;

	private transient final JavaSparkContext sparkContext;
	private transient final TileInfo[] tiles;

	private final long[] fullTileSize;
	private final Interval workingInterval;

	private final int bins;
	private int histMinValue = Integer.MAX_VALUE, histMaxValue = Integer.MIN_VALUE;

	private Broadcast< int[] > broadcastedFullPixelToDownsampledPixel;
	private Broadcast< int[] > broadcastedDownsampledPixelToFullPixelsCount;

	private transient ArrayImg< DoubleType, DoubleArray > additiveComponent;


	public static void main( final String[] args ) throws Exception
	{
		final IlluminationCorrectionArguments illuminationCorrectionArgs = new IlluminationCorrectionArguments( args );
		if ( !illuminationCorrectionArgs.parsedSuccessfully() )
			System.exit( 1 );

		try ( final IlluminationCorrection driver = new IlluminationCorrection( illuminationCorrectionArgs ) )
		{
			driver.run();
		}
		System.out.println("Done");
	}


	public static <
		T extends NativeType< T > & RealType< T >,
		U extends NativeType< U > & RealType< U > >
	ImagePlusImg< FloatType, ? > applyCorrection(
			final RandomAccessibleInterval< T > src,
			final RandomAccessibleInterval< U > v,
			final RandomAccessibleInterval< U > z )
	{
		final Cursor< T > srcCursor = Views.iterable( src ).localizingCursor();
		final ImagePlusImg< FloatType, ? > dst = ImagePlusImgs.floats( Intervals.dimensionsAsLongArray( src ) );
		final RandomAccess< FloatType > dstRandomAccess = Views.translate( dst, Intervals.minAsLongArray( src ) ).randomAccess();
		final RandomAccess< U > vRandomAccess = v.randomAccess();
		final RandomAccess< U > zRandomAccess = z.randomAccess();
		while ( srcCursor.hasNext() )
		{
			srcCursor.fwd();
			dstRandomAccess.setPosition( srcCursor );
			vRandomAccess.setPosition( srcCursor );
			zRandomAccess.setPosition( srcCursor.getIntPosition(0), 0 ); zRandomAccess.setPosition( srcCursor.getIntPosition(1), 1 );
			dstRandomAccess.get().setReal( srcCursor.get().getRealDouble() * vRandomAccess.get().getRealDouble() + zRandomAccess.get().getRealDouble() );
		}
		return dst;
	}



	public IlluminationCorrection( final IlluminationCorrectionArguments args ) throws Exception
	{
		tiles = TileInfoJSONProvider.loadTilesConfiguration( args.inputFilePath() );
		fullTileSize = getMinSize( tiles );
		workingInterval = args.cropMinMaxInterval( fullTileSize );
		bins = args.bins();

		if ( args.histMinValue() != null ) histMinValue = args.histMinValue();
		if ( args.histMaxValue() != null ) histMaxValue = args.histMaxValue();

		System.out.println( "Working interval is at " + Arrays.toString( Intervals.minAsLongArray( workingInterval ) ) + " of size " + Arrays.toString( Intervals.dimensionsAsLongArray( workingInterval ) ) );

		final String basePath = args.inputFilePath().substring( 0, args.inputFilePath().lastIndexOf( "." ) );
		final String outputPath = basePath + "/" + ( args.cropMinMaxIntervalStr() == null ? "fullsize" : args.cropMinMaxIntervalStr() );
		histogramsPath = basePath + "/" + "histograms";
		solutionPath = outputPath + "/" + "solution";

		// check if all tiles have the same size
		for ( final TileInfo tile : tiles )
			for ( int d = 0; d < tile.numDimensions(); d++ )
				if ( tile.getSize(d) != fullTileSize[ d ] )
				{
					System.out.println("Assumption failed: not all the tiles are of the same size");
					System.exit(1);
				}

		sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "IlluminationCorrection3D" )
				//.set( "spark.driver.maxResultSize", "8g" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
				//.set( "spark.kryoserializer.buffer.max", "2047m" )
				.registerKryoClasses( new Class[] { Short.class, Integer.class, Long.class, Double.class, TreeMap.class, TreeMap[].class, long[].class, short[][].class, double[].class, List.class, Tuple2.class, Interval.class, FinalInterval.class, ArrayImg.class, DoubleType.class, DoubleArray.class } )
				.set( "spark.rdd.compress", "true" )
				//.set( "spark.executor.heartbeatInterval", "10000000" )
				//.set( "spark.network.timeout", "10000000" )
			);
	}

	@Override
	public void close()
	{
		if ( sparkContext != null )
			sparkContext.close();
	}


	public < A extends ArrayImg< DoubleType, DoubleArray >, V extends TreeMap< Short, Integer > >
	void run() throws Exception
	{
		/*final Map< Integer, Map< String, double[] > > solutions = new TreeMap<>();
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
		{
			final Map< String, double[] > s = loadSolution( slice );
			if ( s != null )
				solutions.put(slice, s);
		}
		if ( !solutions.isEmpty() )
		{
			System.out.println( "Successfully loaded a solution for "+solutions.size()+" slices" );
			for ( final Entry<Integer, Map<String, double[]>> entry : solutions.entrySet() )
			{
				System.out.println( "Correcting slice " + entry.getKey() );
				correctImages(entry.getKey(), entry.getValue().get("v"), entry.getValue().get("z"));
			}
			return;
		}*/

		if ( !allHistogramsReady() )
		{
			populateHistograms();
			System.out.println( "Computed all histograms" );
		}

		final int N = tiles.length;
		System.out.println( "Working with stack of size " + N );
		System.out.println( "Output directory: " + solutionPath );

		long elapsed = System.nanoTime();

		System.out.println( "Loading histograms.." );
		final JavaPairRDD< Long, long[] > rddFullHistograms = loadHistograms();

		final long[] referenceHistogram;
		// Don't save & read the reference histogram for now, because it should be recalculated in case the number of bins or min/max values have changed
//		if ( Files.exists( Paths.get( solutionPath + "/" + "referenceHistogram.ser" ) ) )
//		{
//			referenceHistogram = readReferenceHistogramFromDisk();
//			System.out.println( "Loaded reference histogram from disk" );
//		}
//		else
		{
			referenceHistogram = estimateReferenceHistogram( rddFullHistograms );
			System.out.println( "Obtained reference histogram of size " + referenceHistogram.length );
			System.out.println( "Reference histogram:");
			System.out.println( Arrays.toString( referenceHistogram ) );
//			saveReferenceHistogramToDisk( referenceHistogram );
		}

		// Define the transform and calculate the image size on each scale level
		final AffineTransform3D downsamplingTransform = new AffineTransform3D();
		downsamplingTransform.set(
				0.5, 0, 0, -0.5,
				0, 0.5, 0, -0.5,
				0, 0, 0.5, -0.5
			);

		final List< Integer > scales = new ArrayList<>();
		final TreeMap< Integer, long[] > scaleLevelToPixelSize = new TreeMap<>();
		final TreeMap< Integer, long[] > scaleLevelToOffset = new TreeMap<>();
		final TreeMap< Integer, long[] > scaleLevelToDimensions = new TreeMap<>();
		long smallestDimension;
		do
		{
			final int scale = scaleLevelToDimensions.size();
			final long[]
					pixelSize 		= new long[ workingInterval.numDimensions() ],
					offset 			= new long[ workingInterval.numDimensions() ],
					downsampledSize = new long[ workingInterval.numDimensions() ];
			for ( int d = 0; d < downsampledSize.length; d++ )
			{
				// how many original pixels form a single pixel on this scale level
				pixelSize[ d ] = ( long ) ( scale == 0 ? 1 : scaleLevelToPixelSize.get( scale - 1 )[ d ] / downsamplingTransform.get( d, d ) );

				// how many original pixels form the leftmost pixel on this scale level (which could be different from pixelSize[d] because of the translation component)
				offset[ d ] = ( long ) ( scale == 0 ? 0 : Math.min( pixelSize[ d ] * downsamplingTransform.get( d, 4 ) + pixelSize[ d ], workingInterval.dimension( d ) ) );

				// how many original pixels form the rightmost pixel on this scale level
				final long remaining = workingInterval.dimension( d ) - offset[ d ];

				// how many downsampled pixels are on this scale level
				downsampledSize[ d ] = ( offset[ d ] == 0 ? 0 : 1 ) + remaining / pixelSize[ d ] + ( remaining % pixelSize[ d ] == 0 ? 0 : 1 );
			}

			scales.add( scale );
			scaleLevelToPixelSize.put( scale, pixelSize );
			scaleLevelToOffset.put( scale, offset );
			scaleLevelToDimensions.put( scale, downsampledSize );

			smallestDimension = Long.MAX_VALUE;
			for ( int d = 0; d < workingInterval.numDimensions(); d++ )
				smallestDimension = Math.min( downsampledSize[ d ], smallestDimension );
		}
		while ( smallestDimension > 1 );

		System.out.println( "Scale level to dimensions:" );
		for ( final Entry< Integer, long[] > entry : scaleLevelToDimensions.entrySet() )
			System.out.println( String.format( " %d: %s", entry.getKey(), Arrays.toString( entry.getValue() ) ) );

		// Generate solutions iteratively from the smallest scale to the full size
		Collections.reverse( scales );


		final int iterations = 2;
		Pair< A, A > lastSolution = null;
		A downsampledSameComponent = null;
		for ( int iter = 0; iter < iterations; iter++ )
		{
			// For even iterations estimate only V (starting from it), for odd iterations estimate only Z
			final ModelType modelType = iter % 2 == 0 ? ModelType.FixedTranslationAffineModel : ModelType.FixedScalingAffineModel;

			final A fullScaleOtherComponent;
			if ( lastSolution != null )
				fullScaleOtherComponent = modelType == ModelType.FixedTranslationAffineModel ? lastSolution.getB() : lastSolution.getA();
			else
				fullScaleOtherComponent = null;

			for ( final int scale : scales )
			{
				final RegularizerModelType regularizerModelType = downsampledSameComponent == null ? RegularizerModelType.IdentityModel : RegularizerModelType.AffineModel;

				final JavaPairRDD< Long, long[] > rddDownsampledHistograms;
				final A sameScaleOtherComponent;

				if ( scale > 0 )
				{
					final Map< Long, Interval > downsampledPixelToFullPixels = createMappingFromDownsampledPixelToFullPixels(
							scaleLevelToPixelSize.get( scale ),
							scaleLevelToOffset.get( scale ),
							scaleLevelToDimensions.get( scale ) );

					broadcastMappingFromDownsampledPixelToFullPixels( downsampledPixelToFullPixels );

					rddDownsampledHistograms = downsampleHistograms( rddFullHistograms );

					if ( fullScaleOtherComponent != null )
						sameScaleOtherComponent = downsampleSolutionComponent( fullScaleOtherComponent, downsampledPixelToFullPixels, scaleLevelToDimensions.get( scale ) );
					else
						sameScaleOtherComponent = null;
				}
				else
				{
					rddDownsampledHistograms = rddFullHistograms;
					sameScaleOtherComponent = fullScaleOtherComponent;
				}

				downsampledSameComponent = leastSquaresInterpolationFit(
						rddDownsampledHistograms,
						scale,
						scaleLevelToDimensions.get( scale ),
						referenceHistogram,
						downsampledSameComponent,
						sameScaleOtherComponent,
						downsamplingTransform,
						modelType,
						regularizerModelType );

				destroyBroadcastedMappingFromDownsampledPixelToFullPixels();
			}

			lastSolution = new ValuePair<>(
					modelType == ModelType.FixedTranslationAffineModel ? downsampledSameComponent : fullScaleOtherComponent,
					modelType == ModelType.FixedScalingAffineModel ? downsampledSameComponent : fullScaleOtherComponent );

			saveSolution( iter, lastSolution );
		}

		elapsed = System.nanoTime() - elapsed;
		System.out.println( "----------" );
		System.out.println( String.format( "Took %f mins", elapsed / 1e9 / 60 ) );
	}

	private List< PointMatch > generateHistogramMatches(
			final long[] histogram,
			final long[] referenceHistogram,
			final long referenceHistogramMultiplier )
	{
		return generateHistogramMatches(
				histogram, referenceHistogram, referenceHistogramMultiplier,
				histMinValue, histMaxValue, bins );
	}
	public static List< PointMatch > generateHistogramMatches(
			final long[] histogram, final long[] referenceHistogram, final long referenceHistogramMultiplier,
			final int histMinValue, final int histMaxValue, final int bins )
	{
		if ( referenceHistogramMultiplier <= 0 )
			throw new IllegalArgumentException( "Incorrect value: referenceHistogramMultiplier="+referenceHistogramMultiplier );

		// number of elements should match in both histograms (accounting for the multiplier)
		long histogramElements = 0, referenceHistogramElements = 0;
		for ( int i = 0; i < bins; i++ )
		{
			histogramElements += histogram[ i ];
			referenceHistogramElements += referenceHistogram[ i ] * referenceHistogramMultiplier;
		}
		if ( histogramElements != referenceHistogramElements )
			throw new IllegalArgumentException( "Number of elements doesn't match: histogramElements="+histogramElements+", referenceHistogramElements="+referenceHistogramElements);

		final List< PointMatch > matches = new ArrayList<>();
		long histogramValue = 0, referenceHistogramValue = 0;
		for ( int histogramIndex = -1, referenceHistogramIndex = -1; ; )
		{
			while ( histogramValue == 0 && histogramIndex < bins - 1 ) histogramValue = histogram[ ++histogramIndex ];
			while ( referenceHistogramValue == 0 && referenceHistogramIndex < bins - 1 ) referenceHistogramValue = referenceHistogram[ ++referenceHistogramIndex ] * referenceHistogramMultiplier;

			// if at least one of them remains 0, it means that we have reached the boundary
			if ( histogramValue == 0 || referenceHistogramValue == 0 )
				break;

			final long weight = Math.min( histogramValue, referenceHistogramValue );

			// ignore the first and the last bin because they presumably contain undersaturated/oversaturated values
			if ( histogramIndex > 0 && histogramIndex < bins - 1 && referenceHistogramIndex > 0 && referenceHistogramIndex < bins - 1 )
				matches.add(
						new PointMatch(
								new Point( new double[] { getBinValue( histogramIndex, 			histMinValue, histMaxValue, bins ) } ),
								new Point( new double[] { getBinValue( referenceHistogramIndex, histMinValue, histMaxValue, bins ) } ),
								weight )
						);

			histogramValue -= weight;
			referenceHistogramValue -= weight;
		}
		return matches;
	}

	private <
		A extends ArrayImg< DoubleType, DoubleArray >,
		M extends Model< M > & Affine1D< M >,
		R extends Model< R > & Affine1D< R > & InvertibleBoundable >
	A leastSquaresInterpolationFitSecondPhase(
			final JavaPairRDD< Long, long[] > rddHistograms,
			final int scale,
			final long[] size,
			final long[] referenceHistogram,
			final A downsampledMultiplicativeComponent,
			final AffineTransform3D downsamplingTransform,
			final RegularizerModelType regularizerModelType ) throws Exception
	{
		final long[] downsampledSize = ( downsampledMultiplicativeComponent != null ? Intervals.dimensionsAsLongArray( downsampledMultiplicativeComponent ) : null );
		System.out.println( String.format("Working size: %s%s;  model: FixedTranslationAffineModel,  regularizer model: %s", Arrays.toString(size), (downsampledSize != null ? String.format(", downsampled size: %s", Arrays.toString(downsampledSize)) : ""), regularizerModelType) );

		final Broadcast< A > downsampledMultiplicativeComponentBroadcast = sparkContext.broadcast( downsampledMultiplicativeComponent );
		final Broadcast< AffineTransform3D > downsamplingTransformBroadcast = sparkContext.broadcast( downsamplingTransform );

		// prepare downsampled additive noise component
		final Broadcast< A > additiveComponentBroadcast = sparkContext.broadcast( ( A ) additiveComponent );
		final ImagePlus additiveNoiseComponentImp = ImageJFunctions.wrap( additiveComponent, "z-avgProj-"+scale );
		IJ.saveAsTiff( additiveNoiseComponentImp, solutionPath + "/" + additiveNoiseComponentImp.getTitle() + ".tif" );

		final JavaPairRDD< Long, Double > rddMultiplicativeComponentPixels = rddHistograms.mapToPair( tuple ->
			{
				final long[] histogram = tuple._2();
				final A downsampledMultiplicativeComponentLocal = downsampledMultiplicativeComponentBroadcast.value();
				final A additiveComponentLocal = additiveComponentBroadcast.value();

				final long[] position = new long[ size.length ];
				IntervalIndexer.indexToPosition( tuple._1(), size, position );

				// accumulated histograms have N*(number of aggregated full-scale pixels) items,
				// so we need to compensate for that by multuplying reference histogram values by the same amount
				final int referenceHistogramMultiplier = broadcastedDownsampledPixelToFullPixelsCount != null ? broadcastedDownsampledPixelToFullPixelsCount.value()[ tuple._1().intValue() ] : 1;
				final List< PointMatch > matches = generateHistogramMatches( histogram, referenceHistogram, referenceHistogramMultiplier );

				final Double multiplicativeRegularizerValue, additiveRegularizerValue;
				if ( downsampledMultiplicativeComponentLocal != null )
				{
//					final RandomAccessible< DoubleType > multiplicativeRegularizerImg = prepareRegularizerImage( downsampledMultiplicativeComponentLocal, scale );
					final RandomAccessible< DoubleType > multiplicativeRegularizerImg = prepareRegularizerImage( downsampledMultiplicativeComponentLocal, downsamplingTransformBroadcast.value() );
					final RandomAccess< DoubleType > multiplicativeRegularizerImgRandomAccess = multiplicativeRegularizerImg.randomAccess();
					multiplicativeRegularizerImgRandomAccess.setPosition( position );
					multiplicativeRegularizerValue = multiplicativeRegularizerImgRandomAccess.get().get();
				}
				else
				{
					multiplicativeRegularizerValue = null;
				}

				final RandomAccessible< DoubleType > additiveRegularizerImg = Views.translate( Views.stack( additiveComponentLocal ), new long[] { 0, 0, position[ 2 ] } );
				final RandomAccess< DoubleType > additiveRegularizerImgRandomAccess = additiveRegularizerImg.randomAccess();
				additiveRegularizerImgRandomAccess.setPosition( position );
				additiveRegularizerValue = additiveRegularizerImgRandomAccess.get().get();

				final M model = ( M ) new FixedTranslationAffineModel1D( additiveRegularizerValue );

				final R regularizerModel;
				switch ( regularizerModelType )
				{
				case IdentityModel:
					regularizerModel = ( R ) new IdentityModel();
					break;
				case AffineModel:
					final AffineModel1D downsampledModel = new AffineModel1D();
					downsampledModel.set( multiplicativeRegularizerValue, additiveRegularizerValue );
					regularizerModel = ( R ) downsampledModel;
					break;
				default:
					regularizerModel = null;
					break;
				}

				final InterpolatedAffineModel1D< M, ConstantAffineModel1D< R > > interpolatedModel = new InterpolatedAffineModel1D<>(
						model,
						new ConstantAffineModel1D<>( regularizerModel ),
						INTERPOLATION_LAMBDA );

				try
				{
					// NOTE: if you're running filter() and experiencing ClassCastException, take a look at: https://github.com/axtimwalde/mpicbg/pull/32
					interpolatedModel.fit( matches );
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}

				final double[] mCurr = new double[ 2 ];
				interpolatedModel.toArray( mCurr );

				return new Tuple2<>( tuple._1(), mCurr[ 0 ] );
			} );

		final List< Tuple2< Long, Double > > multiplicativeComponentPixels  = rddMultiplicativeComponentPixels.collect();

		downsampledMultiplicativeComponentBroadcast.destroy();
		additiveComponentBroadcast.destroy();

		final A multiplicativeComponent = ( A ) ArrayImgs.doubles( size );
		final ArrayRandomAccess< DoubleType > randomAccess = multiplicativeComponent.randomAccess();
		final long[] position = new long[ size.length ];
		for ( final Tuple2< Long, Double > tuple : multiplicativeComponentPixels )
		{
			IntervalIndexer.indexToPosition( tuple._1(), size, position );
			randomAccess.setPosition( position );
			randomAccess.get().set( tuple._2() );
		}

		System.out.println( "Got solution for scale=" + scale );
		return multiplicativeComponent;
	}




	private <
		A extends ArrayImg< DoubleType, DoubleArray >,
		M extends Model< M > & Affine1D< M >,
		R extends Model< R > & Affine1D< R > & InvertibleBoundable >
	A leastSquaresInterpolationFit(
			final JavaPairRDD< Long, long[] > rddHistograms,
			final int scale,
			final long[] size,
			final long[] referenceHistogram,
			final A downsampledSameComponent,
			final A sameScaleOtherComponent,
			final AffineTransform3D downsamplingTransform,
			final ModelType modelType,
			final RegularizerModelType regularizerModelType ) throws Exception
	{
		final long[] downsampledSize = ( downsampledSameComponent != null ? Intervals.dimensionsAsLongArray( downsampledSameComponent ) : null );
		System.out.println( String.format("Working size: %s%s;  model: %s,  regularizer model: %s", Arrays.toString(size), (downsampledSize != null ? String.format(", downsampled size: %s", Arrays.toString(downsampledSize)) : ""), modelType, regularizerModelType) );

		final Broadcast< A > downsampledSameComponentBroadcast = sparkContext.broadcast( downsampledSameComponent );
		final Broadcast< A > sameScaleOtherComponentBroadcast = sparkContext.broadcast( sameScaleOtherComponent );
		final Broadcast< AffineTransform3D > downsamplingTransformBroadcast = sparkContext.broadcast( downsamplingTransform );

		final JavaPairRDD< Long, Double > rddSolutionPixels = rddHistograms.mapToPair( tuple ->
			{
				final long[] histogram = tuple._2();

				final long[] position = new long[ size.length ];
				IntervalIndexer.indexToPosition( tuple._1(), size, position );

				// accumulated histograms have N*(number of aggregated full-scale pixels) items,
				// so we need to compensate for that by multuplying reference histogram values by the same amount
				final int referenceHistogramMultiplier = broadcastedDownsampledPixelToFullPixelsCount != null ? broadcastedDownsampledPixelToFullPixelsCount.value()[ tuple._1().intValue() ] : 1;
				final List< PointMatch > matches = generateHistogramMatches( histogram, referenceHistogram, referenceHistogramMultiplier );

				final Double downsampledSameComponentValue;
				if ( downsampledSameComponentBroadcast.value() != null )
				{
					final RandomAccessible< DoubleType > downsampledSameComponentImg = prepareRegularizerImage(
							downsampledSameComponentBroadcast.value(),
							downsamplingTransformBroadcast.value() );
					final RandomAccess< DoubleType > downsampledSameComponentImgRandomAccess = downsampledSameComponentImg.randomAccess();
					downsampledSameComponentImgRandomAccess.setPosition( position );
					downsampledSameComponentValue = downsampledSameComponentImgRandomAccess.get().get();
				}
				else
				{
					downsampledSameComponentValue = null;
				}

				final Double sameScaleOtherComponentValue;
				if ( sameScaleOtherComponentBroadcast.value() != null )
				{
					final RandomAccessible< DoubleType > sameScaleOtherComponentImg = sameScaleOtherComponentBroadcast.value();
					final RandomAccess< DoubleType > sameScaleOtherComponentImgRandomAccess = sameScaleOtherComponentImg.randomAccess();
					sameScaleOtherComponentImgRandomAccess.setPosition( position );
					sameScaleOtherComponentValue = sameScaleOtherComponentImgRandomAccess.get().get();
				}
				else
				{
					sameScaleOtherComponentValue = null;
				}

				final M model;
				switch ( modelType )
				{
				case FixedTranslationAffineModel:
					model = ( M ) new FixedTranslationAffineModel1D( sameScaleOtherComponentValue == null ? 0 : sameScaleOtherComponentValue );
					break;
				case FixedScalingAffineModel:
					model = ( M ) new FixedScalingAffineModel1D( sameScaleOtherComponentValue == null ? 1 : sameScaleOtherComponentValue );
					break;
				default:
					model = null;
					break;
				}

				final R regularizerModel;
				switch ( regularizerModelType )
				{
				case IdentityModel:
					regularizerModel = ( R ) new IdentityModel();
					break;
				case AffineModel:
					final Double multiplicativeComponentValue = modelType == ModelType.FixedTranslationAffineModel ? downsampledSameComponentValue : sameScaleOtherComponentValue;
					final Double additiveComponentValue = modelType == ModelType.FixedScalingAffineModel ? downsampledSameComponentValue : sameScaleOtherComponentValue;
					final AffineModel1D downsampledModel = new AffineModel1D();
					downsampledModel.set(
							multiplicativeComponentValue != null ? multiplicativeComponentValue : 1,
							additiveComponentValue != null ? additiveComponentValue : 0 );
					regularizerModel = ( R ) downsampledModel;
					break;
				default:
					regularizerModel = null;
					break;
				}

				final InterpolatedAffineModel1D< M, ConstantAffineModel1D< R > > interpolatedModel = new InterpolatedAffineModel1D<>(
						model,
						new ConstantAffineModel1D<>( regularizerModel ),
						INTERPOLATION_LAMBDA );

				try
				{
					// NOTE: if you're running filter() and experiencing ClassCastException, take a look at: https://github.com/axtimwalde/mpicbg/pull/32
					interpolatedModel.fit( matches );
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}

				final double[] mCurr = new double[ 2 ];
				interpolatedModel.toArray( mCurr );

				return new Tuple2<>(
						tuple._1(),
						modelType == ModelType.FixedTranslationAffineModel ? mCurr[ 0 ] : mCurr[ 1 ] );
			} );

		final List< Tuple2< Long, Double > > solutionPixels  = rddSolutionPixels.collect();

		downsampledSameComponentBroadcast.destroy();
		sameScaleOtherComponentBroadcast.destroy();
		downsamplingTransformBroadcast.destroy();

		final A solution = ( A ) ArrayImgs.doubles( size );
		final ArrayRandomAccess< DoubleType > solutionRandomAccess = solution.randomAccess();
		final long[] position = new long[ size.length ];
		for ( final Tuple2< Long, Double > tuple : solutionPixels )
		{
			IntervalIndexer.indexToPosition( tuple._1(), size, position );
			solutionRandomAccess.setPosition( position );
			solutionRandomAccess.get().set( tuple._2() );
		}

		System.out.println( "Got solution for scale=" + scale );
		return solution;
	}


	private < T extends RealType< T > & NativeType< T > > RandomAccessible< T > prepareRegularizerImage( final Img< T > downsampledImg, final AffineTransform3D downsamplingTransform )
	{
		final RealRandomAccessible< T > interpolatedDownsampledImage = Views.interpolate( Views.extendBorder( downsampledImg ), new NLinearInterpolatorFactory<>() );

		// Preapply the transform with a positive translation in order to align the downsampled image to the upsampled image (in its coordinate space)
		final double[] translation = downsamplingTransform.getTranslation();
		for ( int d = 0; d < translation.length; d++ )
			translation[ d ] = Math.abs( translation[ d ] );
		final AffineTransform3D translationTransform = new AffineTransform3D();
		translationTransform.setTranslation( translation );

		final AffineRandomAccessible< T, AffineGet > alignedDownsampledImg = RealViews.affine( interpolatedDownsampledImage, translationTransform );

		// Then, apply the inverse of the downsampling transform in order to map the downsampled image to the upsampled image
		return RealViews.affine( alignedDownsampledImg, downsamplingTransform.inverse() );
	}


	private < V extends TreeMap< Short, Integer > > JavaPairRDD< Long, long[] > loadHistograms() throws Exception
	{
		final List< Integer > slices = new ArrayList<>();
		for ( int slice = ( int ) workingInterval.min( 2 ) + 1; slice <= ( int ) workingInterval.max( 2 ) + 1; slice++ )
			slices.add( slice );

		final JavaRDD< Integer > rddSlices = sparkContext.parallelize( slices );

		final JavaPairRDD< Long, V > rddTreeMaps = rddSlices
				.flatMapToPair( slice ->
					{
						final RandomAccessibleInterval< V > sliceHistograms = readSliceHistogramsFromDisk( slice );
						final Interval sliceInterval = Intervals.createMinMax( workingInterval.min( 0 ), workingInterval.min( 1 ), workingInterval.max( 0 ), workingInterval.max( 1 ) );
						final IntervalView< V > sliceHistogramsInterval = Views.offsetInterval( sliceHistograms, sliceInterval );
						final ListImg< Tuple2< Long, V > > ret = new ListImg<>( Intervals.dimensionsAsLongArray( sliceHistogramsInterval ), null );

						final Cursor< V > srcCursor = Views.iterable( sliceHistogramsInterval ).localizingCursor();
						final ListRandomAccess< Tuple2< Long, V > > dstRandomAccess = ret.randomAccess();

						final long[] workingDimensions = Intervals.dimensionsAsLongArray( workingInterval );

						while ( srcCursor.hasNext() )
						{
							srcCursor.fwd();
							dstRandomAccess.setPosition( srcCursor );
							dstRandomAccess.set( new Tuple2<>(
									IntervalIndexer.positionToIndex(
											new long[] { srcCursor.getLongPosition( 0 ), srcCursor.getLongPosition( 1 ), slice - 1 - workingInterval.min( 2 ) },
											workingDimensions ),
									srcCursor.get() ) );
						}
						return ret.iterator();
					} );

		if ( histMinValue == Integer.MAX_VALUE || histMaxValue == Integer.MIN_VALUE )
		{
			// extract min/max value from the histograms
			rddTreeMaps.persist( StorageLevel.MEMORY_ONLY_SER() );

			final Tuple2< Integer, Integer > histMinMax = rddTreeMaps
					.map( tuple -> tuple._2() )
					.treeAggregate(
						new Tuple2<>( Integer.MAX_VALUE, Integer.MIN_VALUE ),
						( ret, map ) ->
							{
								int min = ret._1(), max = ret._2();
								for ( final Short key : map.keySet() )
								{
									final int unsignedKey = shortToUnsigned( key );
									min = Math.min( unsignedKey, min );
									max = Math.max( unsignedKey, max );
								}
								return new Tuple2<>( min, max );
							},
						( ret, val ) -> new Tuple2<>( Math.min( ret._1(), val._1() ), Math.max( ret._2(), val._2() ) ),
						getAggregationTreeDepth() );

			histMinValue = histMinMax._1();
			histMaxValue = histMinMax._2();
		}

		System.out.println( "Histograms min=" + histMinValue + ", max=" + histMaxValue );

		final JavaPairRDD< Long, long[] > rddHistograms = rddTreeMaps
				.mapValues( histogram ->
					{
						final long[] array = new long[ bins ];
						for ( final Entry< Short, Integer > entry : histogram.entrySet() )
							array[ getBinIndex( shortToUnsigned( entry.getKey() ) ) ] += entry.getValue();
						return array;
					} );

		// enforce the computation so we can unpersist the parent RDD after that
		System.out.println( "Total histograms (pixels) count = " + rddHistograms.persist( StorageLevel.MEMORY_ONLY_SER() ).count() );

		rddTreeMaps.unpersist();
		return rddHistograms;
	}



	private static int shortToUnsigned( final short value )
	{
		return value + ( value >= 0 ? 0 : ( ( 1 << Short.SIZE ) ) );
	}

	public static double getBinWidth( final int histMinValue, final int histMaxValue, final int bins )
	{
		return ( histMaxValue - histMinValue + 1 ) / ( double ) bins;
	}
	private int getBinIndex( final int value )
	{
		return getBinIndex( value, histMinValue, histMaxValue, bins );
	}
	public static int getBinIndex( final int value, final int histMinValue, final int histMaxValue, final int bins )
	{
		if ( value < histMinValue )
			return 0;
		else if ( value > histMaxValue )
			return bins - 1;

		return ( int ) ( ( value - histMinValue ) / getBinWidth( histMinValue, histMaxValue, bins ) );
	}

	private int getBinValue( final int index )
	{
		return getBinValue( index, histMinValue, histMaxValue, bins );
	}
	public static int getBinValue( final int index, final int histMinValue, final int histMaxValue, final int bins )
	{
		return ( int ) ( histMinValue + ( index + 0.5 ) * getBinWidth( histMinValue, histMaxValue, bins ) );
	}


	private Map< Long, Interval > createMappingFromDownsampledPixelToFullPixels(
			final long[] pixelSize,
			final long[] offset,
			final long[] downsampledSize )
	{
		final int n = workingInterval.numDimensions();
		final long numFullPixels = Intervals.numElements( workingInterval );
		final long numDownsampledPixels = Intervals.numElements( new FinalDimensions( downsampledSize ) );

		final Map< Long, Interval > downsampledPixelToFullPixels = new HashMap<>();

		final int[] downsampledPosition = new int[ downsampledSize.length ];
		for ( long downsampledPixel = 0; downsampledPixel < numDownsampledPixels; downsampledPixel++ )
		{
			IntervalIndexer.indexToPosition( downsampledPixel, downsampledSize, downsampledPosition );
			final long[] mins = new long[ workingInterval.numDimensions() ], maxs = new long[ workingInterval.numDimensions() ];
			for ( int d = 0; d < mins.length; d++ )
			{
				if ( downsampledPosition[ d ] == 0 )
				{
					mins[ d ] = 0;
					maxs[ d ] = offset[ d ] - 1;
				}
				else
				{
					mins[ d ] = offset[ d ] + pixelSize[ d ] * ( downsampledPosition[ d ] - 1 );
					maxs[ d ] = Math.min( mins[ d ] + pixelSize[ d ], workingInterval.dimension( d ) ) - 1;
				}
			}
			final Interval srcInterval = new FinalInterval( mins, maxs );

			downsampledPixelToFullPixels.put( downsampledPixel, srcInterval );
		}

		return downsampledPixelToFullPixels;
	}

	private void broadcastMappingFromDownsampledPixelToFullPixels( final Map< Long, Interval > downsampledPixelToFullPixels )
	{
		final int[] fullPixelToDownsampledPixel = new int[ ( int ) Intervals.numElements( workingInterval ) ];
		final int[] downsampledPixelToFullPixelsCount = new int[ downsampledPixelToFullPixels.size() ];

		final long[] dimensions = Intervals.dimensionsAsLongArray( workingInterval );
		for ( final Entry< Long, Interval > entry : downsampledPixelToFullPixels.entrySet() )
		{
			final long downsampledPixel = entry.getKey();
			final long[] mins = Intervals.minAsLongArray( entry.getValue() );
			final long[] maxs = Intervals.maxAsLongArray( entry.getValue() );

			final long[] position = mins.clone();
			final int n = entry.getValue().numDimensions();
			for ( int d = 0; d < n; )
			{
				fullPixelToDownsampledPixel[ ( int ) IntervalIndexer.positionToIndex( position, dimensions ) ] = ( int ) downsampledPixel;

				for ( d = 0; d < n; ++d )
				{
					position[ d ]++;
					if ( position[ d ] <= maxs[ d ] )
						break;
					else
						position[ d ] = mins[ d ];
				}
			}

			downsampledPixelToFullPixelsCount[ ( int ) downsampledPixel ] = ( int ) Intervals.numElements( entry.getValue() );
		}

		broadcastedFullPixelToDownsampledPixel = sparkContext.broadcast( fullPixelToDownsampledPixel );
		broadcastedDownsampledPixelToFullPixelsCount = sparkContext.broadcast( downsampledPixelToFullPixelsCount );
	}
	private void destroyBroadcastedMappingFromDownsampledPixelToFullPixels()
	{
		if ( broadcastedFullPixelToDownsampledPixel != null )
		{
			broadcastedFullPixelToDownsampledPixel.destroy();
			broadcastedFullPixelToDownsampledPixel = null;
		}
		if ( broadcastedDownsampledPixelToFullPixelsCount != null )
		{
			broadcastedDownsampledPixelToFullPixelsCount.destroy();
			broadcastedDownsampledPixelToFullPixelsCount = null;
		}
	}

	private JavaPairRDD< Long, long[] > downsampleHistograms( final JavaPairRDD< Long, long[] > rddFullHistograms )
	{
		return rddFullHistograms
				.mapToPair( tuple -> new Tuple2<>( ( long ) broadcastedFullPixelToDownsampledPixel.value()[ tuple._1().intValue() ], tuple._2() ) )
				.reduceByKey(
						( ret, other ) ->
						{
							for ( int i = 0; i < ret.length; i++ )
								ret[ i ] += other[ i ];
							return ret;
						} );
	}


	private < A extends ArrayImg< DoubleType, DoubleArray > > A downsampleSolutionComponent(
			final A fullComponent,
			final Map< Long, Interval > downsampledPixelToFullPixels,
			final long[] downsampledSize )
	{
		final long numFullPixels = Intervals.numElements( workingInterval );
		final long numDownsampledPixels = Intervals.numElements( new FinalDimensions( downsampledSize ) );

		final A downsampledComponent = ( A ) ArrayImgs.doubles( downsampledSize );
		final ArrayLocalizingCursor< DoubleType > downsampledComponentCursor = downsampledComponent.localizingCursor();
		final long[] downsampledPosition = new long[ downsampledComponent.numDimensions() ];
		while ( downsampledComponentCursor.hasNext() )
		{
			double fullPixelsSum = 0;
			final DoubleType downsampledVal = downsampledComponentCursor.next();

			downsampledComponentCursor.localize( downsampledPosition );
			final long downsampledPixel = IntervalIndexer.positionToIndex( downsampledPosition, downsampledSize );

			final IntervalView< DoubleType > fullComponentInterval = Views.interval( fullComponent, downsampledPixelToFullPixels.get( downsampledPixel ) );
			final Cursor< DoubleType > fullComponentIntervalCursor = Views.iterable( fullComponentInterval ).cursor();
			while ( fullComponentIntervalCursor.hasNext() )
				fullPixelsSum += fullComponentIntervalCursor.next().get();

			downsampledVal.set( fullPixelsSum / fullComponentInterval.size() );
		}

		return downsampledComponent;
	}

	private < A extends ArrayImg< DoubleType, DoubleArray > > void saveSolution( final int iteration, final Pair< A, A > solution )
	{
		if ( solution.getA() != null )
			saveSolution( iteration, solution.getA(), "v" );

		if ( solution.getB() != null )
			saveSolution( iteration, solution.getB(), "z" );
	}


	private < A extends ArrayImg< DoubleType, DoubleArray > > void saveSolution( final int iteration, final A solution, final String title )
	{
		final String path = solutionPath + "/iter" + iteration + "/" + title + ".tif";

		Paths.get( path ).getParent().toFile().mkdirs();

		final ImagePlus imp = ImageJFunctions.wrap( solution, title );
		Utils.workaroundImagePlusNSlices( imp );
		IJ.saveAsTiff( imp, path );
	}


	private long[] estimateReferenceHistogram( final JavaPairRDD< Long, long[] > rddHistograms ) throws Exception
	{
		final long numPixels = Intervals.numElements( workingInterval );
		final int N = tiles.length;

		int numWindowPoints = ( int ) Math.round( numPixels * WINDOW_POINTS_PERCENT );
		final int mStart = ( int ) ( Math.round( numPixels / 2.0 ) - Math.round( numWindowPoints / 2.0 ) ) - 1;
		final int mEnd   = ( int ) ( Math.round( numPixels / 2.0 ) + Math.round( numWindowPoints / 2.0 ) );
		numWindowPoints = mEnd - mStart;

		System.out.println("Estimating Q using mStart="+mStart+", mEnd="+mEnd+" (points="+numWindowPoints+")");

		final long[] referenceVector = rddHistograms
			.mapValues( histogram ->
				{
					double pixelMean = 0;
					int count = 0;
					for ( int i = 0; i < histogram.length; i++ )
					{
						pixelMean += histogram[ i ] * getBinValue( i );
						count += histogram[ i ];
					}
					pixelMean /= count;
					return pixelMean;
				}
			)
			.mapToPair( pair -> pair.swap() )
			.sortByKey()
			.zipWithIndex()
			.filter( tuple -> tuple._2() >= mStart && tuple._2() < mEnd )
			.mapToPair( tuple -> tuple._1().swap() )
			.join( rddHistograms )
			.map( item -> item._2()._2() )
			.treeAggregate(
				new long[ N ],
				( ret, histogram ) ->
				{
					int counter = 0;
					for ( int i = 0; i < histogram.length; i++ )
						for ( int j = 0; j < histogram[ i ]; j++ )
							ret[ counter++ ] += getBinValue( i );
					return ret;
				},
				( ret, other ) ->
				{
					for ( int i = 0; i < N; i++ )
						ret[ i ] += other[ i ];
					return ret;
				},
				getAggregationTreeDepth()
			);

		final long[] referenceHistogram = new long[ bins ];
		for ( final long val : referenceVector )
			referenceHistogram[ getBinIndex( ( short ) Math.round( ( double ) val / numWindowPoints ) ) ]++;

		return referenceHistogram;
	}

	// Arrays, slow??
	/*private < T extends NativeType< T > & RealType< T > > void populateHistograms() throws Exception
	{
		final JavaRDD< TileInfo > rddTiles = sparkContext.parallelize( Arrays.asList( tiles ) );

		// Check for existing histograms
		final Set< Integer > remainingSlices = new HashSet<>();
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
			if ( !Files.exists( Paths.get( generateSliceHistogramsPath( 0, slice ) ) ) )
				remainingSlices.add( slice );

		// FIXME: hardcoded for the second channel of the Sample1_C1 dataset
		histMinValue = 0;
		histMaxValue = 10000;

		final int slicePixelsCount = ( int ) ( workingInterval.dimension( 0 ) * workingInterval.dimension( 1 ) );

		for ( final int currentSlice : remainingSlices )
		{
			System.out.println( "  Processing slice " + currentSlice );

			final short[][] histograms = rddTiles.treeAggregate(
				new short[ slicePixelsCount ][ bins ],	// zero value

				// generator
				( intermediateHist, tile ) ->
				{
					final ImagePlus imp = TiffSliceLoader.loadSlice( tile, currentSlice );
					Utils.workaroundImagePlusNSlices( imp );

					final Img< T > img = ImagePlusImgs.from( imp );
					final Cursor< T > cursor = Views.iterable( img ).localizingCursor();
					final int[] dimensions = Intervals.dimensionsAsIntArray( img );
					final int[] position = new int[ dimensions.length ];

					while ( cursor.hasNext() )
					{
						cursor.fwd();
						cursor.localize( position );
						final int pixel = IntervalIndexer.positionToIndex( position, dimensions );
						final short val = ( short ) cursor.get().getRealDouble();
						intermediateHist[ pixel ][ getBinIndex( val ) ]++;
					}

					imp.close();
					return intermediateHist;
				},

				// reducer
				( a, b ) ->
				{
					for ( int pixel = 0; pixel < a.length; pixel++ )
						for ( int i = 0; i < bins; i++ )
						a[ pixel ][ i ] += b[ pixel ][ i ];
					return a;
				},

				getAggregationTreeDepth() );

			System.out.println( "Obtained result for slice " + currentSlice + ", saving..");

			saveSliceArrayHistogramsToDisk( 0, currentSlice, histograms );
		}
	}*/

	// TreeMap
	private <
		T extends NativeType< T > & RealType< T >,
		V extends TreeMap< Short, Integer > >
	void populateHistograms() throws Exception
	{
		final JavaRDD< TileInfo > rddTiles = sparkContext.parallelize( Arrays.asList( tiles ) );

		// Check for existing histograms
		final Set< Integer > remainingSlices = new HashSet<>();
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
			if ( !Files.exists( Paths.get( generateSliceHistogramsPath( 0, slice ) ) ) )
				remainingSlices.add( slice );

		for ( final int currentSlice : remainingSlices )
		{
			System.out.println( "  Processing slice " + currentSlice );

			final V[] histograms = rddTiles.treeAggregate(
				null, // zero value

				// generator
				( intermediateHist, tile ) ->
				{
					final ImagePlus imp = TiffSliceLoader.loadSlice( tile, currentSlice );
					Utils.workaroundImagePlusNSlices( imp );

					final Img< T > img = ImagePlusImgs.from( imp );
					final Cursor< T > cursor = Views.iterable( img ).localizingCursor();
					final int[] dimensions = Intervals.dimensionsAsIntArray( img );
					final int[] position = new int[ dimensions.length ];

					final V[] ret;
					if ( intermediateHist != null )
					{
						ret = intermediateHist;
					}
					else
					{
						ret = ( V[] ) new TreeMap[ (int) img.size() ];
						for ( int i = 0; i < ret.length; i++ )
							ret[ i ] = ( V ) new TreeMap< Short, Integer >();
					}

					while ( cursor.hasNext() )
					{
						cursor.fwd();
						cursor.localize( position );
						final int pixel = IntervalIndexer.positionToIndex( position, dimensions );
						final short key = ( short ) cursor.get().getRealDouble();
						ret[ pixel ].put( key, ret[ pixel ].getOrDefault( key, 0 ) + 1 );
					}

					imp.close();
					return ret;
				},

				// reducer
				( a, b ) ->
				{
					if ( a == null )
						return b;
					else if ( b == null )
						return a;

					for ( int pixel = 0; pixel < b.length; pixel++ )
						for ( final Entry< Short, Integer > entry : b[ pixel ].entrySet() )
							a[ pixel ].put( entry.getKey(), a[ pixel ].getOrDefault( entry.getKey(), 0 ) + entry.getValue() );
					return a;
				},

				getAggregationTreeDepth() );

			System.out.println( "Obtained result for slice " + currentSlice + ", saving..");

			saveSliceHistogramsToDisk( 0, currentSlice, histograms );
		}
	}




	private < T extends NativeType< T > & RealType< T > > short[][] getHistogramsArrays( final int currentSlice ) throws Exception
	{
		final JavaRDD< TileInfo > rddTiles = sparkContext.parallelize( Arrays.asList( tiles ) );

		final int slicePixelsCount = ( int ) ( workingInterval.dimension( 0 ) * workingInterval.dimension( 1 ) );

		final short[][] histograms = rddTiles.treeAggregate(
			new short[ slicePixelsCount ][ bins ],	// zero value

			// generator
			( intermediateHist, tile ) ->
			{
				final ImagePlus imp = TiffSliceLoader.loadSlice( tile, currentSlice );
				Utils.workaroundImagePlusNSlices( imp );

				final Img< T > img = ImagePlusImgs.from( imp );
				final Cursor< T > cursor = Views.iterable( img ).localizingCursor();
				final int[] dimensions = Intervals.dimensionsAsIntArray( img );
				final int[] position = new int[ dimensions.length ];

				while ( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.localize( position );
					final int pixel = IntervalIndexer.positionToIndex( position, dimensions );
					final short val = ( short ) cursor.get().getRealDouble();
					intermediateHist[ pixel ][ getBinIndex( shortToUnsigned( val ) ) ]++;
				}

				imp.close();
				return intermediateHist;
			},

			// reducer
			( a, b ) ->
			{
				for ( int pixel = 0; pixel < a.length; pixel++ )
					for ( int i = 0; i < bins; i++ )
					a[ pixel ][ i ] += b[ pixel ][ i ];
				return a;
			},

			getAggregationTreeDepth() );

		return histograms;
	}
	private <
		T extends NativeType< T > & RealType< T >,
		V extends TreeMap< Short, Integer > >
	V[] getHistogramsTreeMaps(final int currentSlice) throws Exception
	{
		final JavaRDD< TileInfo > rddTiles = sparkContext.parallelize( Arrays.asList( tiles ) );

		final V[] histograms = rddTiles.treeAggregate(
			null, // zero value

			// generator
			( intermediateHist, tile ) ->
			{
				final ImagePlus imp = TiffSliceLoader.loadSlice( tile, currentSlice );
				Utils.workaroundImagePlusNSlices( imp );

				final Img< T > img = ImagePlusImgs.from( imp );
				final Cursor< T > cursor = Views.iterable( img ).localizingCursor();
				final int[] dimensions = Intervals.dimensionsAsIntArray( img );
				final int[] position = new int[ dimensions.length ];

				final V[] ret;
				if ( intermediateHist != null )
				{
					ret = intermediateHist;
				}
				else
				{
					ret = ( V[] ) new TreeMap[ (int) img.size() ];
					for ( int i = 0; i < ret.length; i++ )
						ret[ i ] = ( V ) new TreeMap< Short, Integer >();
				}

				while ( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.localize( position );
					final int pixel = IntervalIndexer.positionToIndex( position, dimensions );
					final short key = ( short ) cursor.get().getRealDouble();
					ret[ pixel ].put( key, ret[ pixel ].getOrDefault( key, 0 ) + 1 );
				}

				imp.close();
				return ret;
			},

			// reducer
			( a, b ) ->
			{
				if ( a == null )
					return b;
				else if ( b == null )
					return a;

				for ( int pixel = 0; pixel < b.length; pixel++ )
					for ( final Entry< Short, Integer > entry : b[ pixel ].entrySet() )
						a[ pixel ].put( entry.getKey(), a[ pixel ].getOrDefault( entry.getKey(), 0 ) + entry.getValue() );
				return a;
			},

			getAggregationTreeDepth() );

		return histograms;
	}

	private Map< String, double[] > loadSolution( final int slice )
	{
		final Map< String, double[] > imagesFlattened = new HashMap<>();
		imagesFlattened.put( "v", null );
		imagesFlattened.put( "z", null );

		for ( final Entry< String, double[] > entry : imagesFlattened.entrySet() )
		{
			final String path = solutionPath + "/" + entry.getKey() + "/" + slice + ".tif";
			if ( !Files.exists( Paths.get( path ) ) )
				return null;

			final ImagePlus imp = IJ.openImage( path );
			Utils.workaroundImagePlusNSlices( imp );

			final Img< ? extends RealType > img = ImagePlusImgs.from( imp );
			final Cursor< ? extends RealType > imgCursor = Views.flatIterable( img ).cursor();

			final ArrayImg< DoubleType, DoubleArray > arrayImg = ArrayImgs.doubles( Intervals.dimensionsAsLongArray( img ) );
			final Cursor< DoubleType > arrayImgCursor = Views.flatIterable( arrayImg ).cursor();

			while ( arrayImgCursor.hasNext() || imgCursor.hasNext() )
				arrayImgCursor.next().setReal( imgCursor.next().getRealDouble() );

			imp.close();
			entry.setValue( arrayImg.update( null ).getCurrentStorageArray() );
		}

		return imagesFlattened;
	}

	private void correctImages( final int slice, final double[] v, final double[] z )
	{
		final String outPath = solutionPath + "/corrected/" + slice;
		Paths.get( outPath ).toFile().mkdirs();

		// Prepare broadcast variables for V and Z
		final Broadcast< double[] > vBroadcasted = sparkContext.broadcast( v ), zBroadcasted = sparkContext.broadcast( z );

		//final double v_mean = mean( vFinal );
		//final double z_mean = mean( zFinal );

		final JavaRDD< TileInfo > rdd = sparkContext.parallelize( Arrays.asList( tiles ) );
		rdd.foreach( tile ->
				{
					final ImagePlus imp = TiffSliceLoader.loadSlice(tile, slice);
					Utils.workaroundImagePlusNSlices( imp );
					final Img< ? extends RealType > img = ImagePlusImgs.from( imp );
					final Cursor< ? extends RealType > imgCursor = Views.flatIterable( img ).cursor();

					final ArrayImg< DoubleType, DoubleArray > vImg = ArrayImgs.doubles( vBroadcasted.value(), Intervals.dimensionsAsLongArray( img ) );
					final ArrayImg< DoubleType, DoubleArray > zImg = ArrayImgs.doubles( zBroadcasted.value(), Intervals.dimensionsAsLongArray( img ) );
					final Cursor< DoubleType > vCursor = Views.flatIterable( vImg ).cursor();
					final Cursor< DoubleType > zCursor = Views.flatIterable( zImg ).cursor();

					final ArrayImg< DoubleType, DoubleArray > correctedImg = ArrayImgs.doubles( Intervals.dimensionsAsLongArray( img ) );
					final Cursor< DoubleType > correctedImgCursor = Views.flatIterable( correctedImg ).cursor();

					while ( correctedImgCursor.hasNext() || imgCursor.hasNext() )
						correctedImgCursor.next().setReal( (imgCursor.next().getRealDouble() - zCursor.next().get()) / vCursor.next().get() );   // * v_mean + z_mean


//						final ArrayImg< UnsignedShortType, ShortArray > correctedImgShort = ArrayImgs.unsignedShorts( originalSize );
//						final Cursor< UnsignedShortType > correctedImgShortCursor = Views.flatIterable( correctedImgShort ).cursor();
//						correctedImgCursor.reset();
//						while ( correctedImgShortCursor.hasNext() || correctedImgCursor.hasNext() )
//							correctedImgShortCursor.next().setReal( correctedImgCursor.next().get() );

					final ImagePlus correctedImp = ImageJFunctions.wrap( correctedImg, "" );
					Utils.workaroundImagePlusNSlices( correctedImp );
					IJ.saveAsTiff( correctedImp, outPath + "/"+ Utils.addFilenameSuffix( Paths.get(tile.getFilePath()).getFileName().toString(), "_corrected" ) );

					imp.close();
					correctedImp.close();
				}
			);
	}


	private void saveSliceArrayHistogramsToDisk( final int scale, final int slice, final short[][] hist ) throws Exception
	{
		final String path = generateSliceHistogramsPath( scale, slice );

		Paths.get( path ).getParent().toFile().mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

		final Kryo kryo = new Kryo();
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, hist );
		}
	}

	private < V extends TreeMap< Short, Integer > >void saveSliceHistogramsToDisk( final int scale, final int slice, final V[] hist ) throws Exception
	{
		final String path = generateSliceHistogramsPath( scale, slice );

		Paths.get( path ).getParent().toFile().mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

//		final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		final MapSerializer serializer = new MapSerializer();
		serializer.setKeysCanBeNull( false );
		serializer.setKeyClass( Short.class, kryo.getSerializer( Short.class ) );
		serializer.setValueClass( Integer.class, kryo.getSerializer( Integer.class) );
		kryo.register( TreeMap.class, serializer );

		//try ( final Output output = kryoSerializer.newKryoOutput() )
		//{
		//	output.setOutputStream( os );
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, hist );
		}
	}

	private < V extends TreeMap< Short, Integer > > ListImg< V > readSliceHistogramsFromDisk( final int slice )
	{
		return new ListImg<>( Arrays.asList( readSliceHistogramsArrayFromDisk( 0, slice ) ), new long[] { fullTileSize[ 0 ], fullTileSize[ 1 ] } );
	}
	private < V extends TreeMap< Short, Integer > > V[] readSliceHistogramsArrayFromDisk( final int scale, final int slice )
	{
		System.out.println( "Loading slice " + slice );
		final String path = generateSliceHistogramsPath( scale, slice );

		if ( !Files.exists(Paths.get(path)) )
			return null;

		try
		{
			final InputStream is = new DataInputStream(
					new BufferedInputStream(
							new FileInputStream( path )
							)
					);

//			final Kryo kryo = kryoSerializer.newKryo();
			final Kryo kryo = new Kryo();

			final MapSerializer serializer = new MapSerializer();
			serializer.setKeysCanBeNull( false );
			serializer.setKeyClass( Short.class, kryo.getSerializer( Short.class ) );
			serializer.setValueClass( Integer.class, kryo.getSerializer( Integer.class) );
			kryo.register( TreeMap.class, serializer );

			try ( final Input input = new Input( is ) )
			{
				return ( V[] ) kryo.readClassAndObject( input );
			}
		}
		catch ( final IOException e )
		{
			e.printStackTrace();
			return null;
		}
	}


	private void saveReferenceHistogramToDisk( final long[] referenceHistogram ) throws Exception
	{
		final String path = solutionPath + "/" + "referenceHistogram.ser";

		Paths.get( path ).getParent().toFile().mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

//		final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();

		//try ( final Output output = kryoSerializer.newKryoOutput() )
		//{
		//	output.setOutputStream( os );
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, referenceHistogram );
		}
	}

	private long[] readReferenceHistogramFromDisk() throws Exception
	{
		final String path = solutionPath + "/" + "referenceHistogram.ser";

		if ( !Files.exists(Paths.get(path)) )
			return null;

		final InputStream is = new DataInputStream(
				new BufferedInputStream(
						new FileInputStream( path )
						)
				);

//		final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();

		try ( final Input input = new Input( is ) )
		{
			return ( long[] ) kryo.readClassAndObject( input );
		}
	}


	private boolean allHistogramsReady() throws Exception
	{
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
			if ( !Files.exists( Paths.get( generateSliceHistogramsPath( 0, slice ) ) ) )
				return false;
		return true;
	}

	private String generateSliceHistogramsPath( final int scale, final int slice )
	{
		return histogramsPath + "/" + scale + "/" + slice + ".hist";
	}

	private int getNumSlices()
	{
		return ( int ) ( workingInterval.numDimensions() == 3 ? workingInterval.dimension( 2 ) : 1 );
	}

	private int getAggregationTreeDepth()
	{
		return ( int ) Math.ceil( Math.log( sparkContext.defaultParallelism() ) / Math.log( 2 ) );
	}
	/*private int getNumPartitionsForScaleLevel( final int scale )
	{
		return sparkContext.defaultParallelism() * ( 1 << scale );
	}*/

	private long[] getMinSize( final TileInfo[] tiles )
	{
		final long[] minSize = tiles[ 0 ].getSize().clone();
		for ( final TileInfo tile : tiles )
			for ( int d = 0; d < minSize.length; d++ )
				if (minSize[ d ] > tile.getSize( d ))
					minSize[ d ] = tile.getSize( d );
		return minSize;
	}
}
