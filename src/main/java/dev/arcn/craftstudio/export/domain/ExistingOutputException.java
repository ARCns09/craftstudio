package dev.arcn.craftstudio.export.domain;

import java.nio.file.Path;
import java.util.Objects;

public final class ExistingOutputException extends ExportException {
	private final Path output;

	public ExistingOutputException(Path output) {
		super("Output already exists. Choose another name or explicitly replace it with a backup.");
		this.output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
	}

	public Path output() {
		return output;
	}
}
