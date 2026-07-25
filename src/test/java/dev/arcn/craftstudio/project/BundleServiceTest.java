package dev.arcn.craftstudio.project;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.DependencyClassification;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.application.BundleService;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.BundleOperationResult;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.CopyPlan.DestinationState;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.RemovalPlan;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

public final class BundleServiceTest {
	private static final Instant FIXED_TIME = Instant.parse("2026-07-25T12:00:00Z");
	private static final ResourcePath FURNACE_FRONT =
		ResourcePath.fromPackPath("assets/minecraft/textures/block/furnace_front.png");

	private BundleServiceTest() {
	}

	public static void main(String[] args) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-bundle-service-");
		try {
			TestServices services = createServices();
			AssetResolutionResult furnace = resolve(services.resolver(), "furnace");
			AssetResolutionResult craftingTable = resolve(services.resolver(), "crafting_table");
			testCompleteMode(temporaryRoot, services, furnace);
			testUniqueOnlyMode(temporaryRoot, services, craftingTable);
			testCustomMode(temporaryRoot, services, furnace);
			testConflictAndRestore(temporaryRoot, services, furnace);
			testSafeRemoval(temporaryRoot, services, furnace, craftingTable);
			System.out.println("Bundle service tests passed.");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testCompleteMode(
		Path temporaryRoot,
		TestServices services,
		AssetResolutionResult furnace
	) throws Exception {
		CraftStudioProject project = createProject(temporaryRoot, services, "complete");
		CopyPlan plan = services.bundleService().createCopyPlan(
			project,
			furnace,
			SelectionMode.COMPLETE,
			Set.of()
		);
		assertTrue(plan.entries().stream().allMatch(CopyPlan.Entry::selected), "complete selects every file");
		BundleOperationResult result = services.bundleService().addToProject(project, plan, Set.of());
		assertEquals(plan.entries().size(), result.copiedFiles(), "complete copied file count");
		for (CopyPlan.Entry entry : plan.entries()) {
			assertProjectMatchesFixture(result.project(), entry.path(), "complete file");
		}
		assertEquals(1, result.project().metadata().selectedRoots().size(), "selected root count");
		assertEquals("complete", result.project().metadata().selectedRoots().getFirst().selectionMode(), "mode");
	}

	private static void testUniqueOnlyMode(
		Path temporaryRoot,
		TestServices services,
		AssetResolutionResult craftingTable
	) throws Exception {
		CraftStudioProject project = createProject(temporaryRoot, services, "unique");
		CopyPlan plan = services.bundleService().createCopyPlan(
			project,
			craftingTable,
			SelectionMode.UNIQUE_ONLY,
			Set.of()
		);
		assertTrue(
			plan.entries().stream().anyMatch(entry ->
				entry.classification() == DependencyClassification.SHARED_VANILLA && !entry.selected()),
			"unique-only excludes shared vanilla leaves"
		);
		BundleOperationResult result = services.bundleService().addToProject(project, plan, Set.of());
		for (CopyPlan.Entry entry : plan.entries()) {
			Path destination = destination(result.project(), entry.path());
			assertEquals(entry.selected(), Files.isRegularFile(destination), "unique-only selection");
		}
	}

	private static void testCustomMode(
		Path temporaryRoot,
		TestServices services,
		AssetResolutionResult furnace
	) throws Exception {
		CraftStudioProject project = createProject(temporaryRoot, services, "custom");
		CopyPlan plan = services.bundleService().createCopyPlan(
			project,
			furnace,
			SelectionMode.CUSTOM,
			Set.of(FURNACE_FRONT.packPath())
		);
		assertTrue(plan.entries().stream().filter(CopyPlan.Entry::required)
			.allMatch(CopyPlan.Entry::selected), "custom locks required files");
		assertTrue(find(plan, FURNACE_FRONT).selected(), "custom includes checked texture");
		assertTrue(plan.entries().stream().anyMatch(entry -> !entry.required() && !entry.selected()),
			"custom leaves unchecked optional files out");
		BundleOperationResult result = services.bundleService().addToProject(project, plan, Set.of());
		assertTrue(Files.isRegularFile(destination(result.project(), FURNACE_FRONT)), "custom texture copied");
		RemovalPlan removal = services.bundleService().createRemovalPlan(
			result.project(),
			furnace,
			java.util.List.of()
		);
		assertTrue(removal.removableFiles().isEmpty(), "custom removal is conservative");
		assertTrue(!removal.modifiedFiles().isEmpty(), "custom-selected files are retained");
	}

	private static void testConflictAndRestore(
		Path temporaryRoot,
		TestServices services,
		AssetResolutionResult furnace
	) throws Exception {
		CraftStudioProject project = createProject(temporaryRoot, services, "conflict");
		CopyPlan initial = services.bundleService().createCopyPlan(
			project,
			furnace,
			SelectionMode.COMPLETE,
			Set.of()
		);
		project = services.bundleService().addToProject(project, initial, Set.of()).project();
		Path editedFile = destination(project, FURNACE_FRONT);
		byte[] edit = "manual edit".getBytes(StandardCharsets.UTF_8);
		Files.write(editedFile, edit);

		CopyPlan conflict = services.bundleService().createCopyPlan(
			project,
			furnace,
			SelectionMode.COMPLETE,
			Set.of()
		);
		assertEquals(DestinationState.DIFFERENT, find(conflict, FURNACE_FRONT).destinationState(), "conflict");
		BundleOperationResult kept = services.bundleService().addToProject(project, conflict, Set.of());
		assertArrayEquals(edit, Files.readAllBytes(editedFile), "keep existing is the default");

		BundleOperationResult replaced = services.bundleService().addToProject(
			kept.project(),
			conflict,
			Set.of(FURNACE_FRONT.packPath())
		);
		assertTrue(replaced.backupPath().isPresent(), "replace creates backup");
		assertProjectMatchesFixture(replaced.project(), FURNACE_FRONT, "replaced file");

		Files.write(editedFile, edit);
		BundleOperationResult restored = services.bundleService().restoreVanilla(replaced.project(), furnace);
		assertTrue(restored.backupPath().isPresent(), "restore creates backup");
		assertProjectMatchesFixture(restored.project(), FURNACE_FRONT, "restored file");
	}

	private static void testSafeRemoval(
		Path temporaryRoot,
		TestServices services,
		AssetResolutionResult furnace,
		AssetResolutionResult craftingTable
	) throws Exception {
		CraftStudioProject project = createProject(temporaryRoot, services, "remove");
		CopyPlan furnacePlan = services.bundleService().createCopyPlan(
			project,
			furnace,
			SelectionMode.COMPLETE,
			Set.of()
		);
		project = services.bundleService().addToProject(project, furnacePlan, Set.of()).project();
		CopyPlan tablePlan = services.bundleService().createCopyPlan(
			project,
			craftingTable,
			SelectionMode.COMPLETE,
			Set.of()
		);
		project = services.bundleService().addToProject(project, tablePlan, Set.of()).project();
		Files.writeString(destination(project, FURNACE_FRONT), "manual edit", StandardCharsets.UTF_8);

		RemovalPlan plan = services.bundleService().createRemovalPlan(
			project,
			furnace,
			java.util.List.of(craftingTable)
		);
		assertTrue(plan.modifiedFiles().contains(FURNACE_FRONT), "removal preserves edited files");
		assertTrue(!plan.sharedFiles().isEmpty(), "removal recognizes shared files");
		assertTrue(!plan.removableFiles().isEmpty(), "removal finds exclusive vanilla files");
		ResourcePath removable = plan.removableFiles().getFirst();
		ResourcePath shared = plan.sharedFiles().getFirst();
		BundleOperationResult removed = services.bundleService().removeRoot(project, plan);
		assertTrue(removed.backupPath().isPresent(), "removal creates backup");
		assertTrue(!Files.exists(destination(removed.project(), removable)), "exclusive file removed");
		assertTrue(Files.exists(destination(removed.project(), shared)), "shared file retained");
		assertTrue(Files.exists(destination(removed.project(), FURNACE_FRONT)), "edited file retained");
		assertEquals(1, removed.project().metadata().selectedRoots().size(), "only other root remains");
		assertEquals("minecraft:crafting_table", removed.project().metadata().selectedRoots().getFirst().id(),
			"remaining root");
	}

	private static TestServices createServices() throws Exception {
		AtomicFileWriter writer = new AtomicFileWriter();
		Clock clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
		ProjectMetadataRepository metadataRepository = new ProjectMetadataRepository(writer);
		ProjectAssetSource fixture = new ProjectAssetSource(fixtureRoot(), "bundle-fixture");
		return new TestServices(
			new ProjectService(
				TargetVersionManifest.minecraft_1_21_11(),
				metadataRepository,
				new PackMetadataWriter(writer),
				writer,
				clock
			),
			new BundleService(fixture, metadataRepository, writer, clock),
			new BlockDependencyResolver(fixture, "1.21.11")
		);
	}

	private static CraftStudioProject createProject(
		Path temporaryRoot,
		TestServices services,
		String slug
	) throws Exception {
		return services.projectService().createProject(new ProjectCreationRequest(
			slug,
			slug,
			"",
			"ARCn09",
			temporaryRoot.resolve("workspace")
		));
	}

	private static AssetResolutionResult resolve(BlockDependencyResolver resolver, String path) {
		return resolver.resolve(new AssetKey(AssetKind.BLOCK, "minecraft", path));
	}

	private static CopyPlan.Entry find(CopyPlan plan, ResourcePath path) {
		return plan.entries().stream()
			.filter(entry -> entry.path().equals(path))
			.findFirst()
			.orElseThrow();
	}

	private static Path destination(CraftStudioProject project, ResourcePath path) {
		return project.packRoot().resolve(path.packPath());
	}

	private static void assertProjectMatchesFixture(
		CraftStudioProject project,
		ResourcePath path,
		String description
	) throws Exception {
		assertArrayEquals(
			Files.readAllBytes(fixtureRoot().resolve(path.packPath())),
			Files.readAllBytes(destination(project, path)),
			description
		);
	}

	private static Path fixtureRoot() throws URISyntaxException {
		return Path.of(BundleServiceTest.class.getResource("/fixtures/block-resolver").toURI());
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

	private static void assertArrayEquals(byte[] expected, byte[] actual, String description) {
		if (!java.util.Arrays.equals(expected, actual)) {
			throw new AssertionError("Assertion failed: " + description);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private record TestServices(
		ProjectService projectService,
		BundleService bundleService,
		BlockDependencyResolver resolver
	) {
	}
}
