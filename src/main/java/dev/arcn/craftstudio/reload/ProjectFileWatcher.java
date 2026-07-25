package dev.arcn.craftstudio.reload;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ProjectFileWatcher implements AutoCloseable {
	public static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(300);

	private final Path packRoot;
	private final Consumer<ProjectFileChangeBatch> listener;
	private final long debounceNanos;
	private final WatchService watchService;
	private final Map<WatchKey, Path> watchedDirectories = new LinkedHashMap<>();
	private final Map<Path, ReloadClassification> pendingChanges = new LinkedHashMap<>();
	private final Thread watcherThread;

	private volatile boolean running = true;
	private long lastChangeNanos;
	private long lastRegistrationCheckNanos;

	public ProjectFileWatcher(
		Path packRoot,
		Duration debounce,
		Consumer<ProjectFileChangeBatch> listener
	) throws IOException {
		this.packRoot = Objects.requireNonNull(packRoot, "packRoot").toAbsolutePath().normalize();
		this.listener = Objects.requireNonNull(listener, "listener");
		Duration safeDebounce = Objects.requireNonNull(debounce, "debounce");
		if (safeDebounce.isNegative() || safeDebounce.isZero()) {
			throw new IllegalArgumentException("Watcher debounce must be positive.");
		}
		debounceNanos = safeDebounce.toNanos();
		if (Files.isSymbolicLink(this.packRoot) || !Files.isDirectory(this.packRoot)) {
			throw new IOException("Project pack root is not a safe directory: " + this.packRoot);
		}
		watchService = FileSystems.getDefault().newWatchService();
		registerRecursively(this.packRoot);
		watcherThread = Thread.ofPlatform()
			.daemon()
			.name("craftstudio-pack-watcher")
			.unstarted(this::watchLoop);
		watcherThread.start();
	}

	private void watchLoop() {
		while (running) {
			try {
				WatchKey key = watchService.poll(100, TimeUnit.MILLISECONDS);
				if (key != null) {
					processKey(key);
				}
				flushWhenReady();
				ensurePackDirectoriesRegistered();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			} catch (IOException | RuntimeException exception) {
				if (running) {
					// Keep watching after transient directory replacement or registration failures.
				}
			}
		}
		flushPending();
	}

	private void processKey(WatchKey key) throws IOException {
		Path directory = watchedDirectories.get(key);
		if (directory == null) {
			key.reset();
			return;
		}
		for (WatchEvent<?> event : key.pollEvents()) {
			if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
				pendingChanges.put(Path.of("."), ReloadClassification.UNKNOWN);
				lastChangeNanos = System.nanoTime();
				continue;
			}
			if (!(event.context() instanceof Path childName)) {
				continue;
			}
			Path changed = directory.resolve(childName).toAbsolutePath().normalize();
			if (!changed.startsWith(packRoot)) {
				continue;
			}
			Path relative = packRoot.relativize(changed);
			pendingChanges.put(relative, ProjectFileChangeClassifier.classify(relative));
			lastChangeNanos = System.nanoTime();
			if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
				&& Files.isDirectory(changed)
				&& !Files.isSymbolicLink(changed)) {
				registerRecursively(changed);
			}
		}
		if (!key.reset()) {
			watchedDirectories.remove(key);
		}
	}

	private void flushWhenReady() {
		if (!pendingChanges.isEmpty()
			&& System.nanoTime() - lastChangeNanos >= debounceNanos) {
			flushPending();
		}
	}

	private void flushPending() {
		if (pendingChanges.isEmpty()) {
			return;
		}
		ProjectFileChangeBatch batch = new ProjectFileChangeBatch(pendingChanges);
		pendingChanges.clear();
		listener.accept(batch);
	}

	private void ensurePackDirectoriesRegistered() throws IOException {
		long now = System.nanoTime();
		if (now - lastRegistrationCheckNanos < TimeUnit.SECONDS.toNanos(1)) {
			return;
		}
		lastRegistrationCheckNanos = now;
		if (Files.isDirectory(packRoot) && !Files.isSymbolicLink(packRoot)) {
			registerRecursively(packRoot);
		}
	}

	private void registerRecursively(Path start) throws IOException {
		Files.walkFileTree(start, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (!watchedDirectories.containsValue(directory.toAbsolutePath().normalize())) {
					register(directory);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private void register(Path directory) throws IOException {
		WatchKey key = directory.register(
			watchService,
			StandardWatchEventKinds.ENTRY_CREATE,
			StandardWatchEventKinds.ENTRY_MODIFY,
			StandardWatchEventKinds.ENTRY_DELETE
		);
		watchedDirectories.put(key, directory.toAbsolutePath().normalize());
	}

	@Override
	public void close() {
		running = false;
		watcherThread.interrupt();
		try {
			watchService.close();
		} catch (IOException ignored) {
			// Closing is best-effort during project switches and client shutdown.
		}
	}
}
