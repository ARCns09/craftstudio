package dev.arcn.craftstudio.platform.process;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class EditorSettingsRepository {
	private final Path settingsFile;
	private final AtomicFileWriter atomicFileWriter;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	public EditorSettingsRepository(Path settingsFile, AtomicFileWriter atomicFileWriter) {
		this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile")
			.toAbsolutePath()
			.normalize();
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
	}

	public EditorSettings load() throws IOException {
		if (!Files.exists(settingsFile)) {
			return EditorSettings.DEFAULT;
		}
		if (Files.isSymbolicLink(settingsFile) || !Files.isRegularFile(settingsFile)) {
			throw new IOException("Editor settings path is not a safe regular file: " + settingsFile);
		}
		try {
			EditorSettings settings = gson.fromJson(
				Files.readString(settingsFile, StandardCharsets.UTF_8),
				EditorSettings.class
			);
			return settings == null ? EditorSettings.DEFAULT : settings;
		} catch (JsonParseException | NullPointerException exception) {
			throw new IOException("Editor settings are malformed: " + settingsFile, exception);
		}
	}

	public void save(EditorSettings settings) throws IOException {
		atomicFileWriter.writeUtf8(
			settingsFile,
			gson.toJson(Objects.requireNonNull(settings, "settings")) + System.lineSeparator()
		);
	}
}
