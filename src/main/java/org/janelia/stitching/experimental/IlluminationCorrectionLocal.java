package org.janelia.stitching.experimental;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.spark.api.java.JavaPairRDD;
import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.Utils;
import org.janelia.util.concurrent.MultithreadedExecutor;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.MapSerializer;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.dog.DifferenceOfGaussian;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.RandomAccessibleOnRealRandomAccessible;
import net.imglib2.view.Views;
import scala.Tuple2;



public class IlluminationCorrectionLocal
{
	private enum Mestimator { LS, CAUCHY };

	class Parameters
	{
		public TreeMap< Float, Short >[] histograms;

		public double CAUCHY_W;		// width of the Cauchy function used for robust regression
		public double[] PivotShiftY;	// shift on the q axis into the pivot space
		public int ITER;				// iteration count for the optimization
		public Mestimator MESTIMATOR;	// specifies "CAUCHY" or "LS" (least squares)
		public int TERMSFLAG;			// flag specifing which terms to include in the energy function
		public double LAMBDA_ZERO = Math.sqrt(10)/* / 2500*/;	// coefficient for the zero-light term ----- worked well with 1e6
		public double LAMBDA_VREG = 1e14/* / 2500*/;			// coefficient for the v regularization
		public double LAMBDA_BARR = 1e6;//1e0/* / 2500*/;				// the barrier term coefficient  ---- doesn't affect much!
		public double ZMIN;			// minimum possible value for Z
		public double ZMAX;			// maximum possible value for Z

		public long[] currentSize;

		// solution
		public double[] vFinal;
		public double[] zFinal;
	}

	private static final double WINDOW_POINTS_PERCENT = 0.25;

	private double[] Q;
	private final String inputFilepath;
	private String outPath;
	private TileInfo[] tiles;
	private TileInfo[] correctedTiles;

	private Parameters[] parameters;

	private int mid_ind;
	private double PivotShiftX;		// shift on the Q axis into the pivot space

	private long elapsed;
	private int numThreads = Runtime.getRuntime().availableProcessors();
	private MultithreadedExecutor multithreadedExecutor;

	private int N;	// stack size
	private int quantiles = 200; // shrinked stack size
	private int numPixels;
	private long[] originalSize;



	public static void main( final String[] args ) throws Exception
	{
		final IlluminationCorrectionLocal driver = new IlluminationCorrectionLocal( args[ 0 ] );
		driver.run();
		driver.shutdown();
		System.out.println("Done");
	}


	public IlluminationCorrectionLocal( final String inputFilepath )
	{
		this.inputFilepath = inputFilepath;
	}

	public void shutdown()
	{
		if ( multithreadedExecutor != null )
			multithreadedExecutor.close();
	}


	public void run() throws Exception
	{
		final DataProvider dataProvider = DataProviderFactory.createFSDataProvider();

		try {
			tiles = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( inputFilepath ) );
			N = tiles.length;
		} catch (final IOException e) {
			e.printStackTrace();
			return;
		}

		originalSize = getMinSize(tiles);

		// check if all tiles have the same size
		for ( final TileInfo tile : tiles )
			for ( int d = 0; d < tile.numDimensions(); d++ )
				if ( tile.getSize(d) != originalSize[ d ] )
				{
					System.out.println("Assumption failed: not all the tiles are of the same size");
					System.exit(1);
				}

		if ( !allShrinkedSliceHistogramsReady() )
			throw new Exception( "Histograms should be precomputed" );

		Q = readReferenceVectorFromDisk();
		if ( Q == null )
			throw new Exception( "Reference vector should be precomputed" );

		numPixels = (int) ( originalSize[0]*originalSize[1] );
		N = quantiles;
		System.out.println( "Now working with shrinked stack of size " + N + ",  "+ String.format("%d slices x %d pixels", getNumSlices(), numPixels ) );

		outPath = Paths.get(inputFilepath).getParent().toString()+"/estimated";
		new File( outPath+"/v" ).mkdirs();
		new File( outPath+"/z" ).mkdirs();

		parameters = new Parameters[ numThreads ];

		// Transform Q and S (which contains q) to the pivot space.
		// The pivot space is just a shift of the origin to the median datum. First, the shift for Q:
		final int mid_ind = (N-1) / 2;
		PivotShiftX = Q[mid_ind];
		System.out.println( "PivotShiftX="+PivotShiftX);
		for (int i = 0; i < N; i++)
			Q[i] -= PivotShiftX;

