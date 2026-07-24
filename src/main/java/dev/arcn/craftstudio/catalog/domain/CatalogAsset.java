package dev.arcn.craftstudio.catalog.domain;

import java.util.List;
import java.util.Objects;

public record CatalogAsset(
	AssetKind kind,
	String identifier,
	String displayName,
	String namespace,
	String path,
	String translationKey,
	List<String> searchTerms,
	GraphResolutionStatus graphResolutionStatus,
	PreviewSupportStatus previewSupportStatus
) {
	public CatalogAsset {
		kind = Objects.requireNonNull(kind, "kind");
		identifier = Objects.requireNonNull(identifier, "identifier");
		displayName = Objects.requireNonNull(displayName, "displayName");
		namespace = Objects.requireNonNull(namespace, "namespace");
		path = Objects.requireNonNull(path, "path");
		translationKey = Objects.requireNonNull(translationKey, "translationKey");
		searchTerms = List.copyOf(Objects.requireNonNull(searchTerms, "searchTerms"));
		graphResolutionStatus = Objects.requireNonNull(graphResolutionStatus, "graphResolutionStatus");
		previewSupportStatus = Objects.requireNonNull(previewSupportStatus, "previewSupportStatus");
	}
}
