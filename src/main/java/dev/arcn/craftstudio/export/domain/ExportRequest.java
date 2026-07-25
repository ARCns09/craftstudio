package dev.arcn.craftstudio.export.domain;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record ExportRequest(
	ExportType type,
	Path destinationRoot,
	String exportName,
	ExistingOutputPolicy existingOutputPolicy
) {
	private static final Pattern SAFE_NAME = Pattern.compile(
		"[A-Za-z0-9][A-Za-z0-9._ -]{0,79}"
	);
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
		"con", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
	);

	public ExportRequest {
		type = Objects.requireNonNull(type, "type");
		destinationRoot = Objects.requireNonNull(destinationRoot, "destinationRoot")
			.toAbsolutePath()
			.normalize();
		exportName = Objects.requireNonNull(exportName, "exportName").strip();
		if (exportName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			exportName = exportName.substring(0, exportName.length() - 4).strip();
		}
		existingOutputPolicy = Objects.requireNonNull(
			existingOutputPolicy,
			"existingOutputPolicy"
		);
		if (!SAFE_NAME.matcher(exportName).matches()
			|| exportName.endsWith(".")
			|| exportName.endsWith(" ")
			|| WINDOWS_RESERVED_NAMES.contains(
				exportName.split("\\.", 2)[0].toLowerCase(Locale.ROOT)
			)) {
			throw new IllegalArgumentException(
				"Export name must be 1-80 letters, numbers, spaces, dots, hyphens, or underscores."
			);
		}
	}
}
