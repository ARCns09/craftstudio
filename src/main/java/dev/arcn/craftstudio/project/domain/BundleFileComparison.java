package dev.arcn.craftstudio.project.domain;

import dev.arcn.craftstudio.resource.domain.ResourcePath;
import java.util.Objects;

public record BundleFileComparison(
	ResourcePath path,
	int projectSize,
	int vanillaSize,
	String projectSha256,
	String vanillaSha256
) {
	public BundleFileComparison {
		path = Objects.requireNonNull(path, "path");
		projectSha256 = Objects.requireNonNull(projectSha256, "projectSha256");
		vanillaSha256 = Objects.requireNonNull(vanillaSha256, "vanillaSha256");
		if (projectSize < 0 || vanillaSize < 0) {
			throw new IllegalArgumentException("Comparison sizes cannot be negative.");
		}
	}

	public boolean identical() {
		return projectSize == vanillaSize && projectSha256.equals(vanillaSha256);
	}
}