		multithreadedExecutor = new MultithreadedExecutor( Executors.newFixedThreadPool( numThreads ), numThreads );
		multithreadedExecutor.run( ( thread, slice ) -> {
			try {
				run( thread, slice );
				return 0;
			} catch (final Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return -1;
			}
		}, getNumSlices() );
	}

	private void run( final int thread, final int slice ) throws Exception
	{
		parameters[ thread ] = new Parameters();
		parameters[ thread ].histograms = readShrinkedSliceHistogramsFromDisk( slice + 1 );
		parameters[ thread ].currentSize = new long[] { originalSize[0],originalSize[1] };

		// initial guesses for the correction surfaces
		final double[] v0 = new double[ numPixels ];
		final double[] b0 = new double[ numPixels ];
		Arrays.fill( v0, 1.0 );

		parameters[ thread ].PivotShiftY = new double[ numPixels ];

		// the shift for each location q
		for ( int pixel = 0; pixel < numPixels; pixel++ )
		{
			int counter = 0;
	        for ( final Entry< Float, Short > entry : parameters[thread].histograms[pixel].entrySet() )
	        {
	        	counter += entry.getValue();
	        	if ( counter > mid_ind )
	        	{
	        		parameters[thread].PivotShiftY[ pixel ] = entry.getKey();
	        		break;
	        	}
	        }
		}

		// also, account for the pivot shift in b0
		//b0 = b0 + PivotShiftX*v0 - PivotShiftY;
		for ( int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++ )
			b0[ pixelIndex ] += PivotShiftX * v0[ pixelIndex ] - parameters[thread].PivotShiftY[ pixelIndex ];

		float minKey = Float.MAX_VALUE, maxKey = -Float.MAX_VALUE;
		for ( int pixel = 0; pixel < numPixels; pixel++ )
		{
			minKey = Math.min( parameters[thread].histograms[ pixel ].firstKey(), minKey );
			maxKey = Math.max( parameters[thread].histograms[ pixel ].lastKey(),  maxKey );
		}
		System.out.println( "slice="+slice+": minKey=" + minKey + ", maxKey=" + maxKey );

		// some parameters initialization
		parameters[thread].ZMIN = 0;
		parameters[thread].ZMAX = minKey;
		final double zx0 = 0.85 * parameters[thread].ZMAX, zy0 = zx0;

		// vector containing initial values of the variables we want to estimate
		//x0 = [v0(:); b0(:); zx0; zy0];
		final double[] x0 = new double[2 * numPixels + 2];
		int pX = 0;
		for (int i = 0; i < v0.length; i++)
			x0[pX++] = v0[i];
		for (int i = 0; i < b0.length; i++)
			x0[pX++] = b0[i];
		x0[pX++] = zx0;
		x0[pX++] = zy0;

		final MinFuncOptions minFuncOptions = new MinFuncOptions();
		minFuncOptions.maxIter      = 10000;//500;							// max iterations for optimization
		minFuncOptions.MaxFunEvals  = 10000;//;1000;							// max evaluations of objective function
		minFuncOptions.progTol      = 1e-5;							// progress tolerance
		minFuncOptions.optTol       = 1e-5;							// optimality tolerance
		minFuncOptions.Corr         = 100;							// number of corrections to store in memory (default: 100)

		// First call to roughly estimate our variables
		parameters[thread].ITER = 1;
		parameters[thread].MESTIMATOR = Mestimator.LS;
		parameters[thread].TERMSFLAG = 0;
		MinFuncResult minFuncResult = minFunc( x0, minFuncOptions, thread );

		double[] x  = minFuncResult.x;
		double fval = minFuncResult.f;

		// unpack
		final double[] v1 = Arrays.copyOfRange(x, 0, numPixels);
		final double[] b1 = Arrays.copyOfRange(x, numPixels, 2*numPixels);
		final double zx1 = x[2 * numPixels];
		final double zy1 = x[2 * numPixels + 1];


//		MinFuncResult minFuncResult = null;
//		double fval;
//		double[] x = leastSquaresFit();
//		final double[] v1 = Arrays.copyOfRange(x, 0, numPixels);
//		final double[] z1 = Arrays.copyOfRange(x, numPixels, 2*numPixels);
//		final double zx1 = zx0;
//		final double zy1 = zy0;



		System.out.println("-------------");
		// 2nd optimization using REGULARIZED ROBUST fitting
		// use the mean standard error of the LS fitting to set the width of the
		// CAUCHY function
		parameters[thread].CAUCHY_W = computeStandardError(v1, b1, thread);
		System.out.println("CAUCHY_W="+parameters[thread].CAUCHY_W);

		// assign the remaining global variables needed in cdr_objective
		parameters[thread].ITER = 1;
		parameters[thread].MESTIMATOR = Mestimator.CAUCHY;
		parameters[thread].TERMSFLAG = 1;

		// vector containing initial values of the variables we want to estimate
		final double[] x1 = new double[2 * numPixels + 2];

		int pX1 = 0;
		for (int i = 0; i < v1.length; i++)
			x1[pX1++] = v1[i];
		for (int i = 0; i < b1.length; i++)
			x1[pX1++] = b1[i];
		x1[pX1++] = zx1;
		x1[pX1++] = zy1;

		minFuncResult = minFunc(x1, minFuncOptions, thread);
		x = minFuncResult.x;
		fval = minFuncResult.f;

		// unpack the optimized v surface, b surface, xc, and yc from the vector x
		final double[] v = Arrays.copyOfRange(x, 0, numPixels);
		final double[] b = Arrays.copyOfRange(x, numPixels, 2*numPixels);
		final double zx = x[2 * numPixels];
		final double zy = x[2 * numPixels + 1];

		// Build the final correction model


		//rescaleHelper(b, "b");

		// Unpivot b: move pivot point back to the original location
		for ( int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++ )
			b[ pixelIndex ] -= PivotShiftX * v[ pixelIndex ] - parameters[thread].PivotShiftY[ pixelIndex ];

		//rescaleHelper(b, "b_unpivoted");

		// shift the b surface to the zero-light surface
		final double[] z = new double[numPixels];
		for ( int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++ )
			z[pixelIndex] = b[pixelIndex] + zx * v[pixelIndex];


		//parameters[thread].vFinal = rescaleHelper( v, "V", thread, slice );
		//parameters[thread].zFinal = rescaleHelper( z, "Z", thread, slice );

		final ArrayImg< DoubleType, DoubleArray > vImg = ArrayImgs.doubles( v, parameters[thread].currentSize );
		final ImagePlus vImp = ImageJFunctions.wrap( vImg, "V_"+(slice+1) );
		Utils.workaroundImagePlusNSlices( vImp );
		IJ.saveAsTiff( vImp, outPath + "/v/" + (slice+1) + ".tif" );

		final ArrayImg< DoubleType, DoubleArray > zImg = ArrayImgs.doubles( z, parameters[thread].currentSize );
		final ImagePlus zImp = ImageJFunctions.wrap( zImg, "Z_"+(slice+1) );
		Utils.workaroundImagePlusNSlices( zImp );
		IJ.saveAsTiff( zImp, outPath + "/z/" + (slice+1) + ".tif" );


		//correctImages();
	}

	/*private double[] leastSquaresFit() throws Exception
	{
		System.out.println("Least squares fit...");

		final double[] v = new double[ numPixels ];
		final double[] z = new double[ numPixels ];

		ai.set(0);
		for ( int ithread = 0; ithread < numThreads; ++ithread )
			futures[ ithread ] = threadPool.submit(() -> {
				final int myNumber = ai.getAndIncrement();
				final int wholeSize = numPixels;
				final int chunk = (wholeSize / numThreads) + (wholeSize % numThreads == 0 ? 0 : 1);
				final int startInd = chunk * myNumber;
				final int endInd = Math.min(startInd+chunk, wholeSize);
				for ( int pixel = startInd; pixel < endInd; pixel++ )
				{
					final AffineModel1D model = new AffineModel1D();

					final double[] p = new double[ N ], q = new double[ N ], w = new double[ N ];
					Arrays.fill( w, 1.0 );

					int counter = 0;
					for ( final Entry< Short, Integer > entry : histograms[ pixel ].entrySet() )
					{
						for ( int j = 0; j < entry.getValue(); j++ )
						{
							final int i = counter++;
							p[ i ] = entry.getKey() - PivotShiftY[ pixel ];
							q[ i ] = Q[ i ];
						}
					}

					//model.filterRansac(candidates, inliers, 100, 5, 0.5);
					//model.filter(candidates, inliers, 3);
					try {
						model.fit(new double[][] { p }, new double[][] { q }, w);
					} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
						e.printStackTrace();
					}

					final double[] m = new double[ 2 ];
					model.toArray( m );

					//System.out.println(String.format("%d of %d points used to get a=%.3f, b=%.3f", inliers.size(), candidates.size(), m[0], m[1]));

					v[pixel] = m[0];
					z[pixel] = m[1];
				}
			});
		for ( final Future< ? > future : futures )
			future.get();

		final double[] ret = new double[ 2*numPixels ];
		System.arraycopy( v, 0, ret, 0, numPixels );
		System.arraycopy( z, 0, ret, numPixels, numPixels );
		return ret;
	}*/


	/*private < T extends NativeType< T > & RealType< T > > void populateHistograms() throws Exception
	{
		final JavaRDD< TileInfo > rddTiles = sparkContext.parallelize( Arrays.asList( tiles ), tiles.length / 2 );

		saveAsObjectFile(
				rddTiles.flatMapToPair( new PairFlatMapFunction< TileInfo, Integer, TreeMap< Short, Integer > >()
			{
				private static final long serialVersionUID = -3750330336298367217L;

				@Override
				public Iterable< Tuple2< Integer, TreeMap < Short, Integer > > > call( final TileInfo tile ) throws Exception
				{
					System.out.println( "Processing tile " + tile.getIndex() );

					System.out.println( "  Opening an image.." );
					final ImagePlus imp = IJ.openImage( tile.getFilePath() );
					Utils.workaroundImagePlusNSlices( imp );
					final Img< T > img = ImagePlusImgs.from( imp );
					final Cursor< T > cursor = Views.iterable( img ).localizingCursor();

					final List< Tuple2< Integer, TreeMap< Short, Integer > > > result = new ArrayList<>();

					final int[] dimensions = Intervals.dimensionsAsIntArray( img );
					final int[] position = new int[ img.numDimensions() ];

					System.out.println( "  Iterating over the image and creating the maps.." );
					while ( cursor.hasNext() )
					{
						cursor.fwd();
						cursor.localize( position );
						final int pixel = IntervalIndexer.positionToIndex( position, dimensions );

						final short key = ( short ) cursor.get().getRealDouble();
						final TreeMap< Short, Integer > map = new TreeMap<>();
						map.put( key, 1 );

						result.add( new Tuple2<>( pixel, map ) );
					}
					imp.close();
					System.out.println( "  Finished" );
					return result;
				}
			}
		).persist( StorageLevel.DISK_ONLY() )

		.reduceByKey( new Function2< TreeMap< Short, Integer >, TreeMap< Short, Integer >, TreeMap< Short, Integer > >()
			{
				private static final long serialVersionUID = -3335510593959439167L;

				@Override
				public TreeMap< Short, Integer > call( final TreeMap< Short, Integer > a, final TreeMap< Short, Integer > b ) throws Exception
				{
					System.out.println( "Reducing results" );

					if ( a == null )
						return b;
					else if ( b == null )
						return a;

					for ( final Entry< Short, Integer > entry : b.entrySet() )
						a.put( entry.getKey(), a.getOrDefault( entry.getKey(), 0 ) + entry.getValue() );
					return a;
				}
			}
		)//.saveAsObjectFile( inputFilepath + "-rdd" );
				,
				inputFilepath + "-rdd-kryoserializer" );

	}


	private void saveAsObjectFile( final JavaPairRDD< Integer, TreeMap< Short, Integer > > rdd, final String path )
	{
		final KryoSerializer kryoSerializer = new KryoSerializer( sparkContext.getConf() );

		rdd.mapPartitions( iter -> Utils.grouped( iter, 10 ) )
		.mapToPair( new PairFunction< List< Tuple2< Integer, TreeMap< Short, Integer > > >, NullWritable, BytesWritable >()
			{
				private static final long serialVersionUID = 3810360591518212511L;

				@Override
				public Tuple2< NullWritable, BytesWritable > call( final List < Tuple2< Integer, TreeMap< Short, Integer > > > data )
				{
					System.out.println( "Saving a partition" );

					final Kryo kryo = kryoSerializer.newKryo();
					final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
					try ( final Output output = kryoSerializer.newKryoOutput() )
					{
						output.setOutputStream( byteStream );
						kryo.writeClassAndObject( output, data );
					}
					return new Tuple2<>( NullWritable.get(), new BytesWritable( byteStream.toByteArray() ) );
				}
			}
		).saveAsHadoopFile( path, NullWritable.class, BytesWritable.class, SequenceFileOutputFormat.class, BZip2Codec.class );
	}*/

	/*
	  def objectFile[T](sc: SparkContext, path: String, minPartitions: Int = 1)(implicit ct: ClassTag[T]) = {
	    val kryoSerializer = new KryoSerializer(sc.getConf)

	    sc.sequenceFile(path, classOf[NullWritable], classOf[BytesWritable], minPartitions)
	      .flatMap(x => {
	      val kryo = kryoSerializer.newKryo()
	      val input = new Input()
	      input.setBuffer(x._2.getBytes)
	      val data = kryo.readClassAndObject(input)
	      val dataObject = data.asInstanceOf[Array[T]]
	      dataObject
	    })

	  }*/


	/*private void populateHistograms() throws Exception
	{
		final int treeDepth = (int) Math.ceil( Math.log( sparkContext.defaultParallelism() ) / Math.log( 2 ) );
		System.out.println( "default parallelism = " + sparkContext.defaultParallelism() + ",  tree depth = " + treeDepth );

		final int numChunks = 25;
		final int numSlices = (int) ( originalSize.length == 3 ? originalSize[ 2 ] : 1 );
		final int numSlicesInRDD = (numSlices / numChunks) + (numSlices % numChunks == 0 ? 0 : 1);

		for ( int chunkFirstSlice = 1; chunkFirstSlice <= numSlices; chunkFirstSlice += numSlicesInRDD )
		{
			final Pair< Integer, Integer > sliceRange = new SerializablePair<>( chunkFirstSlice, Math.min( chunkFirstSlice + numSlicesInRDD - 1, numSlices ) );

			final long[] outDimensions = originalSize.clone();
			final long flattenedSize;
			if ( outDimensions.length == 3 )
			{
				outDimensions[ 2 ] = sliceRange.getB() - sliceRange.getA() + 1;
				flattenedSize = outDimensions[0] * outDimensions[1] * outDimensions[2];
			}
			else
				flattenedSize = outDimensions[0] * outDimensions[1];

			final JavaRDD< TileInfo > rdd = sparkContext.parallelize( Arrays.asList( tiles ) );

			final TreeMap<Short,Integer>[] result = rdd.treeAggregate(
				null, // zero value

				// generator
				new Function2< TreeMap<Short,Integer>[], TileInfo, TreeMap<Short,Integer>[] >()
				{
					private static final long serialVersionUID = -4991255417353136684L;

					@Override
					public TreeMap<Short,Integer>[] call( final TreeMap<Short,Integer>[] intermediateHist, final TileInfo tile ) throws Exception
					{
						System.out.println( "Loading tile " + tile.getIndex() );
						final ImagePlus imp = IJ.openImage( tile.getFilePath() );
						Utils.workaroundImagePlusNSlices( imp );

						System.out.println( "Populating the histogram" );
						final TreeMap<Short,Integer>[] result;
						if ( intermediateHist != null )
						{
							result = intermediateHist;
						}
						else
						{
							result = new TreeMap[ (int) flattenedSize ];
							for ( int i = 0; i < result.length; i++ )
								result[ i ] = new TreeMap<>();
						}

						final long[] position = new long[ outDimensions.length ];
						for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
						{
							imp.setSlice( slice );
							final float[][] pixels = imp.getProcessor().getFloatArray();

							if ( position.length == 3 )
								position[ 2 ] = slice - sliceRange.getA();

							for ( int y = 0; y < imp.getHeight(); y++ )
							{
								position[ 1 ] = y;

								for ( int x = 0; x < imp.getWidth(); x++ )
								{
									position[ 0 ] = x;

									final int pixelIndex = (int) IntervalIndexer.positionToIndex( position, outDimensions );
									final short key = ( short ) pixels[ x ][ y ];
									result[ pixelIndex ].put( key, result[ pixelIndex ].getOrDefault( key, 0 ) + 1 );
								}
							}
						}
						return result;
					}
				},

				// reducer
				new Function2< TreeMap<Short,Integer>[], TreeMap<Short,Integer>[], TreeMap<Short,Integer>[] >()
				{
					private static final long serialVersionUID = 3979781907633918053L;

					@Override
					public TreeMap<Short,Integer>[] call( final TreeMap<Short,Integer>[] a, final TreeMap<Short,Integer>[] b ) throws Exception
					{
						System.out.println( "Combining intermediate results" );

						if ( a == null )
							return b;
						else if ( b == null )
							return a;

						for ( int pixelIndex = 0; pixelIndex < b.length; pixelIndex++ )
							for ( final Entry< Short, Integer > entry : b[ pixelIndex ].entrySet() )
								a[ pixelIndex ].put( entry.getKey(), a[ pixelIndex ].getOrDefault( entry.getKey(), 0 ) + entry.getValue() );
						return a;
					}
				}

			, treeDepth );

			System.out.println( "Obtained result for slices " + sliceRange );

			final int sliceSize = (int) (outDimensions[0] * outDimensions[1]);
			for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
			{
				final int sliceIndex = slice - sliceRange.getA();
				saveSliceHistogramsToDisk( slice, Arrays.copyOfRange( result, sliceIndex * sliceSize, (sliceIndex+1) * sliceSize ) );
			}
		}
	}*/



	private void saveShrinkedSliceHistograms( final JavaPairRDD< Long, TreeMap< Float, Short > > rdd ) throws Exception
	{
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
		{
			final int currentSlice = slice;
			final List< Tuple2< Long, TreeMap<Float, Short> > > shrinkedSliceHistograms = rdd.filter( tuple ->
				{
					if ( originalSize.length == 2 )
						return true;

					final long[] pos = new long[ originalSize.length ];
					IntervalIndexer.indexToPosition( tuple._1(), originalSize, pos );
					return ( pos[ 2 ] == currentSlice - 1 );
				}
			).collect();

			final TreeMap<Float, Short>[] arr = new TreeMap[ shrinkedSliceHistograms.size() ];
			final long[] pos = new long[ originalSize.length ];
			for ( final Tuple2<Long, TreeMap<Float, Short>> tuple : shrinkedSliceHistograms )
			{
				int pixel = tuple._1().intValue();
				if ( originalSize.length > 2 )
				{
					IntervalIndexer.indexToPosition( pixel, originalSize, pos );
					pos[ 2 ] = 0;
					pixel = (int) IntervalIndexer.positionToIndex( pos, originalSize );
				}

				arr[ pixel ] = tuple._2();
			}

			saveShrinkedSliceHistogramsToDisk(currentSlice, arr);
		}
	}

	private JavaPairRDD< Long, TreeMap< Float, Short > > shrinkStack( final JavaPairRDD< Long, TreeMap< Short, Integer > > rddHistograms )
	{
		return rddHistograms.mapToPair( tuple ->
			{
				final TreeMap< Float, Short > shrinkedMap = new TreeMap<>();
				int increasedChunkCount = N % quantiles;
				int chunkSize = N / quantiles + ( increasedChunkCount == 0 ? 0 : 1 );
				int counter = 0, chunkCounter = 0, chunkSum = 0;
				for ( final Entry< Short, Integer > entry : tuple._2().entrySet() )
				{
					final short val = entry.getKey();
					for ( int j = 0; j < entry.getValue(); j++ )
					{
						counter++;
						chunkCounter++;
						chunkSum += val;

						if ( chunkCounter == chunkSize || counter == N )
						{
							final float meanVal = (float) ( (double) chunkSum / chunkCounter );
							shrinkedMap.put( meanVal, (short) (shrinkedMap.getOrDefault( meanVal, (short) 0 ) + 1) );
							chunkCounter = 0;
							chunkSum = 0;
							if ( increasedChunkCount > 0 )
							{
								increasedChunkCount--;
								if ( increasedChunkCount == 0 )
									chunkSize--;
							}
						}
					}
				}
				return new Tuple2<>( tuple._1(), shrinkedMap );
			}
		);
	}


	private void saveSliceHistogramsToDisk( final int slice, final TreeMap<Short,Integer>[] sliceHist ) throws Exception
	{
		final String path = generateSliceHistogramsPath( slice );

		new File( Paths.get( path ).getParent().toString() ).mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		final MapSerializer serializer = new MapSerializer();
		serializer.setKeysCanBeNull( false );
		serializer.setKeyClass( Short.class, kryo.getSerializer( Short.class ) );
		serializer.setValueClass( Integer.class, kryo.getSerializer( Integer.class) );
		kryo.register( TreeMap.class, serializer );
		kryo.register( TreeMap[].class );

		//try ( final Output output = kryoSerializer.newKryoOutput() )
		//{
		//	output.setOutputStream( os );
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, sliceHist );
		}
	}

	private TreeMap<Short,Integer>[] readSliceHistogramsFromDisk( final int slice ) throws Exception
	{
		System.out.println( "Loading slice " + slice );
		final String path = generateSliceHistogramsPath( slice );

		if ( !Files.exists(Paths.get(path)) )
			return null;

		final InputStream is = new DataInputStream(
				new BufferedInputStream(
						new FileInputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		final MapSerializer serializer = new MapSerializer();
		serializer.setKeysCanBeNull( false );
		serializer.setKeyClass( Short.class, kryo.getSerializer( Short.class ) );
		serializer.setValueClass( Integer.class, kryo.getSerializer( Integer.class) );
		kryo.register( TreeMap.class, serializer );
		kryo.register( TreeMap[].class );

		try ( final Input input = new Input( is ) )
		{
			return ( TreeMap<Short,Integer>[] ) kryo.readClassAndObject( input );
		}
	}


	private void saveReferenceVectorToDisk( final double[] vec ) throws Exception
	{
		final String path = inputFilepath + "_Q.ser";

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		kryo.register( double[].class );

		//try ( final Output output = kryoSerializer.newKryoOutput() )
		//{
		//	output.setOutputStream( os );
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, vec );
		}
	}

	private double[] readReferenceVectorFromDisk() throws Exception
	{
		final String path = inputFilepath + "_Q.ser";

		if ( !Files.exists(Paths.get(path)) )
			return null;

		final InputStream is = new DataInputStream(
				new BufferedInputStream(
						new FileInputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		kryo.register( double[].class );

		try ( final Input input = new Input( is ) )
		{
			return ( double[] ) kryo.readClassAndObject( input );
		}
	}


	private void saveShrinkedSliceHistogramsToDisk( final int slice, final TreeMap<Float,Short>[] sliceHist ) throws Exception
	{
		final String path = generateShrinkedSliceHistogramsPath( slice );

		new File( Paths.get( path ).getParent().toString() ).mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		final MapSerializer serializer = new MapSerializer();
		serializer.setKeysCanBeNull( false );
		serializer.setKeyClass( Float.class, kryo.getSerializer( Float.class ) );
		serializer.setValueClass( Short.class, kryo.getSerializer( Short.class) );
		kryo.register( TreeMap.class, serializer );
		kryo.register( TreeMap[].class );

		//try ( final Output output = kryoSerializer.newKryoOutput() )
		//{
		//	output.setOutputStream( os );
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, sliceHist );
		}
	}


	private TreeMap<Float,Short>[] readShrinkedSliceHistogramsFromDisk( final int slice ) throws Exception
	{
		System.out.println( "Loading slice " + slice );
		final String path = generateShrinkedSliceHistogramsPath( slice );

		if ( !Files.exists(Paths.get(path)) )
			return null;

		final InputStream is = new DataInputStream(
				new BufferedInputStream(
						new FileInputStream( path )
						)
				);

		//final Kryo kryo = kryoSerializer.newKryo();
		final Kryo kryo = new Kryo();
		final MapSerializer serializer = new MapSerializer();
		serializer.setKeysCanBeNull( false );
		serializer.setKeyClass( Float.class, kryo.getSerializer( Float.class ) );
		serializer.setValueClass( Short.class, kryo.getSerializer( Short.class) );
		kryo.register( TreeMap.class, serializer );
		kryo.register( TreeMap[].class );

		try ( final Input input = new Input( is ) )
		{
			return ( TreeMap<Float,Short>[] ) kryo.readClassAndObject( input );
		}
	}



	private boolean allSliceHistogramsReady() throws Exception
	{
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
			if ( !Files.exists( Paths.get( generateSliceHistogramsPath( slice ) ) ) )
				return false;
		return true;
	}

	private boolean allShrinkedSliceHistogramsReady() throws Exception
	{
		for ( int slice = 1; slice <= getNumSlices(); slice++ )
			if ( !Files.exists( Paths.get( generateShrinkedSliceHistogramsPath( slice ) ) ) )
				return false;
		return true;
	}

	private String generateSliceHistogramsPath( final int slice )
	{
		final String folder = Paths.get(inputFilepath).getParent().toString();
		final String subfolder = Paths.get(inputFilepath).getFileName().toString() + "_histograms";
		final String filename = slice + ".hist";
		return folder + "/" + subfolder + "/" + filename;
	}

	private String generateShrinkedSliceHistogramsPath( final int slice )
	{
		final String folder = Paths.get(inputFilepath).getParent().toString();
		final String subfolder = Paths.get(inputFilepath).getFileName().toString() + "_histograms-shrinked";
		final String filename = slice + ".hist";
		return folder + "/" + subfolder + "/" + filename;
	}

	private int getNumSlices()
	{
		return (int) ( originalSize.length == 3 ? originalSize[ 2 ] : 1 );
	}

	/*private void populateHistograms() throws Exception
	{
		final int treeDepth = (int) Math.ceil( Math.log( sparkContext.defaultParallelism() ) / Math.log( 2 ) );
		System.out.println( "default parallelism = " + sparkContext.defaultParallelism() + ",  tree depth = " + treeDepth );

		final int numChunks = 25;
		final int numSlices = (int) ( originalSize.length == 3 ? originalSize[ 2 ] : 1 );
		final int numSlicesInRDD = (numSlices / numChunks) + (numSlices % numChunks == 0 ? 0 : 1);

		for ( int chunkFirstSlice = 1; chunkFirstSlice <= numSlices; chunkFirstSlice += numSlicesInRDD )
		{
			final Pair< Integer, Integer > sliceRange = new SerializablePair<>( chunkFirstSlice, Math.min( chunkFirstSlice + numSlicesInRDD - 1, numSlices ) );

			final JavaRDD< TileInfo > rdd = sparkContext.parallelize( Arrays.asList( tiles ) );

			final List<TreeMap<Short,Integer>[][]> result = rdd.treeAggregate(
				null, // zero value

				// generator
				new Function2< List<TreeMap<Short,Integer>[][]>, TileInfo, List<TreeMap<Short,Integer>[][]> >()
				{
					private static final long serialVersionUID = -4991255417353136684L;

					@Override
					public List<TreeMap<Short,Integer>[][]> call( final List<TreeMap<Short,Integer>[][]> intermediateHist, final TileInfo tile ) throws Exception
					{
						System.out.println( "Loading tile " + tile.getIndex() );
						final ImagePlus imp = IJ.openImage( tile.getFilePath() );
						Utils.workaroundImagePlusNSlices( imp );

						System.out.println( "Populating the histogram" );
						final List<TreeMap<Short,Integer>[][]> result;
						if ( intermediateHist != null )
						{
							result = intermediateHist;
						}
						else
						{
							result = new ArrayList<>();
							for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
							{
								TreeMap<Short,Integer>[][] sliceHist = new TreeMap[ imp.getWidth() ][ imp.getHeight() ];
								for ( int x = 0; x < sliceHist.length; x++ )
									for ( int y = 0; y < sliceHist[ x ].length; y++ )
										sliceHist[ x ][ y ] = new TreeMap<>();
								result.add( sliceHist );
							}
						}

						for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
						{
							imp.setSlice( slice );
							final float[][] pixels = imp.getProcessor().getFloatArray();
							final TreeMap<Short,Integer>[][] sliceHist = result.get( slice - sliceRange.getA() );

							for ( int x = 0; x < pixels.length; x++ )
							{
								for ( int y = 0; y < pixels[ x ].length; y++ )
								{
									final short key = ( short ) pixels[ x ][ y ];
									sliceHist[ x ][ y ].put( key, sliceHist[ x ][ y ].getOrDefault( key, 0 ) + 1 );
								}
							}
						}
						return result;
					}
				},

				// reducer
				new Function2< List<TreeMap<Short,Integer>[][]>, List<TreeMap<Short,Integer>[][]>, List<TreeMap<Short,Integer>[][]> >()
				{
					private static final long serialVersionUID = 3979781907633918053L;

					@Override
					public List<TreeMap<Short,Integer>[][]> call( final List<TreeMap<Short,Integer>[][]> a, final List<TreeMap<Short,Integer>[][]> b ) throws Exception
					{
						System.out.println( "Combining intermediate results" );

						if ( a == null )
							return b;
						else if ( b == null )
							return a;

						for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
						{
							final TreeMap<Short,Integer>[][] sliceHistA = a.get( slice - sliceRange.getA() );
							final TreeMap<Short,Integer>[][] sliceHistB = b.get( slice - sliceRange.getA() );

							for ( int x = 0; x < sliceHistB.length; x++ )
								for ( int y = 0; y < sliceHistB[ x ].length; y++ )
									for ( final Entry< Short, Integer > entry : sliceHistB[ x ][ y ].entrySet() )
										sliceHistA[ x ][ y ].put( entry.getKey(), sliceHistA[ x ][ y ].getOrDefault( entry.getKey(), 0 ) + entry.getValue() );
						}

						return a;
					}
				}

			, treeDepth );

			System.out.println( "Obtained result for slices " + sliceRange );

			for ( int slice = sliceRange.getA(); slice <= sliceRange.getB(); slice++ )
				saveSliceHistogramsToDisk( slice, result.get( slice - sliceRange.getA() ) );
		}
	}


	private void saveSliceHistogramsToDisk( final int slice, final TreeMap<Short,Integer>[][] sliceHist ) throws Exception
	{
		final String folder = Paths.get(inputFilepath).getParent().toString();
		final String subfolder = Paths.get(inputFilepath).getFileName().toString() + "_histograms";
		final String filename = slice + ".hist";
		final String path = folder + "/" + subfolder + "/" + filename;

		new File( folder + "/" + subfolder ).mkdirs();

		final OutputStream os = new DataOutputStream(
				new BufferedOutputStream(
						new FileOutputStream( path )
						)
				);
		try ( final Output output = new Output( os ) )
		{
			kryo.writeClassAndObject( output, sliceHist );
		}
	}

	private TreeMap<Short,Integer>[][] readSliceHistogramsFromDisk( final int slice ) throws Exception
	{
		final String folder = Paths.get(inputFilepath).getParent().toString();
		final String subfolder = Paths.get(inputFilepath).getFileName().toString() + "_histograms";
		final String filename = slice + ".hist";
		final String path = folder + "/" + subfolder + "/" + filename;

		if ( !Files.exists(Paths.get(path)) )
			return null;

		final InputStream is = new DataInputStream(
				new BufferedInputStream(
						new FileInputStream( path )
						)
				);
		try ( final Input input = new Input( is ) )
		{
			return ( TreeMap<Short,Integer>[][] ) kryo.readClassAndObject( input );
		}
	}*/


	private < T extends RealType< T > & NativeType< T > > void rescale( final Img< T > srcImg, final Img< T > dstImg )
	{
		// Define scaling transform
		final double[] scalingCoeffs = new double[ srcImg.numDimensions() ];
		for ( int d = 0; d < scalingCoeffs.length; d++ )
			scalingCoeffs[ d ] = (double) dstImg.dimension( d ) / srcImg.dimension( d );
		final Scale scalingTransform = new Scale( scalingCoeffs );

		// Prepare source image
		final ExtendedRandomAccessibleInterval< T, Img< T > > srcImgExtended = Views.extendBorder( srcImg );
		final RealRandomAccessible< T > srcImgInterpolated = Views.interpolate( srcImgExtended, new NLinearInterpolatorFactory<>() );
		final RealTransformRandomAccessible< T, InverseRealTransform > srcImgTransformed = RealViews.transform( srcImgInterpolated, scalingTransform );
		final RandomAccessibleOnRealRandomAccessible< T > srcImgTransformedRastered = Views.raster( srcImgTransformed );
		final IntervalView< T > srcImgScaledView = Views.interval( srcImgTransformedRastered, dstImg );
		final IterableInterval< T > srcImgScaledIterable = Views.flatIterable( srcImgScaledView );
		final Cursor< T > srcImgScaledCursor = srcImgScaledIterable.cursor();

		// Prepare destination image
		final IterableInterval< T > dstImgIterable = Views.flatIterable( dstImg );
		final Cursor< T > dstImgCursor = dstImgIterable.cursor();

		// Write data
		while ( dstImgCursor.hasNext() && srcImgScaledCursor.hasNext() )
			dstImgCursor.next().set( srcImgScaledCursor.next() );
	}

	private final double[] rescaleHelper( final double[] srcArr, final String title, final int thread, final int slice )
	{
		final ArrayImg< DoubleType, DoubleArray > srcImg = ArrayImgs.doubles( srcArr, parameters[thread].currentSize );
		final ArrayImg< DoubleType, DoubleArray > dstImg = ArrayImgs.doubles( parameters[thread].currentSize );
		rescale( srcImg, dstImg );

		final ImagePlus imp = ImageJFunctions.wrap( dstImg, title );
		Utils.workaroundImagePlusNSlices( imp );
		IJ.saveAsTiff( imp, inputFilepath + "_" + title + ".tif" );

		return dstImg.update( null ).getCurrentStorageArray();
	}


	private MinFuncResult minFunc(final double[] x0, final MinFuncOptions minFuncOptions, final int thread) throws Exception
	{
		final double[] x;
		double f = 0.0;

		final int maxIter      = minFuncOptions.maxIter;
		final int maxFunEvals  = minFuncOptions.MaxFunEvals;
		final double progTol   = minFuncOptions.progTol;
		final double optTol    = minFuncOptions.optTol;
		final int corrections  = minFuncOptions.Corr;

		final double c1 = 1e-4;
		final double c2 = 0.9;
		final int LS_interp = 2;
		final int LS_multi = 0;

		int exitflag = 0;
		String msg = null;

		// Initialize
		final int p = x0.length;
		double[] d = new double[p];
		x = new double[x0.length];
		for (int i = 0; i < x0.length; i++)
			x[i] = x0[i];
		double t = 1.0d;

		// If necessary, form numerical differentiation functions
		final int funEvalMultiplier = 1;
		final int numDiffType = 0;

		// Evaluate Initial Point

		long time = System.nanoTime();
		final CdrObjectiveResult cdrObjectiveResult = cdr_objective(x, thread);
		f = cdrObjectiveResult.E;
		double[] g = cdrObjectiveResult.G;
		final double[] g_old = new double[g.length];

		int computeHessian = 0;

		int funEvals = 1;

		// Compute optimality of initial point
		double optCond = Double.MIN_VALUE;
		for (int j = 0; j < g.length; j++)
		{
			final double absValue = Math.abs(g[j]);
			if (optCond < absValue)
				optCond = absValue;
		}

		// Exit if initial point is optimal
		if (optCond <= optTol)
		{
		    exitflag=1;
		    msg = "Optimality Condition below optTol";
		    final MinFuncResult minFuncResult = new MinFuncResult();
		    minFuncResult.x = x;
		    minFuncResult.f = f;
		    return minFuncResult;
		}

		double[][] S = new double[p][corrections];
		double[][] Y = new double[p][corrections];
		double[]  YS = new double[corrections];
		int lbfgs_start = 0;
		int lbfgs_end = 0;
		double Hdiag = 1.0;

		// Perform up to a maximum of 'maxIter' descent steps:
		for (int i = 0; i < maxIter; i++)
		{
			// LBFGS
			if (i == 0)
			{
					// Initially use steepest descent direction
					for (int j = 0; j < g.length; j++)
						d[j] = -g[j];
					lbfgs_start = 0;
					lbfgs_end = -1;
					Hdiag = 1.0;
			}
			else
			{
				final double[] gMg_old = new double[g.length];
				for (int j = 0; j < g.length; j++)
					gMg_old[j] = g[j] - g_old[j];

				final double[] tPd = new double[d.length];
				for (int j = 0; j < d.length; j++)
					tPd[j] = t * d[j];

				time = System.nanoTime();
				final LbfgsAddResult lbfgsAddResult = lbfgsAdd(gMg_old, tPd, S, Y, YS, lbfgs_start, lbfgs_end, Hdiag);
				System.out.println("lbfgsAdd took " + ((System.nanoTime()-time)/Math.pow(10, 9))+"s" + ",   lbfgs_start="+lbfgsAddResult.lbfgs_start+", lbfgs_end="+lbfgsAddResult.lbfgs_end);
				S = lbfgsAddResult.S;
				Y = lbfgsAddResult.Y;
				YS = lbfgsAddResult.YS;
				lbfgs_start = lbfgsAddResult.lbfgs_start;
				lbfgs_end = lbfgsAddResult.lbfgs_end;
				Hdiag = lbfgsAddResult.Hdiag;
				final boolean skipped = lbfgsAddResult.skipped;

				time = System.nanoTime();
				d = lbfgsProd(g, S, Y, YS, lbfgs_start, lbfgs_end, Hdiag);
				System.out.println("lbfgsProd took " + ((System.nanoTime()-time)/Math.pow(10, 9))+"s");
			}
			for (int j = 0; j < g.length; j++)
				g_old[j] = g[j];

		    // ****************** COMPUTE STEP LENGTH ************************

		    // Directional Derivative
			double gtd = 0.0;
			for (int j = 0; j < g.length; j++)
				gtd += g[j] * d[j];

		    // Check that progress can be made along direction
		    if (gtd > -progTol)
		    {
		        exitflag = 2;
		        msg = "Directional Derivative below progTol";
		        break;
		    }

		    // Select Initial Guess
		    if (i == 0)
		    {
		    	double sumAbsG = 0.0;
				for (int j = 0; j < g.length; j++)
					sumAbsG += Math.abs(g[j]);
				t = Math.min(1.0, 1.0/sumAbsG);
		    } else {
		        //if (LS_init == 0)
		    	// Newton step
		    	t = 1.0;
		    }
		    double f_old = f;
		    final double gtd_old = gtd;

		    final int Fref = 1;
		    double fr;
		    // Compute reference fr if using non-monotone objective
		    if (Fref == 1)
		    {
		        fr = f;
		    }

		    computeHessian = 0;

		    // Line Search
		    f_old = f;

		    time = System.nanoTime();
		    final WolfeLineSearchResult wolfeLineSearchResult = WolfeLineSearch(x,t,d,f,g,gtd,c1,c2,LS_interp,LS_multi,25,progTol,1, thread);
		    System.out.println("WolfeLineSearch took " + ((System.nanoTime()-time)/Math.pow(10, 9))+"s");
		    t = wolfeLineSearchResult.t;
		    f = wolfeLineSearchResult.f_new;
		    g = wolfeLineSearchResult.g_new;
		    final int LSfunEvals = wolfeLineSearchResult.funEvals;

		    funEvals = funEvals + LSfunEvals;
		    for (int j = 0; j < x.length; j++)
		    	x[j] += t * d[j];

			// Compute Optimality Condition
			optCond = Double.MIN_VALUE;
			for (int j = 0; j < g.length; j++)
			{
				final double absValG = Math.abs(g[j]);
				if (optCond < absValG)
					optCond = absValG;
			}

		    // Check Optimality Condition
		    if (optCond <= optTol)
		    {
		        exitflag=1;
		        msg = "Optimality Condition below optTol";
		        break;
		    }

		    // ******************* Check for lack of progress *******************

			double maxAbsTD = Double.MIN_VALUE;
			for (int j = 0; j < d.length; j++)
			{
				final double absValG = Math.abs(t * d[j]);
				if (maxAbsTD < absValG)
					maxAbsTD = absValG;
			}
		    if (maxAbsTD <= progTol)
		    {
		    	exitflag=2;
		        msg = "Step Size below progTol";
		        break;
			}

		    if (Math.abs(f-f_old) < progTol)
		    {
		        exitflag=2;
		        msg = "Function Value changing by less than progTol";
		        break;
		    }

		    // ******** Check for going over iteration/evaluation limit *******************

		    if (funEvals*funEvalMultiplier >= maxFunEvals)
		    {
		        exitflag = 0;
		        msg = "Reached Maximum Number of Function Evaluations";
		        break;
		    }

		    if (i == maxIter)
		    {
		        exitflag = 0;
		        msg="Reached Maximum Number of Iterations";
		        break;
		    }


		    //rescaleHelper(Arrays.copyOfRange(x, 0, numPixels), "v_"+ITER);
		    //rescaleHelper(Arrays.copyOfRange(x, numPixels, 2*numPixels), "z_"+ITER);
		}

		System.out.println("Msg: " + msg);

	    final MinFuncResult minFuncResult = new MinFuncResult();
	    minFuncResult.x = x;
	    minFuncResult.f = f;
	    return minFuncResult;
	}




	public CdrObjectiveResult cdr_objective(final double[] x, final int thread) throws Exception
	{
		long elapsed = System.nanoTime();

		double E = 0.0;

		// some basic definitions
		//int N_stan = 200;				// the standard number of quantiles used for empirical parameter setting
		final double w           = parameters[thread].CAUCHY_W;  // width of the Cauchy function

		// unpack
		final double[] v_vec = Arrays.copyOfRange(x, 0, numPixels);
		final double[] b_vec = Arrays.copyOfRange(x, numPixels , 2 * numPixels);
		final double zx = x[2 * numPixels];
		final double zy = x[2 * numPixels + 1];

		// move the zero-light point to the pivot space (zx,zy) -> (px,py)
		final double px = zx - PivotShiftX;		// a scalar
		final double[] py = new double[numPixels];
		for ( int i = 0; i < numPixels; i++ )
			py[i] = zy - parameters[thread].PivotShiftY[i];

		//--------------------------------------------------------------------------
		// fitting energy
		// We compute the energy of the fitting term given v,b,zx,zy. We also
		// compute its gradient wrt the random variables.

		final double[] energy_fit  = new double[numPixels];		// accumulates the fit energy
		final double[] deriv_v_fit = new double[numPixels];		// derivative of fit term wrt v
		final double[] deriv_b_fit = new double[numPixels];		// derivative of fit term wrt b

		for ( int pixel = 0; pixel < numPixels; pixel++ )
		{
			int counter = 0;
			for ( final Entry< Float, Short > entry : parameters[thread].histograms[ pixel ].entrySet() )
			{
				final double qi = entry.getKey() - parameters[thread].PivotShiftY[pixel];
				for ( int j = 0; j < entry.getValue(); j++ )
				{
					final int i = counter++;
					final double val = Q[i] * v_vec[pixel] + b_vec[pixel] - qi;
					switch (parameters[thread].MESTIMATOR)
			        {
			            case LS:
		            		energy_fit [pixel] += val * val;
		            		deriv_v_fit[pixel] += Q[i] * val;
		            		deriv_b_fit[pixel] += val;
			                break;

			            case CAUCHY:
		            		energy_fit [pixel] += w*w * Math.log(1 + (val*val) / (w*w)) / 2.0;
		            		deriv_v_fit[pixel] += (Q[i]*val) / (1.0 + (val*val) / (w*w));
		            		deriv_b_fit[pixel] += val / (1.0 + (val*val) / (w*w));
			                break;
			        }
				}
			}
		}

		double E_fit = 0;
		final double[] G_V_fit = new double[numPixels];
		final double[] G_B_fit = new double[numPixels];



		// normalize the contribution from fitting energy term by the number of data
		// points in S (so our balancing of the energy terms is invariant)
		final int data_size_factor = 1;//N_stan/histSize;
		for ( int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++ ) {
			E_fit += energy_fit[pixelIndex];
			G_V_fit[pixelIndex] = deriv_v_fit[pixelIndex] * data_size_factor;	// fit term derivative wrt v
			G_B_fit[pixelIndex] = deriv_b_fit[pixelIndex] * data_size_factor;	// fit term derivative wrt b
		}
		E_fit *= data_size_factor;		// fit term energy

		//E_fit /= numPixels;


		//--------------------------------------------------------------------------
		// spatial regularization of v
		// We compute the energy of the regularization term given v,b,zx,zy. We also
		// compute its gradient wrt the random variables.

		/*// determine the widths we will use for the LoG filter
		long maxSide = Long.MIN_VALUE;
		for ( final long side : originalSize )
			maxSide = Math.max(side, maxSide);
		int max_exp = (int)Math.max(1.0, Math.log(Math.floor(maxSide / 50.0))/Math.log(2.0));

		double[] sigmas = new double[max_exp + 2];
		for (int i = -1; i <= max_exp; i++)
			sigmas[i + 1] = Math.pow(2, i);

		double E_vreg = 0;										// vreg term energy
		double[] deriv_v_vreg = new double[numPixels];			// derivative of vreg term wrt v
		double[] deriv_b_vreg = new double[numPixels];			// derivative of vreg term wrt b

		for ( final double sigma : sigmas )
		{
			final int radius = 3 * (int)Math.ceil(sigma);
			final LaplacianOfGaussian kernelLoG = LaplacianOfGaussian.create( radius, sigma, downsampledSize.length );

			final double[][] halfKernels = new double[ downsampledSize.length ][ radius + 1 ];
			for ( int d = 0; d < halfKernels.length; d++ )
			{
				final long[] mins = new long[ kernelLoG.numDimensions() ], maxs = new long[ kernelLoG.numDimensions() ];
				Arrays.fill( mins, radius );
				Arrays.fill( maxs, radius );
				mins[ d ] = radius;
				maxs[ d ] = kernelLoG.max(d);
				final Cursor< DoubleType > kernel1DCursor = Views.flatIterable( Views.interval( kernelLoG, new FinalInterval( mins, maxs ) ) ).localizingCursor();
				while ( kernel1DCursor.hasNext() )
				{
					kernel1DCursor.fwd();
					halfKernels[ d ][ kernel1DCursor.getIntPosition(d) - radius ] = kernel1DCursor.get().get();
				}
			}

			final ArrayImg< DoubleType, DoubleArray > vImg = ArrayImgs.doubles( v_vec, downsampledSize );
			final ExtendedRandomAccessibleInterval< DoubleType, ArrayImg< DoubleType, DoubleArray > > vImgExtended = Views.extendMirrorSingle( vImg );
			final ArrayImg< DoubleType, DoubleArray > vLoGImg = ArrayImgs.doubles( downsampledSize );
			SeparableSymmetricConvolution.convolve( halfKernels, vImgExtended, vLoGImg, threadPool );

			final double[] vLoG_vec = vLoGImg.update(null).getCurrentStorageArray();
			for ( int pixel = 0; pixel < numPixels; pixel++ )
			{
				vLoG_vec[pixel] /= sigmas.length;
				E_vreg += vLoG_vec[pixel] * vLoG_vec[pixel];
				vLoG_vec[pixel] *= 2;
			}

			final ExtendedRandomAccessibleInterval< DoubleType, ArrayImg< DoubleType, DoubleArray > > vLoGImgExtended = Views.extendMirrorSingle( vLoGImg );
			final ArrayImg< DoubleType, DoubleArray > vLoGLoGImg = ArrayImgs.doubles( downsampledSize );
			SeparableSymmetricConvolution.convolve( halfKernels, vLoGImgExtended, vLoGLoGImg, threadPool );

			final double[] vLoGLoG_vec = vLoGLoGImg.update(null).getCurrentStorageArray();
			for ( int pixel = 0; pixel < numPixels; pixel++ )
				deriv_v_vreg[ pixel ] += vLoGLoG_vec[ pixel ];
		}

		double[] G_V_vreg = deriv_v_vreg;			// vreg term gradient wrt v
		double[] G_B_vreg = deriv_b_vreg;			// vreg term gradient wrt b*/

		// Approximate using DoG
		// determine the widths we will use for the LoG filter
		long maxSide = Long.MIN_VALUE;
		for ( final long side : parameters[thread].currentSize )
			maxSide = Math.max(side, maxSide);
		final int max_exp = (int)Math.max(1.0, Math.log(Math.floor(maxSide / 50.0))/Math.log(2.0));

		final double[] sigmas = new double[max_exp + 2];
		for (int i = -1; i <= max_exp; i++)
			sigmas[i + 1] = Math.pow(2, i);

		double E_vreg = 0;										// vreg term energy
		final double[] deriv_v_vreg = new double[numPixels];			// derivative of vreg term wrt v
		final double[] deriv_b_vreg = new double[numPixels];			// derivative of vreg term wrt b

		for ( final double sigma : sigmas )
		{
			final double[] sigmaSmaller = new double[ parameters[thread].currentSize.length ];
			Arrays.fill( sigmaSmaller, sigma * 0.75 );
			final double[] sigmaLarger = new double[ parameters[thread].currentSize.length ];
			Arrays.fill( sigmaLarger, sigma * 1.25 );

			if ( parameters[thread].currentSize.length == 3 )
			{
				final double coeff = 80.0 / 150.0;
				sigmaSmaller[ 2 ] *= coeff;
				sigmaLarger [ 2 ] *= coeff;
			}

			final ArrayImg< DoubleType, DoubleArray > vImg = ArrayImgs.doubles( v_vec, parameters[thread].currentSize );
			final ExtendedRandomAccessibleInterval< DoubleType, ArrayImg< DoubleType, DoubleArray > > vImgExtended = Views.extendMirrorSingle( vImg );
			final ArrayImg< DoubleType, DoubleArray > vDoGImg = ArrayImgs.doubles( parameters[thread].currentSize );
			DifferenceOfGaussian.DoG( sigmaSmaller, sigmaLarger, vImgExtended, vDoGImg, Executors.newSingleThreadExecutor() /*multithreadedExecutor.getThreadPool()*/ );

			final double[] vDoG_vec = vDoGImg.update(null).getCurrentStorageArray();
			for ( int pixel = 0; pixel < numPixels; pixel++ )
			{
				vDoG_vec[pixel] /= sigmas.length;
				E_vreg += vDoG_vec[pixel] * vDoG_vec[pixel];
				vDoG_vec[pixel] *= 2;
			}

			final ExtendedRandomAccessibleInterval< DoubleType, ArrayImg< DoubleType, DoubleArray > > vDoGImgExtended = Views.extendMirrorSingle( vDoGImg );
			final ArrayImg< DoubleType, DoubleArray > vDoGDoGImg = ArrayImgs.doubles( parameters[thread].currentSize );
			DifferenceOfGaussian.DoG( sigmaSmaller, sigmaLarger, vDoGImgExtended, vDoGDoGImg, Executors.newSingleThreadExecutor() /* multithreadedExecutor.getThreadPool() */ );

			final double[] vDoGDoG_vec = vDoGDoGImg.update(null).getCurrentStorageArray();
			for ( int pixel = 0; pixel < numPixels; pixel++ )
				deriv_v_vreg[ pixel ] += vDoGDoG_vec[ pixel ];
		}
		//E_vreg /= numPixels;

		final double[] G_V_vreg = deriv_v_vreg;			// vreg term gradient wrt v
		final double[] G_B_vreg = deriv_b_vreg;			// vreg term gradient wrt b


		//--------------------------------------------------------------------------
		// The ZERO-LIGHT term
		// We compute the energy of the zero-light term given v,b,zx,zy. We also
		// compute its gradient wrt the random variables.

		final double[] residual = new double[numPixels];
		for (int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++)
			residual[pixelIndex] = v_vec[pixelIndex] * px + b_vec[pixelIndex] - py[pixelIndex];

		final double[] deriv_v_zero = new double[numPixels];
		final double[] deriv_b_zero = new double[numPixels];
		double deriv_zx_zero = 0.0;
		double deriv_zy_zero = 0.0;
		for (int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++) {
			final double val = b_vec[pixelIndex] + v_vec[pixelIndex] * px - py[pixelIndex];
			deriv_v_zero[pixelIndex] = 2 * px * val;
			deriv_b_zero[pixelIndex] = 2 * val;
			deriv_zx_zero += 2 * v_vec[pixelIndex] * val;
			deriv_zy_zero += -2 * val;
		}
		//deriv_zx_zero /= numPixels;
		//deriv_zy_zero /= numPixels;

		double E_zero = 0;	// zero light term energy
		for (int pixelIndex = 0; pixelIndex < numPixels; pixelIndex++)
			E_zero += residual[pixelIndex] * residual[pixelIndex];
		//E_zero /= numPixels;

		final double[] G_V_zero = deriv_v_zero;		// zero light term gradient wrt v
		final double[] G_B_zero = deriv_b_zero;		// zero light term gradient wrt b
		final double G_ZX_zero = deriv_zx_zero;		// zero light term gradient wrt zx
		final double G_ZY_zero = deriv_zy_zero;		// zero light term gradient wrt zy
		//--------------------------------------------------------------------------

		//--------------------------------------------------------------------------
		// The BARRIER term
		// We compute the energy of the barrier term given v,b,zx,zy. We also
		// compute its gradient wrt the random variables.

		final double Q_UPPER_LIMIT = parameters[thread].ZMAX;	// upper limit - transition from zero energy to quadratic increase
		final double Q_LOWER_LIMIT = parameters[thread].ZMIN;	// lower limit - transition from quadratic to zero energy
		final double Q_RATE = 0.001;			// rate of increase in energy

		// barrier term gradients and energy components
		double[] barrierResult = theBarrierFunction(zx, Q_LOWER_LIMIT, Q_UPPER_LIMIT, Q_RATE);
		final double E_barr_xc = barrierResult[0];
		final double G_ZX_barr = barrierResult[1];

		barrierResult = theBarrierFunction(zy, Q_LOWER_LIMIT, Q_UPPER_LIMIT, Q_RATE);
		final double E_barr_yc = barrierResult[0];
		final double G_ZY_barr = barrierResult[1];

		final double E_barr = E_barr_xc + E_barr_yc;		// barrier term energy

		//--------------------------------------------------------------------------
		if ( parameters[thread].TERMSFLAG == 0)
			System.out.println("Cost function components: " + Arrays.toString(new double[] { E_fit } ));
		else
			System.out.println("Cost function components: " + Arrays.toString(new double[] { E_fit, parameters[thread].LAMBDA_VREG*E_vreg, parameters[thread].LAMBDA_ZERO*E_zero, parameters[thread].LAMBDA_BARR*E_barr } ));
		//--------------------------------------------------------------------------
		// The total energy
		// Find the sum of all components of the energy. TERMSFLAG switches on and
		// off different components of the energy.
		String term_str = "";
		switch (parameters[thread].TERMSFLAG) {
		    case 0:
		        E = E_fit;
		        term_str = "fitting only";
		        break;
		    case 1:
		        E = E_fit + parameters[thread].LAMBDA_VREG*E_vreg + parameters[thread].LAMBDA_ZERO*E_zero + parameters[thread].LAMBDA_BARR*E_barr;
		        term_str = "all terms";
		        break;
		}
		//--------------------------------------------------------------------------

		//--------------------------------------------------------------------------
		// The gradient of the energy
		final double[] G_V;
		final double[] G_B;
		double G_ZX = 0;
		double G_ZY = 0;

		switch (parameters[thread].TERMSFLAG) {
		    case 0:
		        G_V = G_V_fit;
		        G_B = G_B_fit;
		        G_ZX = 0;
		        G_ZY = 0;
		        break;
		    case 1:
		    	for (int i = 0; i < G_V_fit.length; i++) {
		    		G_V_fit[i] = G_V_fit[i] + parameters[thread].LAMBDA_VREG*G_V_vreg[i] + parameters[thread].LAMBDA_ZERO*G_V_zero[i];
		    		G_B_fit[i] = G_B_fit[i] + parameters[thread].LAMBDA_VREG*G_B_vreg[i] + parameters[thread].LAMBDA_ZERO*G_B_zero[i];
		    	}
		    	G_V = G_V_fit;
		    	G_B = G_B_fit;
		        G_ZX = parameters[thread].LAMBDA_ZERO*G_ZX_zero + parameters[thread].LAMBDA_BARR*G_ZX_barr;
		        G_ZY = parameters[thread].LAMBDA_ZERO*G_ZY_zero + parameters[thread].LAMBDA_BARR*G_ZY_barr;
		        break;
	        default:
	        	G_V = null;
	        	G_B = null;
		}

		// vectorize the gradient
		final double[] G = new double[x.length];

		int pG = 0;
		for (int i = 0; i < G_V.length; i++)
			G[pG++] = G_V[i];
		for (int i = 0; i < G_B.length; i++)
			G[pG++] = G_B[i];
		G[pG++] = G_ZX;
		G[pG++] = G_ZY;

		//--------------------------------------------------------------------------

		/*double[] mlX2 = readFromCSVFile(PathIn + "csv\\g_ml", 1, 2 * S_C * S_R + 2);

		double mmax = Double.MIN_VALUE;
		double mmin = Double.MAX_VALUE;

		for (int t = 0; t < mlX2.length; t++) {
			mlX2[t] -= G[t];
			if (mmax < mlX2[t])
				mmax = mlX2[t];
			if (mmin > mlX2[t])
				mmin = mlX2[t];
		}*/

		//writeToCSVFile(PathIn + "csv\\x_ml_res", mlX2, 1, n);

		System.out.println("thread #"+thread+":  " +String.format("iter = %d  %s %s    zx,zy=(%1.2f,%1.2f)    E=%g", parameters[thread].ITER, parameters[thread].MESTIMATOR, term_str, zx,zy, E));
		//System.out.println(String.format("min = %f, max = %f", mmin, mmax));
		parameters[thread].ITER++;

		final CdrObjectiveResult result = new CdrObjectiveResult();
		result.E = E;
		result.G = G;

		elapsed = System.nanoTime() - elapsed;

		return result;
	}



	private LbfgsAddResult lbfgsAdd(final double[] y, final double[] s, final double[][] S, final double[][] Y, final double[] YS, int lbfgs_start, int lbfgs_end, double Hdiag) throws InterruptedException, ExecutionException
	{
		double ys = 0.0;
		for (int j = 0; j < y.length; j++)
			ys += y[j] * s[j];
		boolean skipped = false;
		final int corrections = S[0].length;
		if (ys > 1e-10d)
		{
			if (lbfgs_end < corrections - 1)
			{
				lbfgs_end = lbfgs_end+1;
				if (lbfgs_start != 0)
				{
					if (lbfgs_start == corrections - 1)
						lbfgs_start = 0;
					else
						lbfgs_start = lbfgs_start+1;
				}
			} else {
				lbfgs_start = Math.min(1, corrections);
				lbfgs_end = 0;
			}

			final int lbfgs_endFinal = lbfgs_end;
			for (int j = 0; j < s.length; j++)
			{
				S[j][lbfgs_end] = s[j];
				Y[j][lbfgs_end] = y[j];
			}
			YS[lbfgs_end] = ys;

			// Update scale of initial Hessian approximation
			double yy = 0.0;
			for (int j = 0; j < y.length; j++)
				yy += y[j]*y[j];
			Hdiag = ys/yy;
		} else {
			skipped = false;
		}

		final LbfgsAddResult lbfgsAddResult = new LbfgsAddResult();
		lbfgsAddResult.S = S;
		lbfgsAddResult.Y = Y;
		lbfgsAddResult.YS = YS;
		lbfgsAddResult.lbfgs_start = lbfgs_start;
		lbfgsAddResult.lbfgs_end = lbfgs_end;
		lbfgsAddResult.Hdiag = Hdiag;
		lbfgsAddResult.skipped = skipped;

		return lbfgsAddResult;
	}


	/*private double[] lbfgsProd(double[] g, double[][] S, double[][] Y, double[] YS, int lbfgs_start, int lbfgs_end, double Hdiag) throws Exception
	{
		// BFGS Search Direction
		// This function returns the (L-BFGS) approximate inverse Hessian,
		// multiplied by the negative gradient

		// Set up indexing
		int nVars = S.length;
		int maxCorrections = S[0].length;
		int nCor;
		int[] ind;
		if (lbfgs_start == 0)
		{
			ind = new int[lbfgs_end];
			for (int j = 0; j < ind.length; j++)
				ind[j] = j;
			nCor = lbfgs_end-lbfgs_start+1;
		} else {
			ind = new int[maxCorrections];
			for (int j = lbfgs_start; j < maxCorrections; j++)
				ind[j - lbfgs_start] = j;
			for (int j = 0; j <= lbfgs_end; j++)
				ind[j + maxCorrections - lbfgs_start] = j;
			nCor = maxCorrections;
		}

		double[] al = new double[nCor];
		double[] be = new double[nCor];

		double[] d = new double[g.length];
		multithreadedExecutor.run( j -> d[j] = -g[j], g.length );

		for (int j = 0; j < ind.length; j++)
		{
			final int i = ind[ind.length-j-1];
			al[i] = multithreadedExecutor.sum( k -> (S[k][i] * d[k]) / YS[i], S.length );
			multithreadedExecutor.run( k -> d[k] -= al[i] * Y[k][i], d.length );
		}

		// Multiply by Initial Hessian
		multithreadedExecutor.run( j -> d[j] = Hdiag * d[j], d.length );

		for (int ii = 0; ii < ind.length; ii++)
		{
			final int i = ii;
			be[ind[i]] = multithreadedExecutor.sum( j -> Y[j][ind[i]] * d[j], Y.length ) / YS[ind[i]];
			multithreadedExecutor.run( j -> d[j] += S[j][ind[i]] * (al[ind[i]] - be[ind[i]]), d.length );
		}

		return d;
	}*/

	private double[] lbfgsProd(final double[] g, final double[][] S, final double[][] Y, final double[] YS, final int lbfgs_start, final int lbfgs_end, final double Hdiag)
	{
		// BFGS Search Direction
		// This function returns the (L-BFGS) approximate inverse Hessian,
		// multiplied by the negative gradient

		// Set up indexing
		final int nVars = S.length;
		final int maxCorrections = S[0].length;
		int nCor;
		int[] ind;
		if (lbfgs_start == 0)
		{
			ind = new int[lbfgs_end];
			for (int j = 0; j < ind.length; j++)
				ind[j] = j;
			nCor = lbfgs_end-lbfgs_start+1;
		} else {
			ind = new int[maxCorrections];
			for (int j = lbfgs_start; j < maxCorrections; j++)
				ind[j - lbfgs_start] = j;
			for (int j = 0; j <= lbfgs_end; j++)
				ind[j + maxCorrections - lbfgs_start] = j;
			nCor = maxCorrections;
		}

		final double[] al = new double[nCor];
		final double[] be = new double[nCor];

		final double[] d = new double[g.length];
		for (int j = 0; j < g.length; j++)
			d[j] = -g[j];
		for (int j = 0; j < ind.length; j++)
		{
			final int i = ind[ind.length-j-1];
			double sumSD = 0.0;
			for (int k = 0; k < S.length; k++)
				sumSD += (S[k][i] * d[k]) / YS[i];
			al[i] = sumSD;

			for (int k = 0; k < d.length; k++)
				d[k] -= al[i] * Y[k][i];
		}

		// Multiply by Initial Hessian
		for (int j = 0; j < d.length; j++)
			d[j] = Hdiag * d[j];

		for (int i = 0; i < ind.length; i++)
		{
			double sumYd = 0.0;
			for (int j = 0; j < Y.length; j++)
				sumYd += Y[j][ind[i]] * d[j];
			be[ind[i]] = sumYd / YS[ind[i]];

			for (int j = 0; j < d.length; j++)
				d[j] += S[j][ind[i]] * (al[ind[i]] - be[ind[i]]);
		}
		return d;
	}


	private WolfeLineSearchResult WolfeLineSearch(final double[] x, double t, final double[] d, final double f, final double[] g, final double gtd, final double c1, final double c2,
			final int LS_interp, final int LS_multi, final int maxLS, final double progTol, final int saveHessianComp, final int thread) throws Exception
	{


		double[] x2 = new double[x.length];
		for (int j = 0; j < x.length; j++)
			x2[j] = x[j] + t * d[j];
		CdrObjectiveResult cdrObjectiveResult = cdr_objective(x2, thread);
		double f_new = cdrObjectiveResult.E;
		double[] g_new = cdrObjectiveResult.G;
		int funEvals = 1;

		double gtd_new = 0.0;
		for (int j = 0; j < g.length; j++)
			gtd_new += g_new[j] * d[j];

		// Bracket an Interval containing a point satisfying the
		// Wolfe criteria

		int LSiter = 0;
		double t_prev = 0.0;
		double f_prev = f;
		final double[] g_prev = new double[g.length];
		for (int j = 0; j < g.length; j++)
			g_prev[j] = g[j];
		double gtd_prev = gtd;
		double nrmD = Double.MIN_VALUE;
		for (int j = 0; j < d.length; j++)
		{
			final double absValD = Math.abs(d[j]);
			if (nrmD < absValD)
				nrmD = absValD;
		}
		boolean done = false;

		int bracketSize = 0;
		final double[] bracket = new double[2];
		final double[] bracketFval = new double[2];
		final double[] bracketGval = new double[2 * x.length];

		while (LSiter < maxLS)
		{
		    if (f_new > f + c1*t*gtd || (LSiter > 1 && f_new >= f_prev))
		    {
		    	bracketSize = 2;
		    	bracket[0] = t_prev; bracket[1] = t;
		    	bracketFval[0] = f_prev; bracketFval[1] = f_new;
		    	for (int j = 0; j < g_prev.length; j++)
		    		bracketGval[j] = g_prev[j];
		    	for (int j = 0; j < g_new.length; j++)
		    		bracketGval[g_prev.length + j] = g_new[j];
		    	break;
		    }
		    else if (Math.abs(gtd_new) <= -c2*gtd)
		    {
		    	bracketSize = 1;
		        bracket[0] = t;
		        bracketFval[0] = f_new;
		    	for (int j = 0; j < g_new.length; j++)
		    		bracketGval[j] = g_new[j];
		        done = true;
		        break;
		    }
		    else if (gtd_new >= 0)
		    {
		    	bracketSize = 2;
		    	bracket[0] = t_prev; bracket[1] = t;
		    	bracketFval[0] = f_prev; bracketFval[1] = f_new;
		    	for (int j = 0; j < g_prev.length; j++)
		    		bracketGval[j] = g_prev[j];
		    	for (int j = 0; j < g_new.length; j++)
		    		bracketGval[g_prev.length + j] = g_new[j];
		    	break;
		    }

		    final double temp = t_prev;
		    t_prev = t;
		    final double minStep = t + 0.01*(t-temp);
		    final double maxStep = t*10;
		    if (LS_interp <= 1)
		    	t = maxStep;
		    else if (LS_interp == 2)
		    {
		    	final double[] points = new double[2*3];
		    	points[0] = temp; points[1] = f_prev; points[2] = gtd_prev;
		    	points[3] = t;    points[4] = f_new;  points[5] = gtd_new;
		    	t = polyinterp(points, minStep, maxStep);
		    }

		    f_prev = f_new;
		    for (int j = 0; j < g_new.length; j++)
		    	g_prev[j] = g_new[j];
		    gtd_prev = gtd_new;

			x2 = new double[x.length];
			for (int j = 0; j < x.length; j++)
				x2[j] = x[j] + t * d[j];
		    cdrObjectiveResult = cdr_objective(x2, thread);
			f_new = cdrObjectiveResult.E;
			g_new = cdrObjectiveResult.G;
			funEvals++;
			gtd_new = 0.0;
			for (int j = 0; j < g.length; j++)
				gtd_new += g_new[j] * d[j];
			LSiter++;
		}

		if (LSiter == maxLS)
		{
	    	bracketSize = 2;
	    	bracket[0] = 0; bracket[1] = t;
	    	bracketFval[0] = f; bracketFval[1] = f_new;
	    	for (int j = 0; j < g.length; j++)
	    		bracketGval[j] = g[j];
	    	for (int j = 0; j < g_new.length; j++)
	    		bracketGval[g.length + j] = g_new[j];
		}

		// Zoom Phase

		// We now either have a point satisfying the criteria, or a bracket
		// surrounding a point satisfying the criteria
		// Refine the bracket until we find a point satisfying the criteria
		boolean insufProgress = false;
		//int Tpos = 1;
		//int LOposRemoved = 0;
		int LOpos;
		int HIpos;
		double f_LO;

		while (!done && LSiter < maxLS)
		{
		    // Find High and Low Points in bracket
		    //[f_LO LOpos] = min(bracketFval);
		    //HIpos = -LOpos + 3;

			if (bracketSize < 2)
			{
				f_LO = bracketFval[0];
				LOpos = 0; HIpos = 1;
			}
			else
			{
				if (bracketFval[0] <= bracketFval[1])
				{
					f_LO = bracketFval[0];
					LOpos = 0; HIpos = 1;
				} else {
					f_LO = bracketFval[1];
					LOpos = 1; HIpos = 0;
				}
			}

			// LS_interp == 2
			//t = polyinterp([bracket(1) bracketFval(1) bracketGval(:,1)'*d
			//            bracket(2) bracketFval(2) bracketGval(:,2)'*d],doPlot);

		    {
				double val0 = 0.0;
				for (int j = 0; j < g.length; j++)
					val0 += bracketGval[j] * d[j];

				double val1 = 0.0;
				for (int j = 0; j < g.length; j++)
					val1 += bracketGval[g.length + j] * d[j];

		    	final double[] points = new double[2*3];
		    	points[0] = bracket[0]; points[1] = bracketFval[0]; points[2] = val0;
		    	points[3] = bracket[1]; points[4] = bracketFval[1];  points[5] = val1;
		    	t = polyinterp(points, null, null);
		    }

		    // Test that we are making sufficient progress
		    if (Math.min(Math.max(bracket[0], bracket[1])-t,t-Math.min(bracket[0], bracket[1]))/(Math.max(bracket[0], bracket[1])-Math.min(bracket[0], bracket[1])) < 0.1)
		    {
		        if (insufProgress || t>=Math.max(bracket[0], bracket[1]) || t <= Math.min(bracket[0], bracket[1]))
		        {
		            if (Math.abs(t-Math.max(bracket[0], bracket[1])) < Math.abs(t-Math.min(bracket[0], bracket[1])))
		            {
		                t = Math.max(bracket[0], bracket[1])-0.1*(Math.max(bracket[0], bracket[1])-Math.min(bracket[0], bracket[1]));
		            } else {
		                t = Math.min(bracket[0], bracket[1])+0.1*(Math.max(bracket[0], bracket[1])-Math.min(bracket[0], bracket[1]));
		            }
		            insufProgress = false;
		        } else {
		            insufProgress = true;
		        }
		    } else {
		        insufProgress = false;
		    }

		    // Evaluate new point
			x2 = new double[x.length];
			for (int j = 0; j < x.length; j++)
				x2[j] = x[j] + t * d[j];
		    cdrObjectiveResult = cdr_objective(x2, thread);
			f_new = cdrObjectiveResult.E;
			g_new = cdrObjectiveResult.G;
			funEvals++;
			gtd_new = 0.0;
			for (int j = 0; j < g.length; j++)
				gtd_new += g_new[j] * d[j];
			LSiter++;

			final boolean armijo = f_new < f + c1*t*gtd;
		    if (!armijo || f_new >= f_LO)
		    {
		        // Armijo condition not satisfied or not lower than lowest point
		        bracket[HIpos] = t;
		        bracketFval[HIpos] = f_new;
		    	for (int j = 0; j < g.length; j++)
		    		bracketGval[g.length * HIpos + j] = g_new[j];
		        //Tpos = HIpos;
		    } else {
		        if (Math.abs(gtd_new) <= - c2*gtd)
		        {
		            // Wolfe conditions satisfied
		            done = true;
		        } else if (gtd_new*(bracket[HIpos]-bracket[LOpos]) >= 0)
		        {
		            // Old HI becomes new LO
		            bracket[HIpos] = bracket[LOpos];
		            bracketFval[HIpos] = bracketFval[LOpos];
			    	for (int j = 0; j < g.length; j++)
			    		bracketGval[g.length * HIpos + j] = bracketGval[g.length * LOpos + j];
		        }
		        // New point becomes new LO
		        bracket[LOpos] = t;
		        bracketFval[LOpos] = f_new;
		    	for (int j = 0; j < g.length; j++)
		    		bracketGval[g.length * LOpos + j] = g_new[j];
		        //Tpos = LOpos;
		    }

		    if (!done && Math.abs(bracket[0]-bracket[1])*nrmD < progTol)
		    	break;
		}

		if (bracketSize < 2)
		{
			f_LO = bracketFval[0];
			LOpos = 0; HIpos = 1;
		}
		else
		{
			if (bracketFval[0] <= bracketFval[1])
			{
				f_LO = bracketFval[0];
				LOpos = 0; HIpos = 1;
			} else {
				f_LO = bracketFval[1];
				LOpos = 1; HIpos = 0;
			}
		}

		t = bracket[LOpos];
		f_new = bracketFval[LOpos];
    	for (int j = 0; j < g.length; j++)
    		g_new[j] = bracketGval[g.length * LOpos + j];

    	final WolfeLineSearchResult wolfeLineSearchResult = new WolfeLineSearchResult();
    	wolfeLineSearchResult.t = t;
    	wolfeLineSearchResult.f_new = f_new;
    	wolfeLineSearchResult.g_new = g_new;
    	wolfeLineSearchResult.funEvals = funEvals;
    	return wolfeLineSearchResult;
	}




	private double polyinterp(final double[] points, Double xminBound, Double xmaxBound)
	{
		final double xmin = Math.min(points[0], points[3]);
		final double xmax = Math.max(points[0], points[3]);

		// Compute Bounds of Interpolation Area
		if (xminBound == null)
		    xminBound = xmin;
		if (xmaxBound == null)
		    xmaxBound = xmax;

		// Code for most common case:
		//   - cubic interpolation of 2 points
		//       w/ function and derivative values for both

		// Solution in this case (where x2 is the farthest point):
		// d1 = g1 + g2 - 3*(f1-f2)/(x1-x2);
		// d2 = sqrt(d1^2 - g1*g2);
		// minPos = x2 - (x2 - x1)*((g2 + d2 - d1)/(g2 - g1 + 2*d2));
		// t_new = min(max(minPos,x1),x2);

		int minPos;
		int notMinPos;
		if (points[0] < points[3])
		{
			minPos = 0;
		} else {
			minPos = 1;
		}
		notMinPos = (1 - minPos) * 3;
		final double d1 = points[minPos + 2] + points[notMinPos + 2] - 3*(points[minPos + 1]-points[notMinPos + 1])/(points[minPos]-points[notMinPos]);
		final double d2_2 = d1*d1 - points[minPos+2]*points[notMinPos+2];

		if (d2_2 >= 0.0)
		{
		    final double d2 = Math.sqrt(d2_2);
	        final double t = points[notMinPos] - (points[notMinPos] - points[minPos])*((points[notMinPos + 2] + d2 - d1)/(points[notMinPos + 2] - points[minPos + 2] + 2*d2));
	        return Math.min(Math.max(t, xminBound), xmaxBound);
		} else {
			return (xmaxBound+xminBound)/2.0;
		}
	}



	private double[] theBarrierFunction(final double x, final double xmin, final double xmax, final double width)
	{
		// the barrier function has a well shape. It has quadratically increasing
		// energy below xmin, zero energy between xmin and xmax, and quadratically
		// increasing energy above xmax. The rate of increase is determined by width

		final double[] result = new double[] {0.0, 0.0}; // E G

		final double xl1 = xmin;
		final double xl2 = xl1 + width;

		final double xh2 = xmax;
		final double xh1 = xh2 - width;

		if (x <= xl1) {
			result[0] = ((x-xl2)/(xl2-xl1)) * ((x-xl2)/(xl2-xl1));
			result[1] = (2*(x-xl2)) / ((xl2-xl1)*(xl2-xl1));
		}
		else if ((x >= xl1) && (x <= xl2)) {
			result[0] = ((x-xl2)/(xl2-xl1))*((x-xl2)/(xl2-xl1));
			result[1] = (2*(x-xl2))  / ((xl2-xl1)*(xl2-xl1));
		}
		else if ((x > xl2) && (x < xh1)) {
			result[0] = 0;
			result[1] = 0;
		}
		else if ((x >= xh1) && (x < xh2)) {
			result[0] = ((x-xh1)/(xh2-xh1))*((x-xh1)/(xh2-xh1));
			result[1] = (2*(x-xh1))  / ((xh2-xh1)*(xh2-xh1));
		}
		else {
			result[0] = ((x-xh1)/(xh2-xh1))*((x-xh1)/(xh2-xh1));
			result[1] = (2*(x-xh1))  / ((xh2-xh1)*(xh2-xh1));
		}

		return result;
	}


	// computes the mean standard error of the regression
	private double computeStandardError(final double[] v, final double[] b, final int thread) {
		// initialize a matrix to contain all the standard error calculations
		final double[] se = new double[numPixels];

		// compute the standard error at each location
		for ( int pixel = 0; pixel < numPixels; pixel++ )
		{
	        double fitval, residual, sum_residuals2 = 0;
	        int counter = 0;
	        for ( final Entry< Float, Short > entry : parameters[thread].histograms[pixel].entrySet() )
	        {
	        	final double qi = entry.getKey() - parameters[thread].PivotShiftY[pixel];
				for ( int j = 0; j < entry.getValue(); j++ )
				{
		        	fitval = b[pixel] + Q[counter++] * v[pixel];
		        	residual = qi - fitval;
		        	sum_residuals2 += residual * residual;
				}
	        }

	        se[pixel] = Math.sqrt(sum_residuals2 / (N-2));
		}
		return mean(se);
	}

	private double mean(final double[] a)
	{
		double sum = 0;
	    for (int i = 0; i < a.length; i++)
	        sum += a[i];
	    return sum / a.length;
	}


	private long[] getMinSize( final TileInfo[] tiles )
	{
		final long[] minSize = tiles[ 0 ].getSize().clone();
		for ( final TileInfo tile : tiles )
			for ( int d = 0; d < minSize.length; d++ )
				if (minSize[ d ] > tile.getSize( d ))
					minSize[ d ] = tile.getSize( d );
		return minSize;
	}


	class MinFuncOptions {
		public int maxIter;			// max iterations for optimization
		public int MaxFunEvals;		// max evaluations of objective function
		public double progTol;		// progress tolerance
		public double optTol;		// optimality tolerance
		public int Corr;			// number of corrections to store in memory
	}

	class MinFuncResult {
		public double[] x;
		public double f;
	}

	class CdrObjectiveResult {
		public double E;
		public double[] G;
	}

	class LbfgsAddResult {
		public double[][] S;
		public double[][] Y;
		public double[] YS;
		public int lbfgs_start;
		public int lbfgs_end;
		public double Hdiag;
		public boolean skipped;
	}

	class WolfeLineSearchResult {
		public double t;
		public double f_new;
		public double[] g_new;
		public int funEvals;
	}
}
