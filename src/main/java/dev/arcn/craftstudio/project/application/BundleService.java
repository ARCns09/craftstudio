package dev.arcn.craftstudio.project.application;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.DependencyClassification;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.project.domain.BundleOperationResult;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.CopyPlan.DestinationState;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectMetadata;
import dev.arcn.craftstudio.project.domain.ProjectOperationException;
import dev.arcn.craftstudio.project.domain.RemovalPlan;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class BundleService {
	private final AssetSource vanillaSource;
	private final ProjectMetadataRepository metadataRepository;
	private final AtomicFileWriter atomicFileWriter;
	private final Clock clock;

	public BundleService(
		AssetSource vanillaSource,
		ProjectMetadataRepository metadataRepository,
		AtomicFileWriter atomicFileWriter,
		Clock clock
	) {
		this.vanillaSource = Objects.requireNonNull(vanillaSource, "vanillaSource");
		this.metadataRepository = Objects.requireNonNull(metadataRepository, "metadataRepository");
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public CopyPlan createCopyPlan(
		CraftStudioProject project,
		AssetResolutionResult resolution,
		SelectionMode mode,
		Set<String> customPackPaths
	) throws ProjectOperationException {
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(resolution, "resolution");
		Objects.requireNonNull(mode, "mode");
		Set<String> custom = Set.copyOf(Objects.requireNonNull(customPackPaths, "customPackPaths"));
		if (resolution.hasErrors()) {
			throw new ProjectOperationException(
				"Cannot add an asset while its dependency graph contains errors."
			);
		}

		Map<ResourcePath, AssetGraphNode> fileNodes = fileNodes(resolution);
		ProjectAssetSource projectSource = new ProjectAssetSource(project);
		List<CopyPlan.Entry> entries = new ArrayList<>();
		for (Map.Entry<ResourcePath, AssetGraphNode> file : fileNodes.entrySet()) {
			ResourcePath path = file.getKey();
			AssetGraphNode node = file.getValue();
			ResourceData vanilla = readRequiredVanilla(path);
			Optional<ResourceData> existing = readProject(projectSource, path);
			DestinationState destinationState = existing
				.map(data -> Arrays.equals(data.bytes(), vanilla.bytes())
					? DestinationState.IDENTICAL
					: DestinationState.DIFFERENT)
				.orElse(DestinationState.MISSING);
			boolean required = isRequired(node, resolution);
			boolean selected = switch (mode) {
				case COMPLETE -> true;
				case UNIQUE_ONLY -> node.classification() != DependencyClassification.SHARED_VANILLA;
				case CUSTOM -> required || custom.contains(path.packPath());
			};
			entries.add(new CopyPlan.Entry(
				path,
				node.classification(),
				required,
				selected,
				destinationState
			));
		}
		entries.sort(Comparator.comparing(entry -> entry.path().packPath()));
		return new CopyPlan(resolution.root(), mode, entries);
	}

	public BundleOperationResult addToProject(
		CraftStudioProject project,
		CopyPlan plan,
		Set<String> replacePackPaths
	) throws ProjectOperationException {
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(plan, "plan");
		Set<String> replacements = Set.copyOf(
			Objects.requireNonNull(replacePackPaths, "replacePackPaths")
		);
		List<CopyPlan.Entry> publishEntries = plan.selectedEntries().stream()
			.filter(entry -> entry.destinationState() == DestinationState.MISSING
				|| entry.destinationState() == DestinationState.DIFFERENT
					&& replacements.contains(entry.path().packPath()))
			.toList();
		List<ResourcePath> overwritten = publishEntries.stream()
			.filter(entry -> entry.destinationState() == DestinationState.DIFFERENT)
			.map(CopyPlan.Entry::path)
			.toList();
		Path backup = overwritten.isEmpty()
			? null
			: createBackup(project, overwritten, "add");
		Path staging = createStagingDirectory(project, "add");
		List<ResourcePath> published = new ArrayList<>();
		try {
			for (CopyPlan.Entry entry : publishEntries) {
				ResourceData vanilla = readRequiredVanilla(entry.path());
				Path staged = resolveUnder(staging, entry.path().packPath());
				atomicFileWriter.writeBytes(staged, vanilla.bytes());
				if (!Arrays.equals(Files.readAllBytes(staged), vanilla.bytes())) {
					throw new IOException("Staged resource verification failed: " + entry.path().packPath());
				}
			}
			for (CopyPlan.Entry entry : publishEntries) {
				Path staged = resolveUnder(staging, entry.path().packPath());
				Path destination = resolveProjectFile(project, entry.path());
				atomicFileWriter.writeBytes(destination, Files.readAllBytes(staged));
				published.add(entry.path());
			}
			CraftStudioProject updatedProject = saveSelectedRoot(project, plan);
			int kept = plan.selectedEntries().size() - publishEntries.size();
			return new BundleOperationResult(
				updatedProject,
				publishEntries.size(),
				kept,
				0,
				backup
			);
		} catch (IOException | ProjectOperationException exception) {
			rollbackPublished(project, published, backup, overwritten);
			throw new ProjectOperationException("Could not add the asset bundle safely.", exception);
		} finally {
			deleteTaskDirectory(staging);
		}
	}

	public BundleOperationResult restoreVanilla(
		CraftStudioProject project,
		AssetResolutionResult resolution
	) throws ProjectOperationException {
		List<ResourcePath> existing = new ArrayList<>();
		for (ResourcePath path : fileNodes(resolution).keySet()) {
			if (Files.isRegularFile(resolveProjectFile(project, path), LinkOption.NOFOLLOW_LINKS)) {
				existing.add(path);
			}
		}
		Path backup = existing.isEmpty() ? null : createBackup(project, existing, "restore");
		List<ResourcePath> published = new ArrayList<>();
		try {
			for (ResourcePath path : existing) {
				atomicFileWriter.writeBytes(
					resolveProjectFile(project, path),
					readRequiredVanilla(path).bytes()
				);
				published.add(path);
			}
			CraftStudioProject updated = saveMetadata(project, project.metadata().selectedRoots());
			return new BundleOperationResult(updated, existing.size(), 0, 0, backup);
		} catch (IOException | ProjectOperationException exception) {
			rollbackPublished(project, published, backup, existing);
			throw new ProjectOperationException("Could not restore the vanilla bundle safely.", exception);
		}
	}

	public RemovalPlan createRemovalPlan(
		CraftStudioProject project,
		AssetResolutionResult rootResolution,
		List<AssetResolutionResult> otherRoots
	) throws ProjectOperationException {
		Map<ResourcePath, AssetGraphNode> rootNodes = fileNodes(rootResolution);
		Set<ResourcePath> rootFiles = rootNodes.keySet();
		Set<ResourcePath> otherFiles = new LinkedHashSet<>();
		for (AssetResolutionResult other : Objects.requireNonNull(otherRoots, "otherRoots")) {
			otherFiles.addAll(fileNodes(other).keySet());
		}
		List<ResourcePath> removable = new ArrayList<>();
		List<ResourcePath> shared = new ArrayList<>();
		List<ResourcePath> modified = new ArrayList<>();
		String modeValue = project.metadata().selectedRoots().stream()
			.filter(root -> matches(root, rootResolution.root().kind(), rootResolution.root().identifier()))
			.findFirst()
			.map(ProjectMetadata.SelectedRoot::selectionMode)
			.orElseThrow(() -> new ProjectOperationException(
				"The asset is not currently selected in this project."
			));
		SelectionMode rootMode = readSelectionMode(modeValue);
		ProjectAssetSource projectSource = new ProjectAssetSource(project);
		for (ResourcePath path : rootFiles.stream().sorted().toList()) {
			Optional<ResourceData> projectData = readProject(projectSource, path);
			if (projectData.isEmpty()) {
				continue;
			}
			if (otherFiles.contains(path)) {
				shared.add(path);
				continue;
			}
			AssetGraphNode node = rootNodes.get(path);
			if (rootMode == SelectionMode.CUSTOM
				|| rootMode == SelectionMode.UNIQUE_ONLY
					&& node.classification() == DependencyClassification.SHARED_VANILLA) {
				modified.add(path);
				continue;
			}
			Optional<ResourceData> vanilla = readVanilla(path);
			if (vanilla.isPresent()
				&& Arrays.equals(projectData.get().bytes(), vanilla.get().bytes())) {
				removable.add(path);
			} else {
				modified.add(path);
			}
		}
		return new RemovalPlan(rootResolution.root(), removable, shared, modified);
	}

	private SelectionMode readSelectionMode(String value) throws ProjectOperationException {
		try {
			return SelectionMode.fromMetadata(value);
		} catch (RuntimeException exception) {
			throw new ProjectOperationException("Unknown project selection mode: " + value, exception);
		}
	}

	public BundleOperationResult removeRoot(
		CraftStudioProject project,
		RemovalPlan plan
	) throws ProjectOperationException {
		List<ResourcePath> removable = plan.removableFiles();
		Path backup = removable.isEmpty() ? null : createBackup(project, removable, "remove");
		List<ResourcePath> removed = new ArrayList<>();
		try {
			for (ResourcePath path : removable) {
				Path file = resolveProjectFile(project, path);
				if (Files.deleteIfExists(file)) {
					removed.add(path);
					deleteEmptyParents(project, file.getParent());
				}
			}
			List<ProjectMetadata.SelectedRoot> selectedRoots = project.metadata().selectedRoots().stream()
				.filter(root -> !matches(root, plan.root().kind(), plan.root().identifier()))
				.toList();
			CraftStudioProject updated = saveMetadata(project, selectedRoots);
			return new BundleOperationResult(
				updated,
				0,
				plan.sharedFiles().size() + plan.modifiedFiles().size(),
				removed.size(),
				backup
			);
		} catch (IOException | ProjectOperationException exception) {
			restoreBackup(project, backup, removed);
			throw new ProjectOperationException("Could not remove the selected root safely.", exception);
		}
	}

	private Map<ResourcePath, AssetGraphNode> fileNodes(AssetResolutionResult resolution) {
		Map<ResourcePath, AssetGraphNode> result = new LinkedHashMap<>();
		for (AssetGraphNode node : resolution.graph().nodes().values()) {
			if (node.packPath().isEmpty()
				|| node.classification() == DependencyClassification.MISSING) {
				continue;
			}
			ResourcePath path = ResourcePath.fromPackPath(node.packPath());
			result.putIfAbsent(path, node);
		}
		return result;
	}

	private boolean isRequired(
		AssetGraphNode node,
		AssetResolutionResult resolution
	) {
		if (resolution.root().kind() == AssetKind.BLOCK
			&& node.type() == GraphNodeType.BLOCKSTATE_FILE) {
			return true;
		}
		if (resolution.root().kind() == AssetKind.ITEM
			&& node.type() == GraphNodeType.CLIENT_ITEM_FILE) {
			return true;
		}
		if (node.type() != GraphNodeType.MODEL_FILE) {
			return false;
		}
		Set<GraphEdgeType> rootModelEdges = resolution.root().kind() == AssetKind.BLOCK
			? Set.of(GraphEdgeType.HAS_VARIANT, GraphEdgeType.HAS_MULTIPART_CASE)
			: Set.of(GraphEdgeType.SELECTS_MODEL, GraphEdgeType.USES_MODEL);
		return resolution.graph().edges().stream()
			.filter(edge -> edge.toNodeId().equals(node.id()))
			.map(AssetGraphEdge::type)
			.anyMatch(rootModelEdges::contains);
	}

	private CraftStudioProject saveSelectedRoot(
		CraftStudioProject project,
		CopyPlan plan
	) throws ProjectOperationException {
		List<ProjectMetadata.SelectedRoot> roots = new ArrayList<>(
			project.metadata().selectedRoots().stream()
				.filter(root -> !matches(root, plan.root().kind(), plan.root().identifier()))
				.toList()
		);
		roots.add(new ProjectMetadata.SelectedRoot(
			plan.root().kind().name().toLowerCase(Locale.ROOT),
			plan.root().identifier(),
			plan.mode().metadataValue()
		));
		return saveMetadata(project, roots);
	}

	private CraftStudioProject saveMetadata(
		CraftStudioProject project,
		List<ProjectMetadata.SelectedRoot> selectedRoots
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
			selectedRoots,
			current.settings()
		);
		try {
			metadataRepository.save(project.root(), updated);
			return new CraftStudioProject(project.root(), updated);
		} catch (IOException exception) {
			throw new ProjectOperationException("Could not update selected project roots.", exception);
		}
	}

	private boolean matches(
		ProjectMetadata.SelectedRoot root,
		AssetKind kind,
		String identifier
	) {
		return root.type().equalsIgnoreCase(kind.name())
			&& root.id().equals(identifier);
	}

	private ResourceData readRequiredVanilla(ResourcePath path) throws ProjectOperationException {
		return readVanilla(path).orElseThrow(() -> new ProjectOperationException(
			"Vanilla dependency is unavailable: " + path.packPath()
		));
	}

	private Optional<ResourceData> readVanilla(ResourcePath path) throws ProjectOperationException {
		try {
			return vanillaSource.read(path);
		} catch (IOException exception) {
			throw new ProjectOperationException(
				"Could not read vanilla dependency: " + path.packPath(),
				exception
			);
		}
	}

	private Optional<ResourceData> readProject(ProjectAssetSource source, ResourcePath path)
		throws ProjectOperationException {
		try {
			return source.read(path);
		} catch (IOException exception) {
			throw new ProjectOperationException(
				"Could not inspect project dependency: " + path.packPath(),
				exception
			);
		}
	}

	private Path createStagingDirectory(CraftStudioProject project, String operation)
		throws ProjectOperationException {
		Path stagingRoot = project.root().resolve(".craftstudio").resolve("staging").normalize();
		Path staging = stagingRoot.resolve(operation + "-" + UUID.randomUUID()).normalize();
		if (!staging.startsWith(stagingRoot)) {
			throw new ProjectOperationException("Staging path escaped the project.");
		}
		try {
			Files.createDirectories(staging);
			return staging;
		} catch (IOException exception) {
			throw new ProjectOperationException("Could not create the staging directory.", exception);
		}
	}

	private Path createBackup(
		CraftStudioProject project,
		List<ResourcePath> paths,
		String operation
	) throws ProjectOperationException {
		Path backupRoot = project.root().resolve(".craftstudio").resolve("backups").normalize();
		Path backup = backupRoot.resolve(
			clock.instant().toEpochMilli() + "-" + operation + "-" + UUID.randomUUID()
		).normalize();
		if (!backup.startsWith(backupRoot)) {
			throw new ProjectOperationException("Backup path escaped the project.");
		}
		try {
			Files.createDirectories(backup);
			for (ResourcePath path : paths) {
				Path source = resolveProjectFile(project, path);
				if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				Path destination = resolveUnder(backup, path.packPath());
				Files.createDirectories(destination.getParent());
				Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			}
			return backup;
		} catch (IOException exception) {
			deleteTaskDirectory(backup);
			throw new ProjectOperationException("Could not create a safety backup.", exception);
		}
	}

	private void rollbackPublished(
		CraftStudioProject project,
		List<ResourcePath> published,
		Path backup,
		List<ResourcePath> originallyExisting
	) {
		Set<ResourcePath> existing = Set.copyOf(originallyExisting);
		for (ResourcePath path : published.reversed()) {
			try {
				Path destination = resolveProjectFile(project, path);
				if (backup != null) {
					Path backupFile = resolveUnder(backup, path.packPath());
					if (Files.isRegularFile(backupFile, LinkOption.NOFOLLOW_LINKS)) {
						atomicFileWriter.writeBytes(destination, Files.readAllBytes(backupFile));
						continue;
					}
				}
				if (!existing.contains(path)) {
					Files.deleteIfExists(destination);
				}
			} catch (Exception ignored) {
				// Preserve the original operation error; the backup remains available.
			}
		}
	}

	private void restoreBackup(
		CraftStudioProject project,
		Path backup,
		List<ResourcePath> paths
	) {
		if (backup == null) {
			return;
		}
		for (ResourcePath path : paths) {
			try {
				Path backupFile = resolveUnder(backup, path.packPath());
				if (Files.isRegularFile(backupFile, LinkOption.NOFOLLOW_LINKS)) {
					atomicFileWriter.writeBytes(
						resolveProjectFile(project, path),
						Files.readAllBytes(backupFile)
					);
				}
			} catch (Exception ignored) {
				// Preserve the original operation error; the backup remains available.
			}
		}
	}

	private Path resolveProjectFile(CraftStudioProject project, ResourcePath path)
		throws ProjectOperationException {
		try {
			Path resolved = resolveUnder(project.packRoot(), path.packPath());
			rejectSymbolicPath(project.packRoot(), resolved);
			return resolved;
		} catch (IOException exception) {
			throw new ProjectOperationException("Unsafe project resource path.", exception);
		}
	}

	private Path resolveUnder(Path root, String relativePath) throws IOException {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path relative = Path.of(relativePath).normalize();
		if (relative.isAbsolute() || relative.startsWith("..")) {
			throw new IOException("Relative path escaped its operation root.");
		}
		Path resolved = normalizedRoot.resolve(relative).normalize();
		if (!resolved.startsWith(normalizedRoot)) {
			throw new IOException("Relative path escaped its operation root.");
		}
		return resolved;
	}

	private void rejectSymbolicPath(Path root, Path target) throws IOException {
		Path current = root.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(current)) {
			throw new IOException("Project pack root cannot be a symbolic link.");
		}
		for (Path segment : current.relativize(target.toAbsolutePath().normalize())) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Project resource path cannot pass through a symbolic link.");
			}
		}
	}

	private void deleteEmptyParents(CraftStudioProject project, Path start) throws IOException {
		Path assetsRoot = project.packRoot().resolve("assets").normalize();
		Path current = start;
		while (current != null && current.startsWith(assetsRoot) && !current.equals(assetsRoot)) {
			try (Stream<Path> children = Files.list(current)) {
				if (children.findAny().isPresent()) {
					return;
				}
			}
			Files.deleteIfExists(current);
			current = current.getParent();
		}
	}

	private void deleteTaskDirectory(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// A safe abandoned task directory can be recovered on a later startup.
		}
	}
}
