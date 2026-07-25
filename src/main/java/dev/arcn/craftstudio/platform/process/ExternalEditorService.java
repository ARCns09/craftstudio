package dev.arcn.craftstudio.platform.process;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ExternalEditorService {
	public LaunchResult openImage(Path file, EditorSettings settings) throws IOException {
		Path safeFile = requireRegularFile(file);
		String preferredEditor = Objects.requireNonNull(settings, "settings")
			.preferredImageEditor();
		if (!preferredEditor.isBlank()) {
			start(List.of(preferredEditor, safeFile.toString()));
			return new LaunchResult(LaunchMode.PREFERRED_EDITOR, safeFile);
		}
		openSystemDefault(safeFile);
		return new LaunchResult(LaunchMode.SYSTEM_DEFAULT, safeFile);
	}

	public void openSystemDefault(Path file) throws IOException {
		Path safeFile = requireRegularFile(file);
		if (Desktop.isDesktopSupported()
			&& Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			Desktop.getDesktop().open(safeFile.toFile());
			return;
		}
		String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (operatingSystem.contains("mac")) {
			start(List.of("open", safeFile.toString()));
		} else if (operatingSystem.contains("win")) {
			start(List.of("rundll32", "url.dll,FileProtocolHandler", safeFile.toString()));
		} else {
			start(List.of("xdg-open", safeFile.toString()));
		}
	}

	private Path requireRegularFile(Path file) throws IOException {
		Path safeFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
		if (Files.isSymbolicLink(safeFile) || !Files.isRegularFile(safeFile)) {
			throw new IOException("The project texture is no longer available: " + safeFile);
		}
		return safeFile;
	}

	private void start(List<String> arguments) throws IOException {
		new ProcessBuilder(arguments)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
	}

	public enum LaunchMode {
		PREFERRED_EDITOR,
		SYSTEM_DEFAULT
	}

	public record LaunchResult(LaunchMode mode, Path file) {
		public LaunchResult {
			mode = Objects.requireNonNull(mode, "mode");
			file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
		}
	}
}
