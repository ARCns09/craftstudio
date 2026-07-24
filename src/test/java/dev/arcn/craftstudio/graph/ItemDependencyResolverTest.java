package dev.arcn.craftstudio.graph;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.graph.resolver.ItemDependencyResolver;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ItemDependencyResolverTest {
	private ItemDependencyResolverTest() {
	}

	public static void main(String[] args) throws Exception {
		ItemDependencyResolver resolver = new ItemDependencyResolver(
			new ProjectAssetSource(fixtureRoot(), "item-resolver-fixture"),
			"1.21.11"
		);
		testPlainModel(resolver);
		testComposite(resolver);
		testConditionSelectAndRange(resolver);
		testUnsupportedDefinitionIsDiagnostic(resolver);
		testSpecialRenderer(resolver);
		testMixedAtlasValidation(resolver);
		System.out.println("Item dependency resolver tests passed.");
	}

	private static void testPlainModel(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "simple");
		assertFalse(result.hasErrors(), "simple item should resolve without errors");
		assertContainsNode(result, GraphNodeType.CLIENT_ITEM_FILE, "items/simple.json");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/simple");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/generated");
		assertContainsNode(result, GraphNodeType.BUILTIN_MODEL, "builtin/generated");
		assertContainsNode(result, GraphNodeType.TEXTURE_FILE, "item/simple");
		assertContainsNode(result, GraphNodeType.ATLAS_FILE, "items");
		assertEquals(0, result.stats().missingCount(), "simple item missing dependencies");
	}

	private static void testComposite(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "composite_test");
		assertFalse(result.hasErrors(), "composite item should resolve without errors");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/simple");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/overlay");
		assertEquals(2L, edgeCount(result, GraphEdgeType.SELECTS_MODEL), "composite model branches");
		assertTrue(
			result.graph().edges().stream()
				.filter(edge -> edge.type() == GraphEdgeType.SELECTS_MODEL)
				.allMatch(edge -> edge.label().contains("composite layer")),
			"composite branch labels"
		);
	}

	private static void testConditionSelectAndRange(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "multistate_test");
		assertFalse(result.hasErrors(), "multi-state item should resolve without errors");
		assertEquals(
			Set.of("item/simple", "item/range_low", "item/range_high", "item/range_fallback"),
			logicalPaths(result, GraphNodeType.MODEL_FILE).stream()
				.filter(path -> !path.equals("item/generated"))
				.collect(Collectors.toSet()),
			"all multi-state render models"
		);
		assertEquals(5L, edgeCount(result, GraphEdgeType.SELECTS_MODEL), "all multi-state branches");
		assertBranch(result, "minecraft:using_item = false");
		assertBranch(result, "minecraft:use_duration ≥ 0.5");
		assertBranch(result, "minecraft:display_context fallback");
	}

	private static void testUnsupportedDefinitionIsDiagnostic(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "unsupported_test");
		assertFalse(result.hasErrors(), "unsupported definition should warn without crashing");
		assertContainsNode(result, GraphNodeType.UNKNOWN_RESOURCE, "minecraft:future_dispatch@$.model");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/simple");
		assertTrue(
			result.issues().stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_PREVIEW")),
			"unsupported preview warning"
		);
	}

	private static void testSpecialRenderer(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "special_test");
		assertFalse(result.hasErrors(), "special item should warn without crashing");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "item/special_base");
		assertTrue(
			!logicalPaths(result, GraphNodeType.SPECIAL_RENDERER).isEmpty(),
			"special renderer marker"
		);
		assertTrue(
			result.issues().stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_PREVIEW")),
			"special preview warning"
		);
	}

	private static void testMixedAtlasValidation(ItemDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "mixed_atlas_test");
		assertTrue(result.hasErrors(), "mixed item and block atlases should be invalid");
		assertEquals(Set.of("items", "blocks"), logicalPaths(result, GraphNodeType.ATLAS_FILE), "atlases");
		assertTrue(
			result.issues().stream().anyMatch(issue -> issue.code().equals("MIXED_ITEM_ATLASES")),
			"mixed-atlas issue"
		);
	}

	private static AssetResolutionResult resolve(ItemDependencyResolver resolver, String path) {
		return resolver.resolve(new AssetKey(AssetKind.ITEM, "minecraft", path));
	}

	private static long edgeCount(AssetResolutionResult result, GraphEdgeType type) {
		return result.graph().edges().stream().filter(edge -> edge.type() == type).count();
	}

	private static Set<String> logicalPaths(AssetResolutionResult result, GraphNodeType type) {
		return result.graph().nodes().values().stream()
			.filter(node -> node.type() == type)
			.map(AssetGraphNode::logicalPath)
			.collect(Collectors.toSet());
	}

	private static void assertContainsNode(
		AssetResolutionResult result,
		GraphNodeType type,
		String logicalPath
	) {
		assertTrue(
			result.graph().nodes().values().stream()
				.anyMatch(node -> node.type() == type && node.logicalPath().equals(logicalPath)),
			"node " + type + " " + logicalPath
		);
	}

	private static void assertBranch(AssetResolutionResult result, String fragment) {
		assertTrue(
			result.graph().edges().stream()
				.map(AssetGraphEdge::label)
				.anyMatch(label -> label.contains(fragment)),
			"branch label containing " + fragment
		);
	}

	private static Path fixtureRoot() throws URISyntaxException {
		return Path.of(Objects.requireNonNull(
			ItemDependencyResolverTest.class.getResource("/fixtures/item-resolver"),
			"item resolver fixture"
		).toURI());
	}

	private static void assertTrue(boolean condition, String description) {
		if (!condition) {
			throw new AssertionError("Assertion failed: " + description);
		}
	}

	private static void assertFalse(boolean condition, String description) {
		assertTrue(!condition, description);
	}

	private static void assertEquals(Object expected, Object actual, String description) {
		if (!expected.equals(actual)) {
			throw new AssertionError(
				"Assertion failed for " + description + ": expected=" + expected + ", actual=" + actual
			);
		}
	}
}
