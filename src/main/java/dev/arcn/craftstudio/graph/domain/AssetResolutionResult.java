package dev.arcn.craftstudio.graph.domain;

import java.util.List;
import java.util.Objects;

public record AssetResolutionResult(
	AssetKey root,
	AssetGraph graph,
	List<ResolutionIssue> issues,
	ResolutionStats stats
) {
	public AssetResolutionResult {
		root = Objects.requireNonNull(root, "root");
		graph = Objects.requireNonNull(graph, "graph");
		issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
		stats = Objects.requireNonNull(stats, "stats");
	}

	public boolean hasErrors() {
		return issues.stream().anyMatch(issue -> issue.severity() == ResolutionIssueSeverity.ERROR);
	}
}
