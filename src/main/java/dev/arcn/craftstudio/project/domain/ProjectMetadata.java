package dev.arcn.craftstudio.project.domain;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Objects;

public record ProjectMetadata(
	@SerializedName("schema_version") int schemaVersion,
	@SerializedName("project_id") String projectId,
	String name,
	String slug,
	String description,
	String author,
	ProjectTarget target,
	@SerializedName("pack_root") String packRoot,
	@SerializedName("created_at") String createdAt,
	@SerializedName("updated_at") String updatedAt,
	@SerializedName("selected_roots") List<SelectedRoot> selectedRoots,
	ProjectSettings settings
) {
	public ProjectMetadata {
		projectId = Objects.requireNonNull(projectId, "projectId");
		name = Objects.requireNonNull(name, "name");
		slug = Objects.requireNonNull(slug, "slug");
		description = Objects.requireNonNull(description, "description");
		author = Objects.requireNonNull(author, "author");
		target = Objects.requireNonNull(target, "target");
		packRoot = Objects.requireNonNull(packRoot, "packRoot");
		createdAt = Objects.requireNonNull(createdAt, "createdAt");
		updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
		selectedRoots = List.copyOf(Objects.requireNonNull(selectedRoots, "selectedRoots"));
		settings = Objects.requireNonNull(settings, "settings");
	}

	public record ProjectTarget(
		String minecraft,
		@SerializedName("resource_pack_format") int resourcePackFormat
	) {
		public ProjectTarget {
			minecraft = Objects.requireNonNull(minecraft, "minecraft");
		}
	}

	public record SelectedRoot(
		String type,
		String id,
		@SerializedName("selection_mode") String selectionMode
	) {
		public SelectedRoot {
			type = Objects.requireNonNull(type, "type");
			id = Objects.requireNonNull(id, "id");
			selectionMode = Objects.requireNonNull(selectionMode, "selectionMode");
		}
	}

	public record ProjectSettings(
		@SerializedName("auto_reload") boolean autoReload,
		@SerializedName("advanced_mode") boolean advancedMode
	) {
	}
}
