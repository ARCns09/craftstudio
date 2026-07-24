package dev.arcn.craftstudio.graph;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class BlockDependencyResolverTest {
	private BlockDependencyResolverTest() {
	}

	public static void main(String[] args) throws Exception {
		BlockDependencyResolver resolver = new BlockDependencyResolver(
			new ProjectAssetSource(fixtureRoot(), "block-resolver-fixture"),
			"1.21.11"
		);
		testStoneVariants(resolver);
		testCraftingTableDependencies(resolver);
		testFurnaceBranchesAndMetadata(resolver);
		testMultipartAndWeightedAlternatives(resolver);
		testMissingFilesAndCyclesBecomeIssues(resolver);
		testTextureCyclesAndSpecialRenderers(resolver);
		System.out.println("Block dependency resolver tests passed.");
	}

	private static void testStoneVariants(BlockDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "stone");
		assertFalse(result.hasErrors(), "stone should resolve without errors");
		assertEquals(4L, edgeCount(result, GraphEdgeType.HAS_VARIANT), "stone variant alternatives");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "block/stone");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "block/stone_mirrored");
		assertContainsNode(result, GraphNodeType.TEXTURE_FILE, "block/stone");
		assertContainsNode(result, GraphNodeType.CLIENT_ITEM_FILE, "items/stone.json");
	}

	private static void testCraftingTableDependencies(BlockDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "crafting_table");
		assertFalse(result.hasErrors(), "crafting table should resolve without errors");
		assertContainsNode(result, GraphNodeType.BLOCKSTATE_FILE, "blockstates/crafting_table.json");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "block/crafting_table");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "block/cube");
		assertContainsNode(result, GraphNodeType.MODEL_FILE, "block/block");
		assertEquals(2L, edgeCount(result, GraphEdgeType.INHERITS_MODEL), "crafting table parent chain");
		assertEquals(
			Set.of(
				"block/oak_planks",
				"block/crafting_table_front",
				"block/crafting_table_side",
				"block/crafting_table_top"
			),
			logicalPaths(result, GraphNodeType.TEXTURE_FILE),
			"crafting table textures"
		);
		assertTrue(
			edgeCount(result, GraphEdgeType.USES_TEXTURE_VARIABLE) >= 4,
			"crafting table texture variables"
		);
		assertContainsNode(result, GraphNodeType.ATLAS_FILE, "blocks");
		assertContainsNode(result, GraphNodeType.CLIENT_ITEM_FILE, "items/crafting_table.json");
		assertTrue(edgeCount(result, GraphEdgeType.SELECTS_MODEL) >= 1, "crafting table item model");
	}

	private static void testFurnaceBranchesAndMetadata(BlockDependencyResolver resolver) {
		AssetResolutionResult result = resolve(resolver, "furnace");
		assertFalse(result.hasErrors(), "furnace should resolve without errors");
		assertEquals(8L, edgeCount(result, GraphEdgeType.HAS_VARIANT), "furnace branches");
		List<String> branchLabels = result.graph().edges().stream()
			.filter(edge -> edge.type() == GraphEdgeType.HAS_VARIANT)
			.map(AssetGraphEdge::label)
			.toList();
		assertTrue(branchLabels.stream().anyMatch(label -> label.contains("lit=false")), "unlit branch");
		assertTrue(branchLabels.stream().anyMatch(label -> label.contains("lit=true")), "lit branch");
		assertTrue(branchLabels.stream().anyMatch(label -> label.contains("y=270")), "rotation metadata");
		assertEquals(
			Set.of(
				"block/furnace_front",
				"block/furnace_front_on",
				"block/furnace_side",
				"block/furnace_top"
			),
			logicalPaths(result, GraphNodeType.TEXTURE_FILE),
			"furnace textures"
		);
		assertContainsNode(
			result,
			GraphNodeType.TEXTURE_METADATA_FILE,
			"block/furnace_front_on.png.mcmeta"
		);
		assertContainsNode(result, GraphNodeType.CLIENT_ITEM_FILE, "items/furnace.json");
		assertEquals(0, result.stats().missingCount(), "furnace missing dependencies");
	}

	private static void testMultipartAndWeightedAlternatives(BlockDependencyResolver resolver) {
		AssetResolutionResult fence = resolve(resolver, "oak_fence");
		assertFalse(fence.hasErrors(), "multipart fence should resolve without errors");
		assertEquals(5L, edgeCount(fence, GraphEdgeType.HAS_MULTIPART_CASE), "multipart cases");
		assertTrue(
			fence.graph().edges().stream()
				.anyMatch(edge -> edge.label().contains("north=true") && edge.label().contains("uvlock=true")),
			"multipart condition and uvlock"
		);

		AssetResolutionResult weighted = resolve(resolver, "weighted_test");
		assertFalse(weighted.hasErrors(), "weighted variants should resolve without errors");
		assertEquals(2L, edgeCount(weighted, GraphEdgeType.HAS_VARIANT), "weighted alternatives");
		assertTrue(
			weighted.graph().edges().stream()
				.anyMatch(edge -> edge.type() == GraphEdgeType.HAS_VARIANT && edge.label().contains("weight=3")),
			"weighted model metadata"
		);
	}

	private static void testMissingFilesAndCyclesBecomeIssues(BlockDependencyResolver resolver) {
		AssetResolutionResult broken = resolve(resolver, "broken_test");
		assertTrue(broken.hasErrors(), "missing model should produce an error");
		assertTrue(
			broken.issues().stream().anyMatch(issue -> issue.code().equals("MISSING_MODEL")),
			"missing model issue"
		);
		assertTrue(broken.stats().missingCount() > 0, "missing node count");

		AssetResolutionResult cycle = resolve(resolver, "cycle_test");
		assertTrue(cycle.hasErrors(), "parent cycle should produce an error");
		assertTrue(
			cycle.issues().stream().anyMatch(issue -> issue.code().equals("MODEL_PARENT_CYCLE")),
			"model parent cycle issue"
		);
	}

	private static void testTextureCyclesAndSpecialRenderers(BlockDependencyResolver resolver) {
		AssetResolutionResult textureCycle = resolve(resolver, "texture_cycle_test");
		assertTrue(textureCycle.hasErrors(), "texture-variable cycle should produce an error");
		assertTrue(
			textureCycle.issues().stream().anyMatch(issue -> issue.code().equals("TEXTURE_VARIABLE_CYCLE")),
			"texture variable cycle issue"
		);

		AssetResolutionResult special = resolve(resolver, "special_test");
		assertFalse(special.hasErrors(), "special renderer should be a truthful limitation, not an error");
		assertTrue(
			!logicalPaths(special, GraphNodeType.SPECIAL_RENDERER).isEmpty(),
			"special renderer marker"
		);
		assertTrue(
			special.graph().edges().stream()
				.anyMatch(edge -> edge.type() == GraphEdgeType.USES_SPECIAL_RENDERER),
			"special renderer edge"
		);
	}

	private static AssetResolutionResult resolve(BlockDependencyResolver resolver, String path) {
		return resolver.resolve(new AssetKey(AssetKind.BLOCK, "minecraft", path));
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

	private static Path fixtureRoot() throws URISyntaxException {
		return Path.of(Objects.requireNonNull(
			BlockDependencyResolverTest.class.getResource("/fixtures/block-resolver"),
			"block resolver fixture"
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
