package dev.arcn.craftstudio.project.domain;

import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.DependencyClassification;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import java.util.List;
import java.util.Objects;

public record CopyPlan(
	AssetKey root,
	SelectionMode mode,
	List<Entry> entries
) {
	public CopyPlan {
		root = Objects.requireNonNull(root, "root");
		mode = Objects.requireNonNull(mode, "mode");
		entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
	}

	public List<Entry> selectedEntries() {
		return entries.stream().filter(Entry::selected).toList();
	}

	public List<Entry> conflicts() {
		return entries.stream()
			.filter(Entry::selected)
			.filter(entry -> entry.destinationState() == DestinationState.DIFFERENT)
			.toList();
	}

	public enum DestinationState {
		MISSING,
		IDENTICAL,
		DIFFERENT
	}

	public record Entry(
		ResourcePath path,
		DependencyClassification classification,
		boolean required,
		boolean selected,
		DestinationState destinationState
	) {
		public Entry {
			path = Objects.requireNonNull(path, "path");
			classification = Objects.requireNonNull(classification, "classification");
			destinationState = Objects.requireNonNull(destinationState, "destinationState");
		}
	}
}
