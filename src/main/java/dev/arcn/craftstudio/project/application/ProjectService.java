package dev.arcn.craftstudio.project.application;

import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.ProjectMetadata;
import dev.arcn.craftstudio.project.domain.ProjectOperationException;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ProjectService {
	private static final int SCHEMA_VERSION = 1;
	private static final String PACK_ROOT = "pack";
	private static final Pattern SAFE_SLUG = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
		"con", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
	);

	private final TargetVersionManifest target;
	private final ProjectMetadataRepository metadataRepository;
	private final PackMetadataWriter packMetadataWriter;
	private final AtomicFileWriter atomicFileWriter;
	private final Clock clock;

	public ProjectService(
		TargetVersionManifest target,
		ProjectMetadataRepository metadataRepository,
		PackMetadataWriter packMetadataWriter,
		AtomicFileWriter atomicFileWriter,
		Clock clock
	) {
		this.target = target;
		this.metadataRepository = metadataRepository;
		this.packMetadataWriter = packMetadataWriter;
		this.atomicFileWriter = atomicFileWriter;
		this.clock = clock;
	}

	public CraftStudioProject createProject(ProjectCreationRequest request) throws ProjectOperationException {
		ValidatedRequest validated = validateCreationRequest(request);
		Path workspaceRoot = validated.workspaceRoot();
		Path destination = workspaceRoot.resolve(validated.slug()).normalize();

		ensureNoProjectAncestor(workspaceRoot);
		try {
			Files.createDirectories(workspaceRoot);
		} catch (IOException exception) {
			throw new ProjectOperationException("Could not create the workspace directory.", exception);
		}
		if (!Files.isDirectory(workspaceRoot)) {
			throw new ProjectOperationException("Workspace location is not a directory: " + workspaceRoot);
		}
		if (!destination.getParent().equals(workspaceRoot)) {
			throw new ProjectOperationException("Project destination escaped the selected workspace.");
		}
		if (Files.exists(destination)) {
			throw new ProjectOperationException(
				"Project destination already exists. Choose another slug: " + destination
			);
		}

		Path staging = workspaceRoot.resolve(
			"." + validated.slug() + ".craftstudio-" + UUID.randomUUID() + ".tmp"
		);
		try {
			Files.createDirectory(staging);
			Path packRoot = staging.resolve(PACK_ROOT);
			Files.createDirectories(packRoot.resolve(target.assetsDirectory()));
			Files.createDirectories(staging.resolve(".craftstudio").resolve("backups"));
			Files.createDirectories(staging.resolve(".craftstudio").resolve("cache"));
			Files.createDirectories(staging.resolve(".craftstudio").resolve("exports"));
			Files.createDirectories(staging.resolve(".craftstudio").resolve("logs"));

			Instant now = clock.instant();
			ProjectMetadata metadata = new ProjectMetadata(
				SCHEMA_VERSION,
				UUID.randomUUID().toString(),
				validated.name(),
				validated.slug(),
				validated.description(),
				validated.author(),
				new ProjectMetadata.ProjectTarget(target.minecraftVersion(), target.resourcePackFormat()),
				PACK_ROOT,
				now.toString(),
				now.toString(),
				List.of(),
				new ProjectMetadata.ProjectSettings(false, false)
			);

			metadataRepository.save(staging, metadata);
			String packDescription = validated.description().isBlank()
				? validated.name()
				: validated.description();
			packMetadataWriter.write(packRoot, target, packDescription);
			atomicFileWriter.writeUtf8(staging.resolve("README.txt"), createReadme(metadata));
			publishStagingDirectory(staging, destination);
			return new CraftStudioProject(destination, metadata);
		} catch (IOException | RuntimeException exception) {
			deleteTaskOwnedDirectory(staging);
			throw new ProjectOperationException("Could not create the CraftStudio project.", exception);
		}
	}

	public CraftStudioProject openProject(Path selectedPath) throws ProjectOperationException {
		Path projectRoot;
		try {
			projectRoot = selectedPath.toAbsolutePath().normalize();
		} catch (InvalidPathException exception) {
			throw new ProjectOperationException("Project path is invalid.", exception);
		}
		if (Files.isRegularFile(projectRoot)
			&& projectRoot.getFileName().toString().equals(ProjectMetadataRepository.FILE_NAME)) {
			projectRoot = projectRoot.getParent();
		}
		if (projectRoot == null || !Files.isDirectory(projectRoot)) {
			throw new ProjectOperationException("Project folder does not exist: " + projectRoot);
		}

		ProjectMetadata metadata = metadataRepository.load(projectRoot);
		validateLoadedMetadata(metadata);
		Path packRoot = resolvePackRoot(projectRoot, metadata.packRoot());
		Path packMetadata = packRoot.resolve("pack.mcmeta");
		if (Files.isSymbolicLink(packRoot)
			|| Files.isSymbolicLink(packMetadata)
			|| !Files.isDirectory(packRoot)
			|| !Files.isRegularFile(packMetadata)) {
			throw new ProjectOperationException("Project pack root is incomplete: " + packRoot);
		}
		try {
			packMetadataWriter.addRequiredFormatRangeToLegacyMetadata(packRoot, target);
		} catch (IOException exception) {
			throw new ProjectOperationException(
				"Could not update legacy pack metadata for Minecraft "
					+ target.minecraftVersion() + ".",
				exception
			);
		}
		return new CraftStudioProject(projectRoot, metadata);
	}

	public CraftStudioProject updateSettings(
		CraftStudioProject project,
		ProjectMetadata.ProjectSettings settings
	) throws ProjectOperationException {
		ProjectMetadata current = project.metadata();
		ProjectMetadata updated = new ProjectMetadata(
			current.schemaVersion(),
			current.projectId(),
			current.name(),
			current.slug(),
			current.description(),
			current.author(),
			current.target(),
			current.packRoot(),
			current.createdAt(),
			clock.instant().toString(),
			current.selectedRoots(),
			settings
		);
		try {
			metadataRepository.save(project.root(), updated);
			return new CraftStudioProject(project.root(), updated);
		} catch (IOException exception) {
			throw new ProjectOperationException("Could not save project settings.", exception);
		}
	}

	public static String slugify(String name) {
		String slug = name.strip().toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
		return slug.length() > 64 ? slug.substring(0, 64).replaceAll("[._-]+$", "") : slug;
	}

	private ValidatedRequest validateCreationRequest(ProjectCreationRequest request)
		throws ProjectOperationException {
		String name = request.name().strip();
		String slug = request.slug().strip().toLowerCase(Locale.ROOT);
		String description = request.description().strip();
		String author = request.author().strip();

		if (name.isBlank()) {
			throw new ProjectOperationException("Project name cannot be blank.");
		}
		String windowsBaseName = slug.split("\\.", 2)[0];
		if (!SAFE_SLUG.matcher(slug).matches()
			|| slug.endsWith(".")
			|| WINDOWS_RESERVED_NAMES.contains(windowsBaseName)) {
			throw new ProjectOperationException(
				"Project slug must be 1-64 lowercase letters, numbers, dots, hyphens, or underscores."
			);
		}

		Path workspaceRoot;
		try {
			workspaceRoot = request.workspaceRoot().toAbsolutePath().normalize();
		} catch (InvalidPathException exception) {
			throw new ProjectOperationException("Workspace path is invalid.", exception);
		}
		return new ValidatedRequest(name, slug, description, author, workspaceRoot);
	}

	private void validateLoadedMetadata(ProjectMetadata metadata) throws ProjectOperationException {
		if (metadata.schemaVersion() != SCHEMA_VERSION) {
			throw new ProjectOperationException(
				"Unsupported CraftStudio project schema: " + metadata.schemaVersion()
			);
		}
		try {
			UUID.fromString(metadata.projectId());
			Instant.parse(metadata.createdAt());
			Instant.parse(metadata.updatedAt());
		} catch (IllegalArgumentException exception) {
			throw new ProjectOperationException("Project identifiers or timestamps are malformed.", exception);
		}
		if (!metadata.target().minecraft().equals(target.minecraftVersion())
			|| metadata.target().resourcePackFormat() != target.resourcePackFormat()) {
			throw new ProjectOperationException(
				"This project does not target Minecraft " + target.minecraftVersion() + "."
			);
		}
		if (metadata.name().isBlank() || !SAFE_SLUG.matcher(metadata.slug()).matches()) {
			throw new ProjectOperationException("Project name or slug is invalid.");
		}
	}

	private Path resolvePackRoot(Path projectRoot, String packRootValue)
		throws ProjectOperationException {
		Path relativePackRoot;
		try {
			relativePackRoot = Path.of(packRootValue).normalize();
		} catch (InvalidPathException exception) {
			throw new ProjectOperationException("Project pack_root path is invalid.", exception);
		}
		if (relativePackRoot.isAbsolute()
			|| relativePackRoot.getNameCount() != 1
			|| relativePackRoot.startsWith("..")) {
			throw new ProjectOperationException("Project pack_root must be a safe relative folder.");
		}
		Path resolved = projectRoot.resolve(relativePackRoot).normalize();
		if (!resolved.getParent().equals(projectRoot)) {
			throw new ProjectOperationException("Project pack_root escaped the project folder.");
		}
		return resolved;
	}

	private void ensureNoProjectAncestor(Path workspaceRoot) throws ProjectOperationException {
		Path current = workspaceRoot;
		while (current != null) {
			if (Files.isRegularFile(current.resolve(ProjectMetadataRepository.FILE_NAME))) {
				throw new ProjectOperationException(
					"Workspace location is inside an existing CraftStudio project: " + current
				);
			}
			current = current.getParent();
		}
	}

	private void publishStagingDirectory(Path staging, Path destination) throws IOException {
		try {
			Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(staging, destination);
		}
	}

	private String createReadme(ProjectMetadata metadata) {
		return """
			CraftStudio resource-pack project

			Project: %s
			Target: Minecraft %s

			Edit resource-pack files inside the pack directory.
			Do not copy craftstudio.project.json or .craftstudio into the resource pack.
			""".formatted(metadata.name(), metadata.target().minecraft());
	}

	private void deleteTaskOwnedDirectory(Path staging) {
		if (!Files.exists(staging)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(staging)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// The original project-creation failure remains the primary error.
				}
			});
		} catch (IOException ignored) {
			// The original project-creation failure remains the primary error.
		}
	}

	private record ValidatedRequest(
		String name,
		String slug,
		String description,
		String author,
		Path workspaceRoot
	) {
	}
}
