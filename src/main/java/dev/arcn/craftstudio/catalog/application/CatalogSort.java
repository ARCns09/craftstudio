package dev.arcn.craftstudio.catalog.application;

public enum CatalogSort {
	NAME,
	IDENTIFIER;

	public CatalogSort next() {
		return this == NAME ? IDENTIFIER : NAME;
	}
}
