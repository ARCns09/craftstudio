package dev.arcn.craftstudio.project.infrastructure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.RecentProjectEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecentProjectRegistry {
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_ENTRIES = 10;

	private final Path registryFile;
	private final AtomicFileWriter atomicFileWriter;
	private final Gson gson;

	public RecentProjectRegistry(Path registryFile, AtomicFileWriter atomicFileWriter) {
		this.registryFile = Objects.requireNonNull(registryFile, "registryFile")
			.toAbsolutePath()
			.normalize();
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
		this.gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	}

	public List<RecentProjectEntry> load() throws IOException {
		if (!Files.exists(registryFile)) {
			return List.of();
		}
		if (Files.isSymbolicLink(registryFile) || !Files.isRegularFile(registryFile)) {
			throw new IOException("Recent-project registry is not a regular file: " + registryFile);
		}

		try {
			RecentProjectsDocument document = gson.fromJson(
				Files.readString(registryFile, StandardCharsets.UTF_8),
				RecentProjectsDocument.class
			);
			if (document == null || document.schemaVersion() != SCHEMA_VERSION || document.projects() == null) {
				throw new IOException("Recent-project registry has an unsupported or malformed structure.");
			}
			return List.copyOf(document.projects());
		} catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
			throw new IOException("Recent-project registry is malformed: " + registryFile, exception);
		}
	}

	public List<RecentProjectEntry> touch(CraftStudioProject project, Instant openedAt) throws IOException {
		List<RecentProjectEntry> projects = new ArrayList<>(load());
		String normalizedPath = project.root().toString();
		projects.removeIf(entry ->
			entry.projectId().equals(project.metadata().projectId()) || entry.path().equals(normalizedPath)
		);
		projects.addFirst(new RecentProjectEntry(
			normalizedPath,
			project.metadata().projectId(),
			project.metadata().name(),
			openedAt.toString(),
			project.metadata().target().minecraft(),
			"not_validated"
		));

		if (projects.size() > MAX_ENTRIES) {
			projects = new ArrayList<>(projects.subList(0, MAX_ENTRIES));
		}
		save(projects);
		return List.copyOf(projects);
	}

	private void save(List<RecentProjectEntry> projects) throws IOException {
		RecentProjectsDocument document = new RecentProjectsDocument(SCHEMA_VERSION, List.copyOf(projects));
		atomicFileWriter.writeUtf8(
			registryFile,
			gson.toJson(document) + System.lineSeparator()
		);
	}

	private record RecentProjectsDocument(
		@SerializedName("schema_version") int schemaVersion,
		List<RecentProjectEntry> projects
	) {
	}
}
