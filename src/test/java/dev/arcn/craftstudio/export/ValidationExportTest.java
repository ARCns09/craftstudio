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
			testModernPackFormatValidation(project, services);
			testZipDestinations(temporaryRoot, project, services);
			testSafeOverwrite(temporaryRoot, project, services);
			testInvalidProjectBlocksExport(temporaryRoot, project, services);
			testMissingSelectedDependency(project, services);
			testProjectInternalsBlockExport(project, services);
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

	private static void testModernPackFormatValidation(
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path metadataPath = project.packRoot().resolve("pack.mcmeta");
		String original = Files.readString(metadataPath, StandardCharsets.UTF_8);
		JsonObject metadata = JsonParser.parseString(original).getAsJsonObject();
		JsonObject pack = metadata.getAsJsonObject("pack");
		pack.remove("max_format");
		Files.writeString(metadataPath, metadata.toString(), StandardCharsets.UTF_8);
		ValidationReport missingRange = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertTrue(
			missingRange.issues().stream().anyMatch(issue ->
				issue.code().equals("PACK_MAX_FORMAT_MISSING")),
			"missing modern format range blocks export"
		);

		pack.add("min_format", JsonParser.parseString("[75, 0]"));
		pack.add("max_format", JsonParser.parseString("[75, 0]"));
		Files.writeString(metadataPath, metadata.toString(), StandardCharsets.UTF_8);
		ValidationReport arrayRange = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertEquals(
			0L,
			arrayRange.count(ValidationSeverity.ERROR),
			"valid major/minor format arrays"
		);
		Files.writeString(metadataPath, original, StandardCharsets.UTF_8);
	}

	private static void testZipDestinations(
		Path temporaryRoot,
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path customOutputRoot = temporaryRoot.resolve("custom-output");
		ExportResult custom = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.CUSTOM_LOCATION,
				customOutputRoot,
				"Custom Furnace",
				ExistingOutputPolicy.CANCEL
			),
			new OperationCancellation()
		);
		assertEquals(
			customOutputRoot.resolve("Custom Furnace.zip").toAbsolutePath(),
			custom.output(),
			"custom ZIP path"
		);
		try (ZipFile archive = new ZipFile(custom.output().toFile())) {
			assertTrue(archive.getEntry("pack.mcmeta") != null, "ZIP root metadata");
			assertTrue(archive.getEntry("Custom Furnace/pack.mcmeta") == null, "no enclosing ZIP folder");
			assertTrue(archive.getEntry("craftstudio.project.json") == null, "ZIP excludes project metadata");
			assertTrue(
				archive.stream().noneMatch(entry -> entry.getName().startsWith(".craftstudio/")),
				"ZIP excludes project internals"
			);
			JsonObject packMetadata = JsonParser.parseString(
				new String(
					archive.getInputStream(archive.getEntry("pack.mcmeta")).readAllBytes(),
					StandardCharsets.UTF_8
				)
			).getAsJsonObject().getAsJsonObject("pack");
			assertEquals(75, packMetadata.get("pack_format").getAsInt(), "ZIP pack format");
			assertEquals(75, packMetadata.get("min_format").getAsInt(), "ZIP minimum format");
			assertEquals(75, packMetadata.get("max_format").getAsInt(), "ZIP maximum format");
		}
		JsonObject report = JsonParser.parseString(Files.readString(
			custom.report(),
			StandardCharsets.UTF_8
		)).getAsJsonObject();
		assertEquals("1.21.11", report.get("minecraft_version").getAsString(), "report target");
		assertEquals(
			"custom_location",
			report.get("export_type").getAsString(),
			"report destination type"
		);
		assertEquals(custom.output().toString(), report.get("output_path").getAsString(), "report output");
		assertEquals(custom.sha256(), report.get("sha256").getAsString(), "report hash");

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
			Files.isRegularFile(installed.output())
				&& installed.output().equals(
					resourcePacks.resolve("Installed Furnace.zip").toAbsolutePath()
				),
			"current-instance ZIP install"
		);
		try (ZipFile archive = new ZipFile(installed.output().toFile())) {
			assertTrue(archive.getEntry("pack.mcmeta") != null, "installed ZIP root metadata");
		}
	}

	private static void testSafeOverwrite(
		Path temporaryRoot,
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path outputRoot = temporaryRoot.resolve("overwrite");
		ExportRequest initialRequest = new ExportRequest(
			ExportType.CUSTOM_LOCATION,
			outputRoot,
			"Existing Pack",
			ExistingOutputPolicy.CANCEL
		);
		ExportResult initial = services.exportService().export(
			project,
			initialRequest,
			new OperationCancellation()
		);
		byte[] originalOutput = "existing user ZIP bytes".getBytes(StandardCharsets.UTF_8);
		Files.write(initial.output(), originalOutput);
		try {
			services.exportService().export(
				project,
				initialRequest,
				new OperationCancellation()
			);
			throw new AssertionError("Existing output should require an explicit policy.");
		} catch (ExistingOutputException expected) {
			assertTrue(
				java.util.Arrays.equals(originalOutput, Files.readAllBytes(initial.output())),
				"cancel keeps existing output"
			);
		}

		ExportResult replaced = services.exportService().export(
			project,
			new ExportRequest(
				ExportType.CUSTOM_LOCATION,
				outputRoot,
				"Existing Pack",
				ExistingOutputPolicy.REPLACE_WITH_BACKUP
			),
			new OperationCancellation()
		);
		assertTrue(replaced.backup().isPresent(), "replacement reports backup");
		Path backupPayload = replaced.backup().orElseThrow().resolve("Existing Pack.zip");
		assertTrue(
			java.util.Arrays.equals(originalOutput, Files.readAllBytes(backupPayload)),
			"backup preserves previous output"
		);
		try (ZipFile archive = new ZipFile(replaced.output().toFile())) {
			assertTrue(archive.getEntry("pack.mcmeta") != null, "replacement publishes verified ZIP");
		}
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
					ExportType.CUSTOM_LOCATION,
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

	private static void testProjectInternalsBlockExport(
		CraftStudioProject project,
		TestServices services
	) throws Exception {
		Path leakedMetadata = project.packRoot().resolve(".craftstudio/private.json");
		Files.createDirectories(leakedMetadata.getParent());
		Files.writeString(leakedMetadata, "{}", StandardCharsets.UTF_8);
		ValidationReport report = services.validationService().validateProject(
			project,
			new OperationCancellation()
		);
		assertTrue(
			report.issues().stream().anyMatch(issue ->
				issue.code().equals("PROJECT_INTERNAL_IN_PACK")
					&& issue.packPath().equals(".craftstudio/private.json")),
			"project internals block export"
		);
		Files.delete(leakedMetadata);
		Files.delete(leakedMetadata.getParent());
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
		ExportRequest normalized = new ExportRequest(
			ExportType.CUSTOM_LOCATION,
			temporaryRoot,
			"Named Pack.zip",
			ExistingOutputPolicy.CANCEL
		);
		assertEquals("Named Pack", normalized.exportName(), "ZIP suffix normalization");
		try {
			new ExportRequest(
				ExportType.CUSTOM_LOCATION,
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
