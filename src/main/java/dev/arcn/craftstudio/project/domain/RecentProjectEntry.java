package dev.arcn.craftstudio.project.domain;

import com.google.gson.annotations.SerializedName;
import java.util.Objects;

public record RecentProjectEntry(
	String path,
	@SerializedName("project_id") String projectId,
	String name,
	@SerializedName("last_opened") String lastOpened,
	@SerializedName("target_version") String targetVersion,
	@SerializedName("last_known_status") String lastKnownStatus
) {
	public RecentProjectEntry {
		path = Objects.requireNonNull(path, "path");
		projectId = Objects.requireNonNull(projectId, "projectId");
		name = Objects.requireNonNull(name, "name");
		lastOpened = Objects.requireNonNull(lastOpened, "lastOpened");
		targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
		lastKnownStatus = Objects.requireNonNull(lastKnownStatus, "lastKnownStatus");
	}
}
