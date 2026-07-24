package dev.arcn.craftstudio.platform.paths;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspacePaths(
	Path configRoot,
	Path defaultWorkspaceRoot,
	Path recentProjectsFile
) {
	public WorkspacePaths {
		configRoot = normalize(configRoot, "configRoot");
		defaultWorkspaceRoot = normalize(defaultWorkspaceRoot, "defaultWorkspaceRoot");
		recentProjectsFile = normalize(recentProjectsFile, "recentProjectsFile");
	}

	private static Path normalize(Path path, String name) {
		return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
	}
}
