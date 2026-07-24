package dev.arcn.craftstudio.graph.domain;

public record ResolutionStats(
	int nodeCount,
	int edgeCount,
	int issueCount,
	int missingCount
) {
	public ResolutionStats {
		if (nodeCount < 0 || edgeCount < 0 || issueCount < 0 || missingCount < 0) {
			throw new IllegalArgumentException("Resolution statistics cannot be negative.");
		}
	}
}
