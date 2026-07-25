package dev.arcn.craftstudio.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.export.application.ExportService;
import dev.arcn.craftstudio.export.application.ZipEntrySafety;
import dev.arcn.craftstudio.export.domain.ExistingOutputException;
import dev.arcn.craftstudio.export.domain.ExistingOutputPolicy;
import dev.arcn.craftstudio.export.domain.ExportBlockedException;
import dev.arcn.craftstudio.export.domain.ExportRequest;
import dev.arcn.craftstudio.export.domain.ExportResult;
import dev.arcn.craftstudio.export.domain.ExportType;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.task.OperationCancellation;
import dev.arcn.craftstudio.project.application.BundleService;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import dev.arcn.craftstudio.validation.application.ValidationService;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import dev.arcn.craftstudio.validation.domain.ValidationSeverity;
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
import java.util.Base64;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

public final class ValidationExportTest {
	private static final Instant FIXED_TIME = Instant.parse("2026-07-25T15:00:00Z");
	private static final ResourcePath FURNACE_FRONT =
		ResourcePath.fromPackPath("assets/minecraft/textures/block/furnace_front.png");

	private ValidationExportTest() {
	}

	public static void main(String[] args) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-validation-export-");
		try {
			TestServices services = createServices();
			CraftStudioProject project = createCompleteFurnaceProject(temporaryRoot, services);
			testValidProjectValidation(project, services);
			testFolderZipAndInstanceExports(temporaryRoot, project, services);
			testSafeOverwrite(temporaryRoot, project, services);
			testInvalidProjectBlocksExport(temporaryRoot, project, services);
			testMissingSelectedDependency(project, services);
			testZipEntrySafety();
			testExportNameSafety(temporaryRoot);
			System.out.println("Validation and export tests passed.");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testValidProjectValidation(
		CraftStudioProject project,
		TestServices services
	) {
		ValidationReport report = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertEquals(
			0L,
			report.count(ValidationSeverity.ERROR),
			"valid project errors " + report.issues().stream()
				.filter(issue -> issue.severity() == ValidationSeverity.ERROR)
				.toList()
		);
		assertTrue(report.canExport(), "valid project can export");
		assertTrue(report.fileCount() > 1, "validation counts files");
		assertTrue(
			report.count(ValidationSeverity.PASSED) >= 3,
			"validation records passed checks"
		);
	}

	private static void testFolderZipAndInstanceExports(
		Path temporaryRoot,
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path outputRoot = temporaryRoot.resolve("outputs");
		ExportResult folder = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.FOLDER,
				outputRoot,
				"Furnace Folder",
				ExistingOutputPolicy.CANCEL
			),
			new OperationCancellation()
		);
		assertTrue(Files.isRegularFile(folder.output().resolve("pack.mcmeta")), "folder root metadata");
		assertTrue(!Files.exists(folder.output().resolve("craftstudio.project.json")), "no project metadata");
		assertTrue(!Files.exists(folder.output().resolve(".craftstudio")), "no project internals");
		assertTrue(Files.isRegularFile(folder.report()), "folder export report");

