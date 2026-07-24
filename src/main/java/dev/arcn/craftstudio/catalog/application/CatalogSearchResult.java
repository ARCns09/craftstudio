package dev.arcn.craftstudio.catalog.application;

import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import java.util.List;

public record CatalogSearchResult(
	List<CatalogAsset> assets,
	int totalCount,
	int offset
) {
	public CatalogSearchResult {
		assets = List.copyOf(assets);
	}
}
