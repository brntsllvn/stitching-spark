package org.janelia.stitching.analysis;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.stitching.SerializablePairWiseStitchingResult;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.Utils;

@Deprecated
public class TracePairwiseShiftsForAllChannelsMulti
{
	public static void main( final String[] args ) throws Exception
	{
		final DataProvider dataProvider = DataProviderFactory.createFSDataProvider();

		final Map< Integer, TileInfo >[] channelsMap = new TreeMap[ 2 ];
		for ( int i = 0; i < channelsMap.length; i++ )
			channelsMap[ i ] = Utils.createTilesMap( TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( URI.create( args[ i ] ) ) ) );

		// validate
		for ( int i = 1; i < channelsMap.length; i++ )
			if ( channelsMap[ i ].size() != channelsMap[ i - 1 ].size() )
				throw new Exception( "Different number of tiles per channel" );
		final int tilesPerChannel = channelsMap.length > 0 ? channelsMap[ 0 ].size() : 0;
		if ( tilesPerChannel > 0 )
			System.out.println( tilesPerChannel + " tiles per channel" );


		final Map< Integer, TileInfo >[] channelsFinalMap = new TreeMap[ 2 ];
		for ( int i = 0; i < channelsFinalMap.length; i++ )
			channelsFinalMap[ i ] = Utils.createTilesMap( TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( URI.create( args[ 2 + i ] ) ) ) );


		final List< SerializablePairWiseStitchingResult[] >[] shifts = new List[ 2 ];
		for ( int i = 0; i < shifts.length; i++ )
			shifts[ i ] = TileInfoJSONProvider.loadPairwiseShiftsMulti( dataProvider.getJsonReader( URI.create( args[ 4 + i ] ) ) );

		final String pairwiseShiftsCombinedFilepath = args.length > 7 ? args[ args.length - 2 ] : "";
		final List< SerializablePairWiseStitchingResult[] > shiftsForCombinedChannels = !pairwiseShiftsCombinedFilepath.isEmpty() ? TileInfoJSONProvider.loadPairwiseShiftsMulti( dataProvider.getJsonReader( URI.create( pairwiseShiftsCombinedFilepath ) ) ) : null;
		final String outputFolder = args[ args.length - 1 ];

		if ( shifts[ 0 ].size() != shifts[ 1 ].size() || shifts[ 0 ].size() != shiftsForCombinedChannels.size() )
			throw new Exception( "Different number of shifts" );

		final String suffixInliers = ( pairwiseShiftsCombinedFilepath.contains( "_inliers" ) ? "_inliers" : "" );
		final String suffixDim = ( pairwiseShiftsCombinedFilepath.contains( "-x" ) ? "-x" : ( pairwiseShiftsCombinedFilepath.contains( "-y" ) ? "-y" : ( pairwiseShiftsCombinedFilepath.contains( "-z" ) ? "-z" : "" ) ) );

		int valid = 0, finalPairs = 0, columns = 0;
		final String timePatternStr = ".*_(\\d*)msecAbs.*";
		final Pattern timePattern = Pattern.compile( timePatternStr );

		final String outFilepath = outputFolder+"/pairs-multi" + suffixInliers + suffixDim + ".txt";

		final PrintWriter writer = new PrintWriter(outFilepath, "UTF-8");
		for ( int ind = 0; ind < shiftsForCombinedChannels.size(); ind++ )
		{
			if ( shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex().intValue() != shifts[ 0 ].get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex().intValue() ||
					shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex().intValue() != shifts[ 0 ].get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex().intValue() ||
					tilesPerChannel+shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex().intValue() != shifts[ 1 ].get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex().intValue() ||
					tilesPerChannel+shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex().intValue() != shifts[ 1 ].get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex().intValue() )
			{
				throw new Exception( "Tile indices don't match: ch0=("+shifts[0].get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex()+","+shifts[0].get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex()+"), ch1=("+
						shifts[1].get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex()+","+shifts[1].get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex()+"), combined="+
						shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex()+","+shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex()+"), combined sum="+
						(tilesPerChannel+shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex())+","+(tilesPerChannel+shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex())+")");
			}

			if ( !shiftsForCombinedChannels.get( ind )[0].getIsValidOverlap() )
				continue;

			valid++;

			// append final coordinates
			if ( !channelsFinalMap[0].containsKey( shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex() ) ||
					!channelsFinalMap[0].containsKey( shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex() ) )
			{
				continue;
			}

			columns = 0;
			finalPairs++;

			final TileInfo[] ch0TilePair = shifts[ 0 ].get( ind )[0].getSubTilePair().getFullTilePair().toArray();
			final TileInfo[] ch1TilePair = shifts[ 1 ].get( ind )[0].getSubTilePair().getFullTilePair().toArray();
			final TileInfo[] combinedTilePair = shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().toArray();

			String outputLine = "";
			outputLine += combinedTilePair[ 0 ].getStagePosition(0)+" "+combinedTilePair[ 0 ].getStagePosition(1)+" "+combinedTilePair[ 0 ].getStagePosition(2)+" ";
			outputLine += combinedTilePair[ 1 ].getStagePosition(0)+" "+combinedTilePair[ 1 ].getStagePosition(1)+" "+combinedTilePair[ 1 ].getStagePosition(2)+" ";
			columns+=6;


			final TileInfo t1 = channelsFinalMap[0].get( shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getA().getIndex() );
			final TileInfo t2 = channelsFinalMap[0].get( shiftsForCombinedChannels.get( ind )[0].getSubTilePair().getFullTilePair().getB().getIndex() );
			outputLine += t1.getStagePosition( 0 ) + " " + t1.getStagePosition( 1 ) + " " + t1.getStagePosition( 2 ) + " ";
			outputLine += t2.getStagePosition( 0 ) + " " + t2.getStagePosition( 1 ) + " " + t2.getStagePosition( 2 ) + " ";
			columns+=6;


			final long[] timestamps = new long[ 2 ];
			for ( int j = 0; j < 2; j++ )
			{
				final String filename = Paths.get( channelsMap[ 0 ].get( combinedTilePair[ j ].getIndex() ).getFilePath() ).getFileName().toString();
				final Matcher matcher = timePattern.matcher( filename );
				if ( !matcher.find() )
					throw new Exception( "Can't parse timestamp" );

				timestamps[ j ] = Long.parseLong( matcher.group( 1 ) );
			}
			outputLine += timestamps[0] + " " + timestamps[1];
			columns+=2;

			for ( int peak = 0; peak < shiftsForCombinedChannels.get( ind ).length; peak++ )
			{
				outputLine += " ";

				final double dist[] = new double[ 3 ];
				for ( int d = 0; d < 3; d++ )
					dist[d] = (ch0TilePair[ 1 ].getStagePosition( d ) - ch0TilePair[ 0 ].getStagePosition( d )) - shifts[ 0 ].get( ind )[ peak ].getOffset( d );
				outputLine += dist[0] + " " + dist[1] + " " + dist[2] + " ";
				columns+=3;

				for ( int d = 0; d < 3; d++ )
					dist[d] = (ch1TilePair[ 1 ].getStagePosition( d ) - ch1TilePair[ 0 ].getStagePosition( d )) - shifts[ 1 ].get( ind )[ peak ].getOffset( d );
				outputLine += dist[0] + " " + dist[1] + " " + dist[2] + " ";
				columns+=3;

				for ( int d = 0; d < 3; d++ )
					dist[d] = (combinedTilePair[ 1 ].getStagePosition( d ) - combinedTilePair[ 0 ].getStagePosition( d )) - shiftsForCombinedChannels.get( ind )[ peak ].getOffset( d );
				outputLine += dist[0] + " " + dist[1] + " " + dist[2] + " ";
				columns+=3;


				outputLine += shifts[ 0 ].get( ind )[ peak ].getCrossCorrelation() + " " + shifts[ 1 ].get( ind )[ peak ].getCrossCorrelation() + " " + shiftsForCombinedChannels.get( ind )[ peak ].getCrossCorrelation();
				columns+=3;
			}



			writer.println( outputLine );
		}
		writer.close();

		System.out.println( "Created: " + outFilepath );
		System.out.println( "Processed " + valid + " valid pairs out of " + shiftsForCombinedChannels.size() );
		System.out.println( "There are " + finalPairs + " pairs present in the final configuration" );
		System.out.println( "Columns: " + columns );
	}
}
