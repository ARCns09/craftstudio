package dev.arcn.craftstudio.catalog;

import dev.arcn.craftstudio.catalog.application.CatalogCategory;
import dev.arcn.craftstudio.catalog.application.CatalogIndex;
import dev.arcn.craftstudio.catalog.application.CatalogQuery;
import dev.arcn.craftstudio.catalog.application.CatalogSearchResult;
import dev.arcn.craftstudio.catalog.application.CatalogSort;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.catalog.domain.CatalogAssetSeed;
import dev.arcn.craftstudio.catalog.domain.GraphResolutionStatus;
import dev.arcn.craftstudio.catalog.domain.PreviewSupportStatus;
import java.util.List;

public final class CatalogIndexTest {
	private CatalogIndexTest() {
	}

	public static void main(String[] args) {
		CatalogIndex index = CatalogIndex.build(List.of(
			seed(AssetKind.BLOCK, "minecraft:stone", "Stone"),
			seed(AssetKind.ITEM, "minecraft:stone", "Stone"),
			seed(AssetKind.BLOCK, "minecraft:furnace", "Furnace"),
			seed(AssetKind.ITEM, "minecraft:diamond_sword", "Diamond Sword"),
			seed(AssetKind.BLOCK, "example:display_block", "Display Block")
		));

		assertEquals(5, index.size(), "index size");
		assertEquals(List.of("example", "minecraft"), index.namespaces(), "namespaces");
		testExactIdentifierRanking(index);
		testCaseInsensitiveNameAndTokenSearch(index);
		testCategoryAndNamespaceFilters(index);
		testPagingAndSort(index);
		System.out.println("Catalog index tests passed.");
	}

	private static void testExactIdentifierRanking(CatalogIndex index) {
		CatalogSearchResult result = search(
			index,
			"minecraft:furnace",
			CatalogCategory.ALL,
			CatalogQuery.ALL_NAMESPACES,
			CatalogSort.NAME,
			0,
			10
		);
		assertEquals(1, result.totalCount(), "exact identifier result count");
		assertEquals("minecraft:furnace", result.assets().getFirst().identifier(), "exact identifier");
		assertPlaceholderStatuses(result.assets().getFirst());
	}

	private static void testCaseInsensitiveNameAndTokenSearch(CatalogIndex index) {
		CatalogSearchResult exactName = search(
			index,
			"DIAMOND SWORD",
			CatalogCategory.ALL,
			CatalogQuery.ALL_NAMESPACES,
			CatalogSort.NAME,
			0,
			10
		);
		assertEquals(1, exactName.totalCount(), "case-insensitive name search");
		assertEquals("minecraft:diamond_sword", exactName.assets().getFirst().identifier(), "name match");

		CatalogSearchResult tokenized = search(
			index,
			"diamond sw",
			CatalogCategory.ITEMS,
			CatalogQuery.ALL_NAMESPACES,
			CatalogSort.NAME,
			0,
			10
		);
		assertEquals(1, tokenized.totalCount(), "tokenized search");
	}

	private static void testCategoryAndNamespaceFilters(CatalogIndex index) {
		CatalogSearchResult blocks = search(
			index,
			"stone",
			CatalogCategory.BLOCKS,
			"minecraft",
			CatalogSort.NAME,
			0,
			10
		);
		assertEquals(1, blocks.totalCount(), "block filter");
		assertEquals(AssetKind.BLOCK, blocks.assets().getFirst().kind(), "block kind");

		CatalogSearchResult example = search(
			index,
			"",
			CatalogCategory.ALL,
			"example",
			CatalogSort.NAME,
			0,
			10
		);
		assertEquals(1, example.totalCount(), "namespace filter");
		assertEquals("example:display_block", example.assets().getFirst().identifier(), "namespace match");
	}

	private static void testPagingAndSort(CatalogIndex index) {
		CatalogSearchResult firstPage = search(
			index,
			"",
			CatalogCategory.ALL,
			CatalogQuery.ALL_NAMESPACES,
			CatalogSort.IDENTIFIER,
			0,
			2
		);
		CatalogSearchResult secondPage = search(
			index,
			"",
			CatalogCategory.ALL,
			CatalogQuery.ALL_NAMESPACES,
			CatalogSort.IDENTIFIER,
			2,
			2
		);
		assertEquals(5, firstPage.totalCount(), "paged total");
		assertEquals(2, firstPage.assets().size(), "first page size");
		assertEquals(2, secondPage.assets().size(), "second page size");
		assertTrue(
			!firstPage.assets().getFirst().equals(secondPage.assets().getFirst()),
			"pages must contain different assets"
		);
	}

	private static CatalogSearchResult search(
		CatalogIndex index,
		String text,
		CatalogCategory category,
		String namespace,
		CatalogSort sort,
		int offset,
		int limit
	) {
		return index.search(new CatalogQuery(text, category, namespace, sort, offset, limit));
	}

	private static CatalogAssetSeed seed(AssetKind kind, String identifier, String displayName) {
		String[] parts = identifier.split(":", 2);
		String translationPrefix = kind == AssetKind.BLOCK ? "block" : "item";
		return new CatalogAssetSeed(
			kind,
			identifier,
			displayName,
			parts[0],
			parts[1],
			translationPrefix + "." + parts[0] + "." + parts[1]
		);
	}

	private static void assertPlaceholderStatuses(CatalogAsset asset) {
		assertEquals(
			GraphResolutionStatus.NOT_RESOLVED,
			asset.graphResolutionStatus(),
			"graph status"
		);
		assertEquals(
			PreviewSupportStatus.NOT_EVALUATED,
			asset.previewSupportStatus(),
			"preview status"
		);
	}

	private static void assertTrue(boolean condition, String description) {
		if (!condition) {
			throw new AssertionError("Assertion failed: " + description);
		}
	}

	private static void assertEquals(Object expected, Object actual, String description) {
		if (!expected.equals(actual)) {
			throw new AssertionError(
				"Assertion failed for " + description + ": expected=" + expected + ", actual=" + actual
			);
		}
	}
}
