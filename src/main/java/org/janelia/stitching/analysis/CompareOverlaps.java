package org.janelia.stitching.analysis;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;

import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.TileOperations;
import org.janelia.stitching.TilePair;

import net.imglib2.util.ComparablePair;

/**
 * Evaluates the quality of a final solution by estimating the distances of every tile
 * from its initial position to a final one.
 *
 * Large offsets indicate that the phase correlation has failed to identify good shifts for some of the tiles,
 * or that the optimization procedure has failed to find a good solution which fits all of the tiles.
 * In this case you may want to:
 * 1. Increase the number of phase correlation peaks that should be investigated by the pairwise stitching algorithm.
 * 2. Preapply gaussian blur to tile images
 *
 * @author Igor Pisarev
 */

public class CompareOverlaps
{
	public static void main( final String[] args ) throws FileNotFoundException, IOException
	{
		final DataProvider dataProvider = DataProviderFactory.createFSDataProvider();

		final TileInfo[] tilesOrig = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( args[ 0 ] ) );
		final TileInfo[] tilesMod  = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( args[ 1 ] ) );

		TileOperations.translateTilesToOrigin( tilesOrig );
		TileOperations.translateTilesToOrigin( tilesMod  );

		if ( tilesOrig.length != tilesMod.length )
			throw new IllegalArgumentException( "Some tiles are missing" );

		final int n = tilesOrig.length;
		System.out.println( "There are " + n + " tiles" );
		System.out.println( "----------------------" );

		final ArrayList< TilePair > overlapsOrig = TileOperations.findOverlappingTiles( tilesOrig );
		final ArrayList< TilePair > overlapsMod = TileOperations.findOverlappingTiles( tilesMod );
		final TreeSet< ComparablePair< Integer, Integer > > overlapsOrigSet = new TreeSet<>();
		final TreeSet< ComparablePair< Integer, Integer > > overlapsModSet = new TreeSet<>();

		for ( final TilePair pair : overlapsOrig )
			overlapsOrigSet.add( new ComparablePair< >(
					Math.min( pair.getA().getIndex(), pair.getB().getIndex() ),
					Math.max( pair.getA().getIndex(), pair.getB().getIndex() ) ) );

		for ( final TilePair pair : overlapsMod )
			overlapsModSet.add( new ComparablePair< >(
					Math.min( pair.getA().getIndex(), pair.getB().getIndex() ),
					Math.max( pair.getA().getIndex(), pair.getB().getIndex() ) ) );

		/*int
		for ( final ComparablePair< Integer, Integer > pair : overlapsOrigSet )
			if ( !overlapsModSet.contains( pair ) )
				System.out.println( x );*/
	}
}
