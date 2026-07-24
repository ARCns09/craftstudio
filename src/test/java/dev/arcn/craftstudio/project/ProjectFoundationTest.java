package dev.arcn.craftstudio.project;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.ProjectOperationException;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.project.infrastructure.RecentProjectRegistry;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ProjectFoundationTest {
	private static final Instant FIXED_TIME = Instant.parse("2026-07-24T12:00:00Z");

	private ProjectFoundationTest() {
	}

	public static void main(String[] args) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-project-foundation-");
		try {
			testCreateAndReopenProject(temporaryRoot);
			testUnsafeAndExistingDestinationsAreRejected(temporaryRoot);
			testRecentProjectsRemainPersistent(temporaryRoot);
			System.out.println("Project foundation tests passed.");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testCreateAndReopenProject(Path temporaryRoot) throws Exception {
		ProjectService service = createService();
		Path workspace = temporaryRoot.resolve("workspace");
		CraftStudioProject created = service.createProject(new ProjectCreationRequest(
			"My Furnace Pack",
			"my-furnace-pack",
			"A custom furnace texture pack.",
			"ARCn09",
			workspace
		));

		assertEquals(workspace.resolve("my-furnace-pack"), created.root(), "project root");
		assertTrue(Files.isRegularFile(created.root().resolve("craftstudio.project.json")), "metadata");
		assertTrue(Files.isRegularFile(created.packRoot().resolve("pack.mcmeta")), "pack metadata");
		assertTrue(Files.isDirectory(created.packRoot().resolve("assets")), "assets directory");
		assertTrue(Files.isDirectory(created.root().resolve(".craftstudio/backups")), "backup directory");
		assertTrue(Files.isDirectory(created.root().resolve(".craftstudio/cache")), "cache directory");
		assertTrue(Files.isDirectory(created.root().resolve(".craftstudio/exports")), "exports directory");
		assertTrue(Files.isDirectory(created.root().resolve(".craftstudio/logs")), "logs directory");

		JsonObject metadata = JsonParser.parseString(Files.readString(
			created.root().resolve("craftstudio.project.json"),
			StandardCharsets.UTF_8
		)).getAsJsonObject();
		assertEquals(1, metadata.get("schema_version").getAsInt(), "schema version");
		assertEquals("1.21.11", metadata.getAsJsonObject("target").get("minecraft").getAsString(), "target");
		assertEquals(
			75,
			metadata.getAsJsonObject("target").get("resource_pack_format").getAsInt(),
			"resource pack format"
		);
		assertEquals(FIXED_TIME.toString(), metadata.get("created_at").getAsString(), "created time");
		assertTrue(metadata.getAsJsonArray("selected_roots").isEmpty(), "selected roots");

		JsonObject packMetadata = JsonParser.parseString(Files.readString(
			created.packRoot().resolve("pack.mcmeta"),
			StandardCharsets.UTF_8
		)).getAsJsonObject();
		assertEquals(
			75,
			packMetadata.getAsJsonObject("pack").get("pack_format").getAsInt(),
			"pack format"
		);
		assertTrue(
			!Files.exists(created.packRoot().resolve("craftstudio.project.json")),
			"project metadata must remain outside pack root"
		);
		assertTrue(
			!Files.exists(created.packRoot().resolve(".craftstudio")),
			"project internals must remain outside pack root"
		);

		CraftStudioProject reopened = service.openProject(created.root());
		assertEquals(created.metadata().projectId(), reopened.metadata().projectId(), "reopened project ID");
		assertEquals(created.root(), reopened.root(), "reopened project root");
	}

	private static void testUnsafeAndExistingDestinationsAreRejected(Path temporaryRoot)
		throws Exception {
		ProjectService service = createService();
		Path workspace = temporaryRoot.resolve("safety-workspace");
		expectProjectFailure(() -> service.createProject(new ProjectCreationRequest(
			"Unsafe",
			"../unsafe",
			"",
			"",
			workspace
		)), "unsafe slug");
		expectProjectFailure(() -> service.createProject(new ProjectCreationRequest(
			"Reserved",
			"con.txt",
			"",
			"",
			workspace
		)), "reserved cross-platform slug");

		service.createProject(new ProjectCreationRequest("Existing", "existing", "", "", workspace));
		expectProjectFailure(() -> service.createProject(new ProjectCreationRequest(
			"Existing Again",
			"existing",
			"",
			"",
			workspace
		)), "existing destination");
	}

	private static void testRecentProjectsRemainPersistent(Path temporaryRoot) throws Exception {
		AtomicFileWriter writer = new AtomicFileWriter();
		ProjectService service = createService();
		CraftStudioProject project = service.createProject(new ProjectCreationRequest(
			"Recent",
			"recent",
			"",
			"",
			temporaryRoot.resolve("recent-workspace")
		));
		RecentProjectRegistry registry = new RecentProjectRegistry(
			temporaryRoot.resolve("config/recent-projects.json"),
			writer
		);
		registry.touch(project, FIXED_TIME);

		assertEquals(1, registry.load().size(), "recent-project count");
		assertEquals(project.root().toString(), registry.load().getFirst().path(), "recent-project path");
	}

	private static ProjectService createService() {
		AtomicFileWriter writer = new AtomicFileWriter();
		return new ProjectService(
			TargetVersionManifest.minecraft_1_21_11(),
			new ProjectMetadataRepository(writer),
			new PackMetadataWriter(writer),
			writer,
			Clock.fixed(FIXED_TIME, ZoneOffset.UTC)
		);
	}

	private static void expectProjectFailure(CheckedOperation operation, String description)
		throws Exception {
		try {
			operation.run();
			throw new AssertionError("Expected project operation to fail: " + description);
		} catch (ProjectOperationException expected) {
			// Expected.
		}
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

	@FunctionalInterface
	private interface CheckedOperation {
		void run() throws Exception;
	}
}
