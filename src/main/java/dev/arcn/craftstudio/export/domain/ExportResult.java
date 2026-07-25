package dev.arcn.craftstudio.export.domain;

import dev.arcn.craftstudio.validation.domain.ValidationReport;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ExportResult(
	Path output,
	Path report,
	int fileCount,
	String sha256,
	ValidationReport validation,
	Optional<Path> backup
) {
	public ExportResult {
		output = normalize(output, "output");
		report = normalize(report, "report");
		if (fileCount < 1) {
			throw new IllegalArgumentException("Export must contain at least one file.");
		}
		sha256 = Objects.requireNonNull(sha256, "sha256");
		validation = Objects.requireNonNull(validation, "validation");
		backup = Objects.requireNonNull(backup, "backup")
			.map(path -> normalize(path, "backup"));
	}

	private static Path normalize(Path path, String name) {
		return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
	}
}
