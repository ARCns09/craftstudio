package dev.arcn.craftstudio.resource.domain;

import java.util.Arrays;
import java.util.Objects;

public record ResourceData(
	ResourcePath path,
	byte[] bytes,
	SourceLayer layer,
	String revision
) {
	public ResourceData {
		path = Objects.requireNonNull(path, "path");
		bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
		layer = Objects.requireNonNull(layer, "layer");
		revision = requireText(revision, "revision");
	}

	@Override
	public byte[] bytes() {
		return Arrays.copyOf(bytes, bytes.length);
	}

	public int size() {
		return bytes.length;
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
