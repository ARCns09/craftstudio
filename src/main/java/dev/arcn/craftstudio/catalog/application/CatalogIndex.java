package dev.arcn.craftstudio.catalog.application;

import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.catalog.domain.CatalogAssetSeed;
import dev.arcn.craftstudio.catalog.domain.GraphResolutionStatus;
import dev.arcn.craftstudio.catalog.domain.PreviewSupportStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CatalogIndex {
	private final List<IndexedAsset> assets;
	private final List<String> namespaces;

	private CatalogIndex(List<IndexedAsset> assets, List<String> namespaces) {
		this.assets = List.copyOf(assets);
		this.namespaces = List.copyOf(namespaces);
	}

	public static CatalogIndex build(List<CatalogAssetSeed> seeds) {
		List<IndexedAsset> indexedAssets = new ArrayList<>(seeds.size());
		Set<String> namespaces = new LinkedHashSet<>();

		for (CatalogAssetSeed seed : seeds) {
			String normalizedName = normalize(seed.displayName());
			String normalizedIdentifier = normalize(seed.identifier());
			String normalizedPath = normalize(seed.path());
			List<String> searchTerms = createSearchTerms(seed, normalizedName, normalizedIdentifier);
			CatalogAsset asset = new CatalogAsset(
				seed.kind(),
				seed.identifier(),
				seed.displayName(),
				seed.namespace(),
				seed.path(),
				seed.translationKey(),
				searchTerms,
				GraphResolutionStatus.NOT_RESOLVED,
				PreviewSupportStatus.NOT_EVALUATED
			);
			String combinedSearchText = String.join(" ", searchTerms);
			indexedAssets.add(new IndexedAsset(
				asset,
				normalizedName,
				normalizedIdentifier,
				normalizedPath,
				combinedSearchText
			));
			namespaces.add(seed.namespace());
		}

		List<String> sortedNamespaces = namespaces.stream().sorted().toList();
		return new CatalogIndex(indexedAssets, sortedNamespaces);
	}

	public CatalogSearchResult search(CatalogQuery query) {
		String normalizedQuery = normalize(query.text()).strip();
		List<String> queryTokens = tokenize(normalizedQuery);
		List<ScoredAsset> matches = new ArrayList<>();

		for (IndexedAsset indexed : assets) {
			CatalogAsset asset = indexed.asset();
			if (!query.category().includes(asset.kind())) {
				continue;
			}
			if (!query.namespace().equals(CatalogQuery.ALL_NAMESPACES)
				&& !query.namespace().equals(asset.namespace())) {
				continue;
			}
			if (!matchesAllTokens(indexed.combinedSearchText(), queryTokens)) {
				continue;
			}
			matches.add(new ScoredAsset(indexed, score(indexed, normalizedQuery, queryTokens)));
		}

		matches.sort(scoredComparator(query.sort()));
		int totalCount = matches.size();
		int fromIndex = Math.min(query.offset(), totalCount);
		int toIndex = Math.min(fromIndex + query.limit(), totalCount);
		List<CatalogAsset> page = matches.subList(fromIndex, toIndex).stream()
			.map(match -> match.indexed().asset())
			.toList();
		return new CatalogSearchResult(page, totalCount, fromIndex);
	}

	public int size() {
		return assets.size();
	}

	public List<String> namespaces() {
		return namespaces;
	}

	private static List<String> createSearchTerms(
		CatalogAssetSeed seed,
		String normalizedName,
		String normalizedIdentifier
	) {
		LinkedHashSet<String> terms = new LinkedHashSet<>();
		terms.add(normalizedIdentifier);
		terms.add(normalize(seed.namespace()));
		terms.add(normalize(seed.path()));
		terms.add(normalizedName);
		terms.add(normalize(seed.translationKey()));
		terms.add(seed.kind().name().toLowerCase(Locale.ROOT));
		terms.addAll(tokenize(normalizedName));
		terms.addAll(tokenize(normalize(seed.path()).replace('_', ' ')));
		return List.copyOf(terms);
	}

	private static boolean matchesAllTokens(String combinedSearchText, List<String> queryTokens) {
		for (String token : queryTokens) {
			if (!combinedSearchText.contains(token)) {
				return false;
			}
		}
		return true;
	}

	private static int score(
		IndexedAsset indexed,
		String normalizedQuery,
		List<String> queryTokens
	) {
		if (normalizedQuery.isEmpty()) {
			return 100;
		}
		if (indexed.normalizedIdentifier().equals(normalizedQuery)) {
			return 0;
		}
		if (indexed.normalizedName().equals(normalizedQuery)) {
			return 10;
		}
		if (indexed.normalizedIdentifier().startsWith(normalizedQuery)) {
			return 20;
		}
		if (indexed.normalizedName().startsWith(normalizedQuery)) {
			return 30;
		}
		if (indexed.normalizedPath().startsWith(normalizedQuery)) {
			return 40;
		}

		int prefixMatches = 0;
		for (String queryToken : queryTokens) {
			if (indexed.asset().searchTerms().stream().anyMatch(term -> term.startsWith(queryToken))) {
				prefixMatches++;
			}
		}
		return 80 - Math.min(prefixMatches, 20);
	}

	private static Comparator<ScoredAsset> scoredComparator(CatalogSort sort) {
		Comparator<ScoredAsset> selectedSort = switch (sort) {
			case NAME -> Comparator
				.comparing((ScoredAsset match) -> match.indexed().normalizedName())
				.thenComparing(match -> match.indexed().normalizedIdentifier());
			case IDENTIFIER -> Comparator.comparing(
				match -> match.indexed().normalizedIdentifier()
			);
		};
		return Comparator.comparingInt(ScoredAsset::score)
			.thenComparing(selectedSort)
			.thenComparing(match -> match.indexed().asset().kind());
	}

	private static List<String> tokenize(String value) {
		if (value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split("[\\s:_./-]+"))
			.filter(token -> !token.isBlank())
			.toList();
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT);
	}

	private record IndexedAsset(
		CatalogAsset asset,
		String normalizedName,
		String normalizedIdentifier,
		String normalizedPath,
		String combinedSearchText
	) {
	}

	private record ScoredAsset(IndexedAsset indexed, int score) {
	}
}
