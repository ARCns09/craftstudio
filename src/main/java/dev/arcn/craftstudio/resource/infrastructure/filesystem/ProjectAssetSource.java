package dev.arcn.craftstudio.resource.infrastructure.filesystem;

import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class ProjectAssetSource implements AssetSource {
	private static final int MAX_RESOURCE_BYTES = 64 * 1024 * 1024;

	private final Path packRoot;
	private final String projectId;
	private final AtomicLong revision = new AtomicLong();

	public ProjectAssetSource(CraftStudioProject project) {
		this(project.packRoot(), project.metadata().projectId());
	}

	public ProjectAssetSource(Path packRoot, String projectId) {
		this.packRoot = Objects.requireNonNull(packRoot, "packRoot").toAbsolutePath().normalize();
		this.projectId = requireText(projectId, "projectId");
	}

	@Override
	public Optional<ResourceData> read(ResourcePath path) throws IOException {
		Objects.requireNonNull(path, "path");
		Path file = resolveSafely(path.packPath());
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		rejectSymbolicLinks(file);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		try (InputStream input = Files.newInputStream(file)) {
			byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
			if (bytes.length > MAX_RESOURCE_BYTES) {
				throw new IOException("Resource exceeds the 64 MiB read limit: " + path.packPath());
			}
			return Optional.of(new ResourceData(path, bytes, layer(), revision()));
		}
	}

	@Override
	public Stream<ResourcePath> list(String namespace, String prefix) throws IOException {
		String safeNamespace = ResourcePath.validateNamespace(namespace);
		String safePrefix = ResourcePath.normalizePrefix(prefix);
		String relativeStart = "assets/" + safeNamespace + (safePrefix.isEmpty() ? "" : "/" + safePrefix);
		Path start = resolveSafely(relativeStart);
		if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
			return Stream.empty();
		}
		rejectSymbolicLinks(start);
		if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
			return Stream.empty();
		}
		Path namespaceRoot = resolveSafely("assets/" + safeNamespace);
		return Files.walk(start)
			.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			.filter(path -> !Files.isSymbolicLink(path))
			.map(namespaceRoot::relativize)
			.map(Path::toString)
			.map(value -> value.replace('\\', '/'))
			.map(path -> new ResourcePath(safeNamespace, path))
			.sorted();
	}

	@Override
	public SourceLayer layer() {
		return SourceLayer.PROJECT;
	}

	@Override
	public String revision() {
		return "project:" + projectId + ":" + revision.get();
	}

	public String advanceRevision() {
		revision.incrementAndGet();
		return revision();
	}

	private Path resolveSafely(String relativePackPath) throws IOException {
		Path relative;
		try {
			relative = Path.of(relativePackPath).normalize();
		} catch (RuntimeException exception) {
			throw new IOException("Invalid resource path.", exception);
		}
		if (relative.isAbsolute() || relative.startsWith("..")) {
			throw new IOException("Resource path escaped the project pack.");
		}
		Path resolved = packRoot.resolve(relative).normalize();
		if (!resolved.startsWith(packRoot)) {
			throw new IOException("Resource path escaped the project pack.");
		}
		return resolved;
	}

	private void rejectSymbolicLinks(Path target) throws IOException {
		Path relative = packRoot.relativize(target);
		Path current = packRoot;
		if (Files.isSymbolicLink(current)) {
			throw new IOException("Project pack root cannot be a symbolic link.");
		}
		for (Path segment : relative) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Resource path cannot pass through a symbolic link: " + target);
			}
		}
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
