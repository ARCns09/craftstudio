package dev.arcn.craftstudio.catalog.application;

import dev.arcn.craftstudio.catalog.domain.AssetKind;

public enum CatalogCategory {
	ALL,
	BLOCKS,
	ITEMS;

	public boolean includes(AssetKind kind) {
		return this == ALL
			|| (this == BLOCKS && kind == AssetKind.BLOCK)
			|| (this == ITEMS && kind == AssetKind.ITEM);
	}

	public CatalogCategory next() {
		return switch (this) {
			case ALL -> BLOCKS;
			case BLOCKS -> ITEMS;
			case ITEMS -> ALL;
		};
	}
}
