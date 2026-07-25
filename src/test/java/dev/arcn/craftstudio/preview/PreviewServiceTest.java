package dev.arcn.craftstudio.preview;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.preview.application.PreviewService;
import dev.arcn.craftstudio.preview.domain.PreviewMode;
import dev.arcn.craftstudio.preview.domain.PreviewScene;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Face;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Texture;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import dev.arcn.craftstudio.resource.infrastructure.LayeredPreviewAssetSource;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class PreviewServiceTest {
	private static final byte[] VALID_PNG = java.util.Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
			+ "AAAADUlEQVR4XmNg+M/wHwAEAQH/zXQBagAAAABJRU5ErkJggg=="
	);
	private static final ResourcePath CRAFTING_TOP =
		new ResourcePath("minecraft", "textures/block/crafting_table_top.png");

	private PreviewServiceTest() {
	}

	public static void main(String[] args) throws Exception {
		AssetSource vanilla = new LayerSource(
			new ProjectAssetSource(fixtureRoot(), "preview-fixture"),
			SourceLayer.VANILLA_BASE
		);
		testCraftingTableFaceAssignment(vanilla);
		testFurnaceVariantsAndRotation(vanilla);
		testProjectLayerOverridesVanilla(vanilla);
		testMissingTextureIsFaceSpecific(vanilla);
		testSpecialRendererLimitation(vanilla);
		System.out.println("Preview service tests passed.");
	}

	private static void testCraftingTableFaceAssignment(AssetSource source) {
		PreviewScene scene = scene(source, "crafting_table");
		assertEquals(1, scene.variants(PreviewMode.BLOCK).size(), "crafting table block variants");
		assertEquals(1, scene.variants(PreviewMode.ITEM).size(), "crafting table item variants");
		Variant block = scene.variants(PreviewMode.BLOCK).getFirst();
		assertEquals(6, block.faces().size(), "crafting table cube faces");
		assertTexture(block, "up", "textures/block/crafting_table_top.png");
		assertTexture(block, "down", "textures/block/oak_planks.png");
		assertTexture(block, "north", "textures/block/crafting_table_front.png");
		assertTexture(block, "west", "textures/block/crafting_table_front.png");
		assertTexture(block, "east", "textures/block/crafting_table_side.png");
		assertTexture(block, "south", "textures/block/crafting_table_side.png");
		assertEquals(0L, block.missingFaceCount(), "crafting table missing faces");
	}

	private static void testFurnaceVariantsAndRotation(AssetSource source) {
		PreviewScene scene = scene(source, "furnace");
		assertEquals(8, scene.variants(PreviewMode.BLOCK).size(), "furnace state variants");
		Variant unlitNorth = variant(scene, "facing", "north", "lit", "false");
		Variant litNorth = variant(scene, "facing", "north", "lit", "true");
		Variant unlitEast = variant(scene, "facing", "east", "lit", "false");
		assertTexture(unlitNorth, "north", "textures/block/furnace_front.png");
		assertTexture(litNorth, "north", "textures/block/furnace_front_on.png");
		Face northFront = face(unlitNorth, "north");
		assertTrue(
			northFront.vertices().stream().allMatch(vertex -> nearly(vertex.z(), 0.0F)),
			"north front remains on the north plane"
		);
		Face eastFront = face(unlitEast, "north");
		assertTrue(
			eastFront.vertices().stream().allMatch(vertex -> nearly(vertex.x(), 0.0F)),
			"east-facing application rotates the front plane"
		);
	}

	private static void testProjectLayerOverridesVanilla(AssetSource vanilla) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-preview-layer-");
		try {
			byte[] override = VALID_PNG.clone();
			Path overridePath = temporaryRoot.resolve(CRAFTING_TOP.packPath());
			Files.createDirectories(overridePath.getParent());
			Files.write(overridePath, override);
			LayeredPreviewAssetSource layered = new LayeredPreviewAssetSource(
				new ProjectAssetSource(temporaryRoot, "project-layer"),
				vanilla
			);
			PreviewScene scene = scene(layered, "crafting_table");
			Texture top = texture(scene.variants(PreviewMode.BLOCK).getFirst(), CRAFTING_TOP);
			assertEquals(SourceLayer.PROJECT, top.sourceLayer(), "project override layer");
			assertTrue(Arrays.equals(override, top.pngBytes()), "project override bytes");
			Texture side = texture(
				scene.variants(PreviewMode.BLOCK).getFirst(),
				new ResourcePath("minecraft", "textures/block/crafting_table_side.png")
			);
			assertEquals(SourceLayer.PROJECT, layered.layer(), "layered source identity");
			assertEquals(SourceLayer.PROJECT, top.sourceLayer(), "top is project-backed");
			assertEquals(SourceLayer.PROJECT, layered.read(CRAFTING_TOP).orElseThrow().layer(),
				"layered read project");
			assertEquals(SourceLayer.PROJECT, top.sourceLayer(), "override stays project");
			assertEquals(SourceLayer.VANILLA_BASE, side.sourceLayer(), "unmodified texture falls back");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testMissingTextureIsFaceSpecific(AssetSource vanilla) {
		AssetSource missingTop = new FilteringSource(vanilla, CRAFTING_TOP);
		PreviewScene scene = scene(missingTop, "crafting_table");
		Variant block = scene.variants(PreviewMode.BLOCK).getFirst();
		assertEquals(1L, block.missingFaceCount(), "only the unresolved top face is missing");
		assertTrue(face(block, "up").missingTexture(), "top face missing marker");
		assertTrue(!face(block, "north").missingTexture(), "front remains resolved");
	}

	private static void testSpecialRendererLimitation(AssetSource source) {
		PreviewScene scene = scene(source, "special_test");
		assertTrue(
			scene.diagnostics().stream().anyMatch(line -> line.contains("special renderer")),
			"special renderer limitation"
		);
	}

	private static PreviewScene scene(AssetSource source, String blockPath) {
		AssetKey root = new AssetKey(AssetKind.BLOCK, "minecraft", blockPath);
		AssetResolutionResult resolution =
			new BlockDependencyResolver(source, "1.21.11").resolve(root);
		return new PreviewService(source, "1.21.11").createScene(resolution);
	}

	private static Variant variant(
		PreviewScene scene,
		String keyA,
		String valueA,
		String keyB,
		String valueB
	) {
		return scene.variants(PreviewMode.BLOCK).stream()
			.filter(candidate -> valueA.equals(candidate.properties().get(keyA)))
			.filter(candidate -> valueB.equals(candidate.properties().get(keyB)))
			.findFirst()
			.orElseThrow();
	}

	private static Face face(Variant variant, String direction) {
		return variant.faces().stream()
			.filter(candidate -> candidate.direction().equals(direction))
			.findFirst()
			.orElseThrow();
	}

	private static void assertTexture(Variant variant, String direction, String pathSuffix) {
		Face face = face(variant, direction);
		assertTrue(face.textureKey().contains(pathSuffix), direction + " texture path");
		assertTrue(!face.missingTexture(), direction + " texture resolved");
	}

	private static Texture texture(Variant variant, ResourcePath path) {
		return variant.textures().values().stream()
			.filter(candidate -> candidate.path().equals(path))
			.findFirst()
			.orElseThrow();
	}

	private static boolean nearly(float value, float expected) {
		return Math.abs(value - expected) < 0.001F;
	}

	private static Path fixtureRoot() throws URISyntaxException {
		return Path.of(PreviewServiceTest.class.getResource("/fixtures/block-resolver").toURI());
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

	private static void deleteRecursively(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private record FilteringSource(AssetSource delegate, ResourcePath filtered) implements AssetSource {
		@Override
		public Optional<ResourceData> read(ResourcePath path) throws IOException {
			return path.equals(filtered) ? Optional.empty() : delegate.read(path);
		}

		@Override
		public Stream<ResourcePath> list(String namespace, String prefix) throws IOException {
			return delegate.list(namespace, prefix).filter(path -> !path.equals(filtered));
		}

		@Override
		public SourceLayer layer() {
			return delegate.layer();
		}

		@Override
		public String revision() {
			return delegate.revision() + ":without:" + filtered.packPath();
		}
	}

	private record LayerSource(AssetSource delegate, SourceLayer layer) implements AssetSource {
		@Override
		public Optional<ResourceData> read(ResourcePath path) throws IOException {
			return delegate.read(path).map(data -> new ResourceData(
				data.path(),
				path.path().endsWith(".png") ? VALID_PNG : data.bytes(),
				layer,
				revision()
			));
		}

		@Override
		public Stream<ResourcePath> list(String namespace, String prefix) throws IOException {
			return delegate.list(namespace, prefix);
		}

		@Override
		public String revision() {
			return delegate.revision() + ":" + layer;
		}
	}
}
