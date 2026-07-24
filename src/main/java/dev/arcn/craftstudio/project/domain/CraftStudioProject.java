package dev.arcn.craftstudio.project.domain;

import java.nio.file.Path;
import java.util.Objects;

public record CraftStudioProject(Path root, ProjectMetadata metadata) {
	public CraftStudioProject {
		root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
		metadata = Objects.requireNonNull(metadata, "metadata");
	}

	public Path packRoot() {
		return root.resolve(metadata.packRoot()).normalize();
	}
}
