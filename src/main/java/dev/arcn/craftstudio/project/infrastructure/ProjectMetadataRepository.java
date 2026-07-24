package dev.arcn.craftstudio.project.infrastructure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.domain.ProjectMetadata;
import dev.arcn.craftstudio.project.domain.ProjectOperationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ProjectMetadataRepository {
	public static final String FILE_NAME = "craftstudio.project.json";

	private final AtomicFileWriter atomicFileWriter;
	private final Gson gson;

	public ProjectMetadataRepository(AtomicFileWriter atomicFileWriter) {
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
		this.gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	}

	public void save(Path projectRoot, ProjectMetadata metadata) throws IOException {
		atomicFileWriter.writeUtf8(metadataPath(projectRoot), gson.toJson(metadata) + System.lineSeparator());
	}

	public ProjectMetadata load(Path projectRoot) throws ProjectOperationException {
		Path metadataPath = metadataPath(projectRoot);
		if (Files.isSymbolicLink(metadataPath) || !Files.isRegularFile(metadataPath)) {
			throw new ProjectOperationException("No CraftStudio project metadata was found at " + metadataPath);
		}

		try {
			String json = Files.readString(metadataPath, StandardCharsets.UTF_8);
			ProjectMetadata metadata = gson.fromJson(json, ProjectMetadata.class);
			if (metadata == null) {
				throw new ProjectOperationException("Project metadata is empty: " + metadataPath);
			}
			return metadata;
		} catch (IOException exception) {
			throw new ProjectOperationException("Could not read project metadata: " + metadataPath, exception);
		} catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
			throw new ProjectOperationException("Project metadata is malformed: " + metadataPath, exception);
		}
	}

	public Path metadataPath(Path projectRoot) {
		return Objects.requireNonNull(projectRoot, "projectRoot")
			.toAbsolutePath()
			.normalize()
			.resolve(FILE_NAME);
	}
}
