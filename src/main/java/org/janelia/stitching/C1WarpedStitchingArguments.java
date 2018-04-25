package org.janelia.stitching;

import java.io.Serializable;

public class C1WarpedStitchingArguments implements Serializable {

	private static final long serialVersionUID = -8996450783846140673L;

	private int minStatsNeighborhood = 5;
	private double defaultSearchRadius = 50;
	private double searchRadiusMultiplier = 3; // 3 * std
	private int[] estimationWindowSizeTiles = new int[] { 3, 3, 3 }; // 3x tile size
	private int fusionCellSize = 128;
	private double blurSigma = 2.0;
	private long[] padding = new long[ 3 ];
	private long[] minCoord = null;
	private long[] maxCoord = null;
	private boolean allPairs = false;
	private boolean noLeaves = false;
	private boolean translationOnlyStitching = false;
	private boolean affineOnlyStitching = true;
	private StitchingMode stitchingMode = StitchingMode.INCREMENTAL;
	private int splitOverlapParts = 2;
	private double maxStitchingError = 10.0;

	public long[] padding() { return padding; }
	public long[] minCoord() { return minCoord; }
	public long[] maxCoord() { return maxCoord; }
	public int minStatsNeighborhood() { return minStatsNeighborhood; }
	public int[] estimationWindowSizeTiles() { return estimationWindowSizeTiles; }
	public double defaultSearchRadius() { return defaultSearchRadius; }
	public double searchRadiusMultiplier() { return searchRadiusMultiplier; }
	public int fusionCellSize() { return fusionCellSize; }
	public double blurSigma() { return blurSigma; }
	public boolean useAllPairs() { return allPairs; }
	public boolean noLeaves() { return noLeaves; }
	public boolean translationOnlyStitching() { return translationOnlyStitching; }
	public boolean affineOnlyStitching() { return affineOnlyStitching; }
	public int splitOverlapParts() { return splitOverlapParts; }
	public StitchingMode stitchingMode() { return stitchingMode; }
	public double maxStitchingError() { return maxStitchingError; }
}