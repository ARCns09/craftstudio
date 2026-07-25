package dev.arcn.craftstudio.export.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ZipEntrySafety {
	private ZipEntrySafety() {
	}

	public static void requireSafe(String entryName) throws IOException {
		String value = Objects.requireNonNull(entryName, "entryName");
		if (value.isBlank()
			|| value.startsWith("/")
			|| value.startsWith("\\")
			|| value.contains("\\")
			|| value.indexOf('\0') >= 0) {
			throw new IOException("Unsafe ZIP entry path: " + value);
		}
		Path normalized = Path.of(value).normalize();
		if (normalized.isAbsolute()
			|| normalized.startsWith("..")
			|| normalized.toString().replace('\\', '/').equals("..")) {
			throw new IOException("Unsafe ZIP entry traversal: " + value);
		}
	}
}
