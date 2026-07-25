package dev.arcn.craftstudio.project.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record BundleOperationResult(
	CraftStudioProject project,
	int copiedFiles,
	int keptFiles,
	int removedFiles,
	Path backup
) {
	public BundleOperationResult {
		project = Objects.requireNonNull(project, "project");
		if (copiedFiles < 0 || keptFiles < 0 || removedFiles < 0) {
			throw new IllegalArgumentException("Operation counts cannot be negative.");
		}
	}

	public Optional<Path> backupPath() {
		return Optional.ofNullable(backup);
	}
}
