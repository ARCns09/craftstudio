package dev.arcn.craftstudio.project.domain;

import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import java.util.List;
import java.util.Objects;

public record RemovalPlan(
	AssetKey root,
	List<ResourcePath> removableFiles,
	List<ResourcePath> sharedFiles,
	List<ResourcePath> modifiedFiles
) {
	public RemovalPlan {
		root = Objects.requireNonNull(root, "root");
		removableFiles = List.copyOf(Objects.requireNonNull(removableFiles, "removableFiles"));
		sharedFiles = List.copyOf(Objects.requireNonNull(sharedFiles, "sharedFiles"));
		modifiedFiles = List.copyOf(Objects.requireNonNull(modifiedFiles, "modifiedFiles"));
	}
}
