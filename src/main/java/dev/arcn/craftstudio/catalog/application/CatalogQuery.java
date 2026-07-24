package dev.arcn.craftstudio.catalog.application;

import java.util.Objects;

public record CatalogQuery(
	String text,
	CatalogCategory category,
	String namespace,
	CatalogSort sort,
	int offset,
	int limit
) {
	public static final String ALL_NAMESPACES = "*";

	public CatalogQuery {
		text = Objects.requireNonNull(text, "text");
		category = Objects.requireNonNull(category, "category");
		namespace = Objects.requireNonNull(namespace, "namespace");
		sort = Objects.requireNonNull(sort, "sort");
		if (offset < 0) {
			throw new IllegalArgumentException("offset cannot be negative");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be positive");
		}
	}
}
