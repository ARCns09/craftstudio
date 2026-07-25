package dev.arcn.craftstudio.reload;

import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.process.EditorSettings;
import dev.arcn.craftstudio.platform.process.EditorSettingsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class LiveReloadTest {
	private LiveReloadTest() {
	}

	public static void main(String[] args) throws Exception {
		Path temporaryRoot = Files.createTempDirectory("craftstudio-live-reload-");
		try {
			testClassification();
			testEditorSettingsRoundTrip(temporaryRoot);
			testRecursiveDebouncedWatcher(temporaryRoot);
			System.out.println("Live reload tests passed.");
		} finally {
			deleteRecursively(temporaryRoot);
		}
	}

	private static void testClassification() {
		assertEquals(
			ReloadClassification.TEXTURE,
			ProjectFileChangeClassifier.classify(
				Path.of("assets/minecraft/textures/block/furnace front.png")
			),
			"PNG classification"
		);
		assertEquals(
			ReloadClassification.TEXTURE,
			ProjectFileChangeClassifier.classify(
				Path.of("assets/minecraft/textures/block/furnace.png.mcmeta")
			),
			"texture metadata classification"
		);
		assertEquals(
			ReloadClassification.MODEL_GRAPH,
			ProjectFileChangeClassifier.classify(
				Path.of("assets/minecraft/models/block/furnace.json")
			),
			"model classification"
		);
		assertEquals(
			ReloadClassification.ATLAS,
			ProjectFileChangeClassifier.classify(
				Path.of("assets/minecraft/atlases/blocks.json")
			),
			"atlas classification"
		);
		assertEquals(
			ReloadClassification.PACK_METADATA,
			ProjectFileChangeClassifier.classify(Path.of("pack.mcmeta")),
			"pack metadata classification"
		);
	}

	private static void testEditorSettingsRoundTrip(Path temporaryRoot) throws Exception {
		EditorSettingsRepository repository = new EditorSettingsRepository(
			temporaryRoot.resolve("config/editor settings.json"),
			new AtomicFileWriter()
		);
		EditorSettings expected = new EditorSettings(
			temporaryRoot.resolve("Editors/Krita चित्र").toString()
		);
		repository.save(expected);
		assertEquals(expected, repository.load(), "Unicode editor executable round trip");
	}

	private static void testRecursiveDebouncedWatcher(Path temporaryRoot) throws Exception {
		Path packRoot = temporaryRoot.resolve("watch-project/pack");
		Path textureDirectory = packRoot.resolve("assets/minecraft/textures/block");
		Files.createDirectories(textureDirectory);
		LinkedBlockingQueue<ProjectFileChangeBatch> batches = new LinkedBlockingQueue<>();
		try (ProjectFileWatcher watcher = new ProjectFileWatcher(
			packRoot,
			Duration.ofMillis(120),
			batches::add
		)) {
			Path texture = textureDirectory.resolve("furnace front.png");
			Files.write(texture, new byte[] {1});
			Files.write(texture, new byte[] {2});
			ProjectFileChangeBatch first = requireBatch(batches, "debounced texture save");
			assertEquals(1, first.changes().size(), "rapid save coalescing");
			assertEquals(
				ReloadClassification.TEXTURE,
				first.changes().get(packRoot.relativize(texture)),
				"watched texture classification"
			);

			Files.delete(texture);
			Files.delete(textureDirectory);
			requireBatch(batches, "directory deletion");
			Files.createDirectory(textureDirectory);
			requireBatch(batches, "directory recreation");
			Path recreatedTexture = textureDirectory.resolve("furnace_on.png");
			Files.write(recreatedTexture, new byte[] {3});
			ProjectFileChangeBatch recreated = requireBatch(batches, "file in recreated directory");
			assertEquals(
				ReloadClassification.TEXTURE,
				recreated.changes().get(packRoot.relativize(recreatedTexture)),
				"recreated directory remains watched"
			);
		}
	}

	private static ProjectFileChangeBatch requireBatch(
		LinkedBlockingQueue<ProjectFileChangeBatch> batches,
		String description
	) throws InterruptedException {
		ProjectFileChangeBatch batch = batches.poll(5, TimeUnit.SECONDS);
		if (batch == null) {
			throw new AssertionError("Timed out waiting for " + description);
		}
		return batch;
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
}
