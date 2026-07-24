package dev.arcn.craftstudio.catalog.domain;

import java.util.Objects;

public record CatalogAssetSeed(
	AssetKind kind,
	String identifier,
	String displayName,
	String namespace,
	String path,
	String translationKey
) {
	public CatalogAssetSeed {
		kind = Objects.requireNonNull(kind, "kind");
		identifier = requireText(identifier, "identifier");
		displayName = requireText(displayName, "displayName");
		namespace = requireText(namespace, "namespace");
		path = requireText(path, "path");
		translationKey = requireText(translationKey, "translationKey");
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank");
		}
		return result;
	}
}
