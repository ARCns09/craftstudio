package dev.arcn.craftstudio.resource;

import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ResourceSourceTest {
	private ResourceSourceTest() {
	}

	public static void main(String[] args) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-resource-source-");
		try {
			testResourcePathValidation();
			testProjectReadsListsAndRevisions(temporaryRoot);
			testProjectSourceRejectsSymbolicLinks(temporaryRoot);
			System.out.println("Resource source tests passed.");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testResourcePathValidation() {
		ResourcePath path = ResourcePath.fromPackPath("assets/minecraft/models/block/stone.json");
		assertEquals("minecraft", path.namespace(), "parsed namespace");
		assertEquals("models/block/stone.json", path.path(), "parsed path");
		assertEquals("assets/minecraft/models/block/stone.json", path.packPath(), "pack path");
		assertEquals("models/block", ResourcePath.normalizePrefix("models/block/"), "normalized prefix");
		assertEquals("", ResourcePath.normalizePrefix(""), "empty prefix");

		expectPathFailure(() -> new ResourcePath("Minecraft", "models/block/stone.json"), "uppercase namespace");
		expectPathFailure(() -> new ResourcePath("minecraft:test", "models/block/stone.json"), "namespace colon");
		expectPathFailure(() -> new ResourcePath("minecraft", "/absolute.json"), "absolute path");
		expectPathFailure(() -> new ResourcePath("minecraft", "../outside.json"), "parent traversal");
		expectPathFailure(() -> new ResourcePath("minecraft", "models/./stone.json"), "dot traversal");
		expectPathFailure(() -> new ResourcePath("minecraft", "models//stone.json"), "empty path segment");
		expectPathFailure(() -> new ResourcePath("minecraft", "models\\stone.json"), "backslash");
		expectPathFailure(() -> new ResourcePath("minecraft", "models/stone.json\0"), "null byte");
		expectPathFailure(() -> ResourcePath.fromPackPath("/assets/minecraft/stone.json"), "absolute pack path");
		expectPathFailure(() -> ResourcePath.fromPackPath("data/minecraft/stone.json"), "non-asset pack path");
	}

	private static void testProjectReadsListsAndRevisions(Path temporaryRoot) throws Exception {
		Path packRoot = temporaryRoot.resolve("project/pack");
		write(packRoot.resolve("assets/minecraft/blockstates/stone.json"), "{\"variants\":{}}");
		write(packRoot.resolve("assets/minecraft/models/block/stone.json"), "{\"parent\":\"minecraft:block/cube_all\"}");
		write(packRoot.resolve("assets/minecraft/textures/block/stone.png"), "png-fixture");
		write(packRoot.resolve("assets/example/models/item/test.json"), "{}");

		ProjectAssetSource source = new ProjectAssetSource(packRoot, "project-id");
		ResourcePath stoneModel = new ResourcePath("minecraft", "models/block/stone.json");
		ResourceData data = source.read(stoneModel).orElseThrow();
		assertEquals(SourceLayer.PROJECT, data.layer(), "project layer");
		assertEquals("project:project-id:0", data.revision(), "initial data revision");
		assertEquals(
			"{\"parent\":\"minecraft:block/cube_all\"}",
			new String(data.bytes(), StandardCharsets.UTF_8),
			"resource bytes"
		);
		assertTrue(source.read(new ResourcePath("minecraft", "models/block/missing.json")).isEmpty(), "missing read");

		List<ResourcePath> models;
		try (Stream<ResourcePath> listed = source.list("minecraft", "models")) {
			models = listed.toList();
		}
		assertEquals(List.of(stoneModel), models, "prefix listing");

		List<ResourcePath> allMinecraft;
		try (Stream<ResourcePath> listed = source.list("minecraft", "")) {
			allMinecraft = listed.toList();
		}
		assertEquals(3, allMinecraft.size(), "namespace listing");
		assertEquals("project:project-id:1", source.advanceRevision(), "advanced revision");
		assertEquals("project:project-id:1", source.read(stoneModel).orElseThrow().revision(), "read revision");

		byte[] copiedBytes = data.bytes();
		copiedBytes[0] = 'X';
		assertEquals('{', (char) data.bytes()[0], "resource data defensive copy");
	}

	private static void testProjectSourceRejectsSymbolicLinks(Path temporaryRoot) throws Exception {
		Path packRoot = temporaryRoot.resolve("symlink-project/pack");
		Path outside = temporaryRoot.resolve("outside.json");
		write(outside, "outside");
		Files.createDirectories(packRoot.resolve("assets/minecraft/models/block"));
		Path link = packRoot.resolve("assets/minecraft/models/block/escaped.json");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (UnsupportedOperationException | FileSystemException exception) {
			return;
		}

		ProjectAssetSource source = new ProjectAssetSource(packRoot, "symlink-project");
		try {
			source.read(new ResourcePath("minecraft", "models/block/escaped.json"));
			throw new AssertionError("Expected symbolic-link resource read to fail.");
		} catch (IOException expected) {
			// Expected.
		}
		try (Stream<ResourcePath> listed = source.list("minecraft", "models")) {
			assertTrue(
				listed.noneMatch(path -> path.path().equals("models/block/escaped.json")),
				"symbolic link excluded from listing"
			);
		}
	}

	private static void write(Path path, String value) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, value, StandardCharsets.UTF_8);
	}

	private static void expectPathFailure(CheckedOperation operation, String description) {
		try {
			operation.run();
			throw new AssertionError("Expected invalid resource path: " + description);
		} catch (IllegalArgumentException expected) {
			// Expected.
		} catch (Exception exception) {
			throw new AssertionError("Unexpected exception for " + description, exception);
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
