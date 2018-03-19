package org.janelia.stitching;

import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assert;
import org.junit.Test;

import mpicbg.imglib.custom.OffsetConverter;
import net.imglib2.Interval;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.IntervalsHelper;
import net.imglib2.util.IntervalsNullable;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class SplitTileOperationsTest
{
	private static final double EPSILON = 1e-9;

	@Test
	public void splitTilesIntoBoxesTest()
	{
		final TileInfo tile = new TileInfo( 3 );
		tile.setPosition( new double[] { 100, 200, 300 } );
		tile.setSize( new long[] { 50, 60, 70 } );

		final List< TileInfo > tileBoxes = SplitTileOperations.splitTilesIntoBoxes( new TileInfo[] { tile }, new int[] { 2, 2, 2 } );
		Assert.assertEquals( 8, tileBoxes.size() );

		// test mins
		final Map< Long, long[] > actualMins = new TreeMap<>();
		for ( final TileInfo tileBox : tileBoxes )
		{
			final long[] mins = Intervals.minAsLongArray( tileBox.getBoundaries() );
			final long key = IntervalIndexer.positionToIndex( mins, tile.getSize() );
			Assert.assertNull( actualMins.put( key, mins ) );
		}
		final long[][] expectedMinsArray = new long[][] {
			new long[] { 0, 0, 0 },
			new long[] { 25, 0, 0 },
			new long[] { 0, 30, 0 },
			new long[] { 25, 30, 0 },
			new long[] { 0, 0, 35 },
			new long[] { 25, 0, 35 },
			new long[] { 0, 30, 35 },
			new long[] { 25, 30, 35 },
		};
		for ( final long[] expectedMin : expectedMinsArray )
		{
			final long key = IntervalIndexer.positionToIndex( expectedMin, tile.getSize() );
			Assert.assertArrayEquals( expectedMin, actualMins.get( key ) );
		}

		// test dimensions
		for ( final TileInfo tileBox : tileBoxes )
			Assert.assertArrayEquals( new long[] { 25, 30, 35 }, Intervals.dimensionsAsLongArray( tileBox.getBoundaries() ) );

		// check that reference to the original tile is preserved
		for ( final TileInfo tileBox : tileBoxes )
			Assert.assertEquals( tile, tileBox.getOriginalTile() );
	}

	@Test
	public void testOverlaps() throws PipelineExecutionException
	{
		final TileInfo fixedTile = new TileInfo( 2 );
		fixedTile.setIndex( 0 );
		fixedTile.setPosition( new double[] { 50, 50 } );
		fixedTile.setSize( new long[] { 20, 20 } );

		final TileInfo movingTile = new TileInfo( 2 );
		movingTile.setIndex( 1 );
		movingTile.setPosition( new double[] { 46, 32 } );
		movingTile.setSize( new long[] { 20, 20 } );

		final List< TileInfo > tileBoxes = SplitTileOperations.splitTilesIntoBoxes( new TileInfo[] { fixedTile,  movingTile }, new int[] { 2, 2 } );
		Assert.assertEquals( 8, tileBoxes.size() );

		final TilePair tileBoxPair;
		{
			TileInfo fixedTileBox = null;
			TileInfo movingTileBox = null;
			for ( final TileInfo tileBox : tileBoxes )
			{
				if ( tileBox.getOriginalTile().getIndex().intValue() == 0 )
				{
					// top-right box of the fixed tile
					if ( Math.round( tileBox.getPosition( 0 ) ) == 10 && Math.round( tileBox.getPosition( 1 ) ) == 0 )
					{
						if ( fixedTileBox != null )
							fail();
						fixedTileBox = tileBox;
					}
				}
				else if ( tileBox.getOriginalTile().getIndex().intValue() == 1 )
				{
					// bottom-right box of the moving tile
					if ( Math.round( tileBox.getPosition( 0 ) ) == 10 && Math.round( tileBox.getPosition( 1 ) ) == 10 )
					{
						if ( movingTileBox != null )
							fail();
						movingTileBox = tileBox;
					}
				}
				else
				{
					fail();
				}
			}
			if ( fixedTileBox == null || movingTileBox == null )
				fail();
			tileBoxPair = new TilePair( fixedTileBox, movingTileBox );
		}
		Assert.assertArrayEquals( new long[] { 10, 0 }, Intervals.minAsLongArray( tileBoxPair.getA().getBoundaries() ) );
		Assert.assertArrayEquals( new long[] { 10, 10 }, Intervals.minAsLongArray( tileBoxPair.getB().getBoundaries() ) );

		final Pair< Interval, Interval > intervalsInGlobalSpace = new ValuePair<>(
				IntervalsHelper.translate( tileBoxPair.getA().getBoundaries(), Intervals.minAsLongArray( tileBoxPair.getA().getOriginalTile().getBoundaries() ) ),
				IntervalsHelper.translate( tileBoxPair.getB().getBoundaries(), Intervals.minAsLongArray( tileBoxPair.getB().getOriginalTile().getBoundaries() ) )
			);

		final Pair< Interval, Interval > intervalsInFixedBoxSpace = SplitTileOperations.globalToFixedBoxSpace( intervalsInGlobalSpace );

		Assert.assertArrayEquals( new long[] { 60, 50 }, Intervals.minAsLongArray( IntervalsNullable.intersect( intervalsInGlobalSpace.getA(), intervalsInGlobalSpace.getB() ) ) );
		Assert.assertArrayEquals( new long[] { 65, 51 }, Intervals.maxAsLongArray( IntervalsNullable.intersect( intervalsInGlobalSpace.getA(), intervalsInGlobalSpace.getB() ) ) );

		Assert.assertArrayEquals( new long[] { 0, 0 }, Intervals.minAsLongArray( IntervalsNullable.intersect( intervalsInFixedBoxSpace.getA(), intervalsInFixedBoxSpace.getB() ) ) );
		Assert.assertArrayEquals( new long[] { 5, 1 }, Intervals.maxAsLongArray( IntervalsNullable.intersect( intervalsInFixedBoxSpace.getA(), intervalsInFixedBoxSpace.getB() ) ) );

		Assert.assertArrayEquals( new long[] { 0, 0 }, Intervals.minAsLongArray( intervalsInFixedBoxSpace.getA() ) );
		Assert.assertArrayEquals( new long[] { -4, -8 }, Intervals.minAsLongArray( intervalsInFixedBoxSpace.getB() ) );

		final double ellipseRadius = 2;
		final double[] offsetsMeanValues = Intervals.minAsDoubleArray( intervalsInFixedBoxSpace.getB() );
		final double[][] offsetsCovarianceMatrix = new double[][] { new double[] { 1, 0, 0 }, new double[] { 0, 1, 0 }, new double[] { 0, 0, 1 } };
		final SearchRadius searchRadius = new SearchRadius( ellipseRadius, offsetsMeanValues, offsetsCovarianceMatrix );

		final Pair< Interval, Interval > adjustedOverlaps = SplitTileOperations.getAdjustedOverlapIntervals( intervalsInFixedBoxSpace, searchRadius );

		Assert.assertArrayEquals( new long[] { 0, 0 }, Intervals.minAsLongArray( adjustedOverlaps.getA() ) );
		Assert.assertArrayEquals( new long[] { 7, 3 }, Intervals.maxAsLongArray( adjustedOverlaps.getA() ) );

		Assert.assertArrayEquals( new long[] { 2, 6 }, Intervals.minAsLongArray( adjustedOverlaps.getB() ) );
		Assert.assertArrayEquals( new long[] { 9, 9 }, Intervals.maxAsLongArray( adjustedOverlaps.getB() ) );

		final Pair< Interval, Interval > adjustedOverlapsInFullTile = SplitTileOperations.getOverlapsInFullTile( tileBoxPair, adjustedOverlaps );

		Assert.assertArrayEquals( new long[] { 10, 0 }, Intervals.minAsLongArray( adjustedOverlapsInFullTile.getA() ) );
		Assert.assertArrayEquals( new long[] { 17, 3 }, Intervals.maxAsLongArray( adjustedOverlapsInFullTile.getA() ) );

		Assert.assertArrayEquals( new long[] { 12, 16 }, Intervals.minAsLongArray( adjustedOverlapsInFullTile.getB() ) );
		Assert.assertArrayEquals( new long[] { 19, 19 }, Intervals.maxAsLongArray( adjustedOverlapsInFullTile.getB() ) );

		final OffsetConverter offsetConverter = SplitTileOperations.getOffsetConverter( adjustedOverlaps );
		final int[] testRoiOffset = new int[] { -1, -1 };

		// find the offset between the tiles
		final long[] tileOffset = offsetConverter.roiOffsetToTileOffset( testRoiOffset );
		Assert.assertArrayEquals( new long[] { -3, -7 }, tileOffset );

		// find the final position that is the position of the moving tile box in the fixed space so it is compatible with the search radius test
		final double[] finalPosition = offsetConverter.tileOffsetToGlobalPosition( tileOffset );
		Assert.assertArrayEquals( new double[] { -3, -7 }, finalPosition, EPSILON );

		Assert.assertTrue( searchRadius.testPoint( finalPosition ) );
	}
}
