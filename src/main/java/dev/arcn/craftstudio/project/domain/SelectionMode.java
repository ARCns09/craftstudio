package dev.arcn.craftstudio.project.domain;

import java.util.Locale;

public enum SelectionMode {
	COMPLETE,
	UNIQUE_ONLY,
	CUSTOM;

	public String metadataValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static SelectionMode fromMetadata(String value) {
		return SelectionMode.valueOf(value.toUpperCase(Locale.ROOT));
	}
}
