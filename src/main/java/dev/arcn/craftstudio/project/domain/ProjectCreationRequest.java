package dev.arcn.craftstudio.project.domain;

import java.nio.file.Path;
import java.util.Objects;

public record ProjectCreationRequest(
	String name,
	String slug,
	String description,
	String author,
	Path workspaceRoot
) {
	public ProjectCreationRequest {
		name = Objects.requireNonNull(name, "name");
		slug = Objects.requireNonNull(slug, "slug");
		description = Objects.requireNonNull(description, "description");
		author = Objects.requireNonNull(author, "author");
		workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
	}
}