		ExportResult zip = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.ZIP,
				outputRoot,
				"Furnace ZIP",
				ExistingOutputPolicy.CANCEL
			),
			new OperationCancellation()
		);
		try (ZipFile archive = new ZipFile(zip.output().toFile())) {
			assertTrue(archive.getEntry("pack.mcmeta") != null, "ZIP root metadata");
			assertTrue(archive.getEntry("Furnace ZIP/pack.mcmeta") == null, "no enclosing ZIP folder");
			assertTrue(archive.getEntry("craftstudio.project.json") == null, "ZIP excludes project metadata");
		}
		JsonObject report = JsonParser.parseString(Files.readString(
			zip.report(),
			StandardCharsets.UTF_8
		)).getAsJsonObject();
		assertEquals("1.21.11", report.get("minecraft_version").getAsString(), "report target");
		assertEquals(zip.output().toString(), report.get("output_path").getAsString(), "report output");
		assertEquals(zip.sha256(), report.get("sha256").getAsString(), "report hash");

		Path resourcePacks = temporaryRoot.resolve("game/resourcepacks");
		ExportResult installed = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.CURRENT_INSTANCE,
				resourcePacks,
				"Installed Furnace",
				ExistingOutputPolicy.CANCEL
			),
			new OperationCancellation()
		);
		assertTrue(
			Files.isRegularFile(installed.output().resolve("pack.mcmeta")),
			"current-instance folder install"
		);
	}

	private static void testSafeOverwrite(
		Path temporaryRoot,
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path outputRoot = temporaryRoot.resolve("overwrite");
		ExportRequest initialRequest = new ExportRequest(
			ExportType.FOLDER,
			outputRoot,
			"Existing Pack",
			ExistingOutputPolicy.CANCEL
		);
		ExportResult initial = services.exportService().export(
			project,
			initialRequest,
			new OperationCancellation()
		);
		Path marker = initial.output().resolve("existing-user-file.txt");
		Files.writeString(marker, "keep me", StandardCharsets.UTF_8);
		try {
			services.exportService().export(
				project,
				initialRequest,
				new OperationCancellation()
			);
			throw new AssertionError("Existing output should require an explicit policy.");
		} catch (ExistingOutputException expected) {
			assertTrue(Files.isRegularFile(marker), "cancel keeps existing output");
		}

		ExportResult replaced = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.FOLDER,
				outputRoot,
				"Existing Pack",
				ExistingOutputPolicy.REPLACE_WITH_BACKUP
			),
			new OperationCancellation()
		);
		assertTrue(replaced.backup().isPresent(), "replacement reports backup");
		try (Stream<Path> backupFiles = Files.walk(replaced.backup().orElseThrow())) {
			assertTrue(
				backupFiles.anyMatch(path -> path.getFileName().toString().equals("existing-user-file.txt")),
				"backup preserves previous output"
			);
		}
		assertTrue(!Files.exists(replaced.output().resolve("existing-user-file.txt")),
			"published output is clean staging");
	}

	private static void testInvalidProjectBlocksExport(
		Path temporaryRoot,
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path texture = project.packRoot().resolve(FURNACE_FRONT.packPath());
		byte[] validTexture = Files.readAllBytes(texture);
		Files.writeString(texture, "not a PNG", StandardCharsets.UTF_8);
		ValidationReport invalid = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertTrue(!invalid.canExport(), "broken PNG blocks export");
		assertTrue(
			invalid.issues().stream().anyMatch(issue -> issue.code().equals("INVALID_PNG")),
			"broken PNG is located"
		);
		Path blockedOutput = temporaryRoot.resolve("blocked");
		try {
			services.exportService().export(
				project,
				new ExportRequest(
					ExportType.ZIP,
					blockedOutput,
					"Broken",
					ExistingOutputPolicy.CANCEL
				),
				new OperationCancellation()
			);
			throw new AssertionError("Invalid project should not export.");
		} catch (ExportBlockedException expected) {
			assertTrue(!Files.exists(blockedOutput.resolve("Broken.zip")), "blocked output is absent");
		}
		Files.write(texture, validTexture);
	}

	private static void testMissingSelectedDependency(
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path texture = project.packRoot().resolve(FURNACE_FRONT.packPath());
		byte[] original = Files.readAllBytes(texture);
		Files.delete(texture);
		ValidationReport report = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertTrue(
			report.issues().stream().anyMatch(issue ->
				issue.code().equals("SELECTED_BUNDLE_FILE_MISSING")
					&& issue.packPath().equals(FURNACE_FRONT.packPath())),
			"missing complete-bundle texture is located"
		);
		Files.write(texture, original);
	}

	private static void testZipEntrySafety() throws Exception {
		ZipEntrySafety.requireSafe("pack.mcmeta");
		ZipEntrySafety.requireSafe("assets/minecraft/textures/block/furnace.png");
		expectUnsafeZip("../outside");
		expectUnsafeZip("/absolute");
		expectUnsafeZip("assets\\minecraft\\bad.png");
	}

	private static void expectUnsafeZip(String entry) throws Exception {
		try {
			ZipEntrySafety.requireSafe(entry);
			throw new AssertionError("Expected unsafe ZIP entry rejection: " + entry);
		} catch (IOException expected) {
			// Expected.
		}
	}

	private static void testExportNameSafety(Path temporaryRoot) {
		try {
			new ExportRequest(
				ExportType.FOLDER,
				temporaryRoot,
				"CON.txt",
				ExistingOutputPolicy.CANCEL
			);
			throw new AssertionError("Expected cross-platform reserved export name rejection.");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static TestServices createServices() throws Exception {
		TargetVersionManifest target = TargetVersionManifest.minecraft_1_21_11();
		AtomicFileWriter writer = new AtomicFileWriter();
		Clock clock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
		ProjectMetadataRepository metadataRepository = new ProjectMetadataRepository(writer);
		ProjectAssetSource fixture = new ProjectAssetSource(fixtureRoot(), "validation-fixture");
		ValidationService validation = new ValidationService(target, fixture, clock);
		return new TestServices(
			new ProjectService(
				target,
				metadataRepository,
				new PackMetadataWriter(writer),
				writer,
				clock
			),
			new BundleService(fixture, metadataRepository, writer, clock),
			new BlockDependencyResolver(fixture, target.minecraftVersion()),
			validation,
			new ExportService(target, validation, writer, clock)
		);
	}

	private static CraftStudioProject createCompleteFurnaceProject(
		Path temporaryRoot,
		TestServices services
	) throws Exception {
		CraftStudioProject project = services.projectService().createProject(
			new ProjectCreationRequest(
				"Validation Furnace",
				"validation-furnace",
				"Validation fixture",
				"ARCn09",
				temporaryRoot.resolve("workspace")
			)
		);
		AssetResolutionResult resolution = services.resolver().resolve(
			new AssetKey(AssetKind.BLOCK, "minecraft", "furnace")
		);
		CopyPlan plan = services.bundleService().createCopyPlan(
			project,
			resolution,
			SelectionMode.COMPLETE,
			Set.of()
		);
		CraftStudioProject completed = services.bundleService()
			.addToProject(project, plan, Set.of())
			.project();
		byte[] validPng = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
		);
		try (Stream<Path> files = Files.walk(completed.packRoot())) {
			for (Path png : files.filter(path -> path.getFileName().toString().endsWith(".png")).toList()) {
				Files.write(png, validPng);
			}
		}
		return completed;
	}

	private static Path fixtureRoot() throws URISyntaxException {
		return Path.of(ValidationExportTest.class.getResource("/fixtures/block-resolver").toURI());
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

	private record TestServices(
		ProjectService projectService,
		BundleService bundleService,
		BlockDependencyResolver resolver,
		ValidationService validationService,
		ExportService exportService
	) {
	}
}
