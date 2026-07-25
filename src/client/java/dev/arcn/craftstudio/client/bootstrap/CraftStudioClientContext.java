package dev.arcn.craftstudio.client.bootstrap;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.catalog.application.CatalogIndex;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.catalog.domain.CatalogAssetSeed;
import dev.arcn.craftstudio.catalog.infrastructure.minecraft.MinecraftVanillaCatalogAdapter;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.graph.resolver.ItemDependencyResolver;
import dev.arcn.craftstudio.export.application.ExportService;
import dev.arcn.craftstudio.export.domain.ExportException;
import dev.arcn.craftstudio.export.domain.ExportRequest;
import dev.arcn.craftstudio.export.domain.ExportResult;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.paths.WorkspacePaths;
import dev.arcn.craftstudio.platform.process.EditorSettings;
import dev.arcn.craftstudio.platform.process.EditorSettingsRepository;
import dev.arcn.craftstudio.platform.process.ExternalEditorService;
import dev.arcn.craftstudio.platform.task.OperationCancellation;
import dev.arcn.craftstudio.preview.application.PreviewService;
import dev.arcn.craftstudio.preview.domain.PreviewScene;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Texture;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.project.application.BundleService;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.BundleOperationResult;
import dev.arcn.craftstudio.project.domain.BundleFileComparison;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.ProjectMetadata;
import dev.arcn.craftstudio.project.domain.ProjectOperationException;
import dev.arcn.craftstudio.project.domain.RecentProjectEntry;
import dev.arcn.craftstudio.project.domain.RemovalPlan;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.project.infrastructure.RecentProjectRegistry;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import dev.arcn.craftstudio.resource.infrastructure.LayeredPreviewAssetSource;
import dev.arcn.craftstudio.resource.infrastructure.minecraft.VanillaAssetSource;
import dev.arcn.craftstudio.reload.ProjectFileChangeBatch;
import dev.arcn.craftstudio.reload.ProjectFileWatcher;
import dev.arcn.craftstudio.reload.ReloadClassification;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import dev.arcn.craftstudio.validation.application.ValidationService;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

public final class CraftStudioClientContext implements AutoCloseable {
	private static final int MAX_PREVIEW_CACHE_ENTRIES = 32;

	private final WorkspacePaths workspacePaths;
	private final ProjectService projectService;
	private final BundleService bundleService;
	private final RecentProjectRegistry recentProjectRegistry;
	private final MinecraftVanillaCatalogAdapter catalogAdapter;
	private final VanillaAssetSource vanillaAssetSource;
	private final BlockDependencyResolver blockDependencyResolver;
	private final ItemDependencyResolver itemDependencyResolver;
	private final EditorSettingsRepository editorSettingsRepository;
	private final ExternalEditorService externalEditorService;
	private final ValidationService validationService;
	private final ExportService exportService;
	private final Path currentInstanceResourcePacksRoot;
	private final String targetVersion;
	private final ExecutorService backgroundExecutor;
	private final ConcurrentHashMap<String, CompletableFuture<AssetResolutionResult>> blockResolutionCache =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<AssetResolutionResult>> itemResolutionCache =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<PreviewScene>> previewCache =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, AssetKey> previewCacheRoots = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<AssetKey, Set<ResourcePath>> previewDependencies =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<AssetKey, AtomicLong> previewRootRevisions =
		new ConcurrentHashMap<>();
	private final AtomicLong recentProjectsRevision = new AtomicLong();
	private final AtomicLong catalogRevision = new AtomicLong();
	private final AtomicLong previewRevision = new AtomicLong();
	private final AtomicLong projectReloadRevision = new AtomicLong();
	private final AtomicLong settingsRevision = new AtomicLong();

	private volatile List<RecentProjectView> recentProjects = List.of();
	private volatile CraftStudioProject activeProject;
	private volatile ProjectAssetSource activeProjectSource;
	private volatile CatalogIndex catalogIndex;
	private volatile boolean catalogLoading;
	private volatile String catalogError;
	private volatile EditorSettings editorSettings = EditorSettings.DEFAULT;
	private volatile ProjectReloadEvent projectReloadEvent = ProjectReloadEvent.NONE;
	private volatile ProjectFileWatcher projectFileWatcher;
	private boolean catalogStarted;

	private CraftStudioClientContext(
		WorkspacePaths workspacePaths,
		ProjectService projectService,
		BundleService bundleService,
		RecentProjectRegistry recentProjectRegistry,
		MinecraftVanillaCatalogAdapter catalogAdapter,
		VanillaAssetSource vanillaAssetSource,
		BlockDependencyResolver blockDependencyResolver,
		ItemDependencyResolver itemDependencyResolver,
		EditorSettingsRepository editorSettingsRepository,
		ExternalEditorService externalEditorService,
		ValidationService validationService,
		ExportService exportService,
		Path currentInstanceResourcePacksRoot,
		String targetVersion
	) {
		this.workspacePaths = workspacePaths;
		this.projectService = projectService;
		this.bundleService = bundleService;
		this.recentProjectRegistry = recentProjectRegistry;
		this.catalogAdapter = catalogAdapter;
		this.vanillaAssetSource = vanillaAssetSource;
		this.blockDependencyResolver = blockDependencyResolver;
		this.itemDependencyResolver = itemDependencyResolver;
		this.editorSettingsRepository = editorSettingsRepository;
		this.externalEditorService = externalEditorService;
		this.validationService = validationService;
		this.exportService = exportService;
		this.currentInstanceResourcePacksRoot = currentInstanceResourcePacksRoot
			.toAbsolutePath()
			.normalize();
		this.targetVersion = targetVersion;
		this.backgroundExecutor = Executors.newFixedThreadPool(2, Thread.ofPlatform()
			.name("craftstudio-worker-", 0)
			.factory());
	}

	public static CraftStudioClientContext create() {
		Path configRoot = FabricLoader.getInstance().getConfigDir().resolve(CraftStudio.MOD_ID);
		Path defaultWorkspace = FabricLoader.getInstance()
			.getGameDir()
			.resolve("craftstudio-workspaces");
		WorkspacePaths paths = new WorkspacePaths(
			configRoot,
			defaultWorkspace,
			configRoot.resolve("recent-projects.json")
		);

		AtomicFileWriter atomicFileWriter = new AtomicFileWriter();
		ProjectMetadataRepository metadataRepository = new ProjectMetadataRepository(atomicFileWriter);
		TargetVersionManifest target = TargetVersionManifest.minecraft_1_21_11();
		Clock clock = Clock.systemUTC();
		ProjectService projectService = new ProjectService(
			target,
			metadataRepository,
			new PackMetadataWriter(atomicFileWriter),
			atomicFileWriter,
			clock
		);
		RecentProjectRegistry recentProjects = new RecentProjectRegistry(
			paths.recentProjectsFile(),
			atomicFileWriter
		);
		VanillaAssetSource vanillaAssets = new VanillaAssetSource(
			MinecraftClient.getInstance().getDefaultResourcePack(),
			target.minecraftVersion()
		);
		ValidationService validationService = new ValidationService(target, vanillaAssets, clock);
		return new CraftStudioClientContext(
			paths,
			projectService,
			new BundleService(vanillaAssets, metadataRepository, atomicFileWriter, clock),
			recentProjects,
			new MinecraftVanillaCatalogAdapter(),
			vanillaAssets,
			new BlockDependencyResolver(vanillaAssets, target.minecraftVersion()),
			new ItemDependencyResolver(vanillaAssets, target.minecraftVersion()),
			new EditorSettingsRepository(
				configRoot.resolve("editor-settings.json"),
				atomicFileWriter
			),
			new ExternalEditorService(),
			validationService,
			new ExportService(target, validationService, atomicFileWriter, clock),
			FabricLoader.getInstance().getGameDir().resolve("resourcepacks"),
			target.minecraftVersion()
		);
	}

	public void initialize() {
		CompletableFuture.runAsync(this::loadRecentProjectsSafely, backgroundExecutor);
		CompletableFuture.runAsync(this::loadEditorSettingsSafely, backgroundExecutor);
	}

	public void verifyVanillaSource() {
		ResourcePath activePackProbe = new ResourcePath(CraftStudio.MOD_ID, "lang/en_us.json");
		boolean activeStackContainsProbe = MinecraftClient.getInstance()
			.getResourceManager()
			.getResource(Identifier.of(activePackProbe.namespace(), activePackProbe.path()))
			.isPresent();
		CompletableFuture.runAsync(
			() -> verifyVanillaSourceSafely(activePackProbe, activeStackContainsProbe),
			backgroundExecutor
		);
	}

	public synchronized void startCatalogIndex() {
		if (catalogStarted) {
			return;
		}
		catalogStarted = true;
		catalogLoading = true;
		catalogError = null;
		catalogRevision.incrementAndGet();

		List<CatalogAssetSeed> seeds;
		try {
			seeds = catalogAdapter.snapshot();
		} catch (RuntimeException exception) {
			catalogLoading = false;
			catalogError = userMessage(exception);
			catalogRevision.incrementAndGet();
			CraftStudio.LOGGER.error("Could not snapshot registries operation=catalog_snapshot", exception);
			return;
		}

		CompletableFuture.supplyAsync(() -> CatalogIndex.build(seeds), backgroundExecutor)
			.whenComplete((index, error) -> {
				catalogLoading = false;
				if (error == null) {
					catalogIndex = index;
					CraftStudio.LOGGER.info(
						"Catalog indexed asset_count={} namespace_count={} operation=catalog_index",
						index.size(),
						index.namespaces().size()
					);
				} else {
					catalogError = userMessage(error);
					CraftStudio.LOGGER.error("Could not build catalog operation=catalog_index", error);
				}
				catalogRevision.incrementAndGet();
			});
	}

	public CompletableFuture<CraftStudioProject> createProject(ProjectCreationRequest request) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				CraftStudioProject project = projectService.createProject(request);
				recordOpenedProject(project);
				return project;
			} catch (Exception exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<CraftStudioProject> openProject(Path projectPath) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				CraftStudioProject project = projectService.openProject(projectPath);
				recordOpenedProject(project);
				return project;
			} catch (Exception exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public Path defaultWorkspaceRoot() {
		return workspacePaths.defaultWorkspaceRoot();
	}

	public CraftStudioProject activeProject() {
		return activeProject;
	}

	public AssetSource vanillaAssetSource() {
		return vanillaAssetSource;
	}

	public Optional<ProjectAssetSource> activeProjectSource() {
		return Optional.ofNullable(activeProjectSource);
	}

	public Path defaultExportRoot() {
		return requireActiveProject().root().resolve(".craftstudio/exports").normalize();
	}

	public Path currentInstanceResourcePacksRoot() {
		return currentInstanceResourcePacksRoot;
	}

	public BackgroundTask<ValidationReport> validateActiveProject() {
		CraftStudioProject project = requireActiveProject();
		OperationCancellation cancellation = new OperationCancellation();
		CompletableFuture<ValidationReport> future = CompletableFuture.supplyAsync(() -> {
			ValidationReport report = validationService.validateProject(project, cancellation);
			CraftStudio.LOGGER.info(
				"Project validated project_id={} file_count={} errors={} warnings={} operation=project_validate",
				project.metadata().projectId(),
				report.fileCount(),
				report.count(dev.arcn.craftstudio.validation.domain.ValidationSeverity.ERROR),
				report.count(dev.arcn.craftstudio.validation.domain.ValidationSeverity.WARNING)
			);
			return report;
		}, backgroundExecutor);
		return new BackgroundTask<>(future, cancellation);
	}

	public Path plannedExportPath(ExportRequest request) {
		return exportService.targetPath(request);
	}

	public BackgroundTask<ExportResult> exportActiveProject(ExportRequest request) {
		CraftStudioProject project = requireActiveProject();
		OperationCancellation cancellation = new OperationCancellation();
		CompletableFuture<ExportResult> future = CompletableFuture.supplyAsync(() -> {
			try {
				ExportResult result = exportService.export(project, request, cancellation);
				CraftStudio.LOGGER.info(
					"Project exported project_id={} output={} file_count={} report={} backup={} operation=project_export",
					project.metadata().projectId(),
					result.output(),
					result.fileCount(),
					result.report(),
					result.backup().map(Path::toString).orElse("")
				);
				return result;
			} catch (ExportException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
		return new BackgroundTask<>(future, cancellation);
	}

	public CompletableFuture<AssetResolutionResult> resolveBlock(CatalogAsset asset) {
		if (asset.kind() != AssetKind.BLOCK) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Only block assets can use the block dependency resolver.")
			);
		}
		String cacheKey = vanillaAssetSource.revision() + "|" + asset.identifier();
		return blockResolutionCache.computeIfAbsent(cacheKey, ignored -> {
			AssetKey key = new AssetKey(asset.kind(), asset.namespace(), asset.path());
			CompletableFuture<AssetResolutionResult> future = CompletableFuture.supplyAsync(
				() -> blockDependencyResolver.resolve(key),
				backgroundExecutor
			);
			future.whenComplete((result, error) -> {
				if (error != null) {
					blockResolutionCache.remove(cacheKey, future);
					CraftStudio.LOGGER.error(
						"Block resolution failed asset_id={} operation=block_resolve",
						asset.identifier(),
						error
					);
				} else {
					CraftStudio.LOGGER.info(
						"Block resolved asset_id={} node_count={} edge_count={} issue_count={} missing_count={} operation=block_resolve",
						asset.identifier(),
						result.stats().nodeCount(),
						result.stats().edgeCount(),
						result.stats().issueCount(),
						result.stats().missingCount()
					);
				}
			});
			return future;
		});
	}

	public CompletableFuture<AssetResolutionResult> resolveItem(CatalogAsset asset) {
		if (asset.kind() != AssetKind.ITEM) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Only item assets can use the item dependency resolver.")
			);
		}
		String cacheKey = vanillaAssetSource.revision() + "|" + asset.identifier();
		return itemResolutionCache.computeIfAbsent(cacheKey, ignored -> {
			AssetKey key = new AssetKey(asset.kind(), asset.namespace(), asset.path());
			CompletableFuture<AssetResolutionResult> future = CompletableFuture.supplyAsync(
				() -> itemDependencyResolver.resolve(key),
				backgroundExecutor
			);
			future.whenComplete((result, error) -> {
				if (error != null) {
					itemResolutionCache.remove(cacheKey, future);
					CraftStudio.LOGGER.error(
						"Item resolution failed asset_id={} operation=item_resolve",
						asset.identifier(),
						error
					);
				} else {
					CraftStudio.LOGGER.info(
						"Item resolved asset_id={} node_count={} edge_count={} issue_count={} missing_count={} operation=item_resolve",
						asset.identifier(),
						result.stats().nodeCount(),
						result.stats().edgeCount(),
						result.stats().issueCount(),
						result.stats().missingCount()
					);
				}
			});
			return future;
		});
	}

	public CompletableFuture<PreviewScene> createPreview(AssetResolutionResult catalogResolution) {
		Objects.requireNonNull(catalogResolution, "catalogResolution");
		AssetSource projectSource = activeProjectSource;
		LayeredPreviewAssetSource effectiveSource = new LayeredPreviewAssetSource(
			projectSource,
			vanillaAssetSource
		);
		String cacheKey = previewRevision.get()
			+ "|"
			+ previewRootRevisions.computeIfAbsent(
				catalogResolution.root(),
				ignored -> new AtomicLong()
			).get()
			+ "|"
			+ vanillaAssetSource.revision()
			+ "|"
			+ catalogResolution.root().kind()
			+ "|"
			+ catalogResolution.root().identifier();
		if (previewCache.size() >= MAX_PREVIEW_CACHE_ENTRIES
			&& !previewCache.containsKey(cacheKey)) {
			previewCache.clear();
			previewCacheRoots.clear();
		}
		previewCacheRoots.put(cacheKey, catalogResolution.root());
		return previewCache.computeIfAbsent(cacheKey, ignored -> {
			CompletableFuture<PreviewScene> future = CompletableFuture.supplyAsync(() -> {
				AssetResolutionResult effectiveResolution = switch (catalogResolution.root().kind()) {
					case BLOCK -> new BlockDependencyResolver(
						effectiveSource,
						targetVersion
					).resolve(catalogResolution.root());
					case ITEM -> new ItemDependencyResolver(
						effectiveSource,
						targetVersion
					).resolve(catalogResolution.root());
				};
				previewDependencies.put(
					catalogResolution.root(),
					effectiveResolution.graph().nodes().values().stream()
						.map(node -> node.resourcePath().orElse(null))
						.filter(Objects::nonNull)
						.collect(java.util.stream.Collectors.toUnmodifiableSet())
				);
				return new PreviewService(effectiveSource, targetVersion)
					.createScene(effectiveResolution);
			}, backgroundExecutor);
			future.whenComplete((scene, failure) -> {
				if (failure != null) {
					previewCache.remove(cacheKey, future);
					previewCacheRoots.remove(cacheKey);
					CraftStudio.LOGGER.error(
						"Preview preparation failed asset_id={} operation=preview_prepare",
						catalogResolution.root().identifier(),
						failure
					);
				} else {
					long faceCount = scene.variants().stream()
						.flatMap(variant -> variant.faces().stream())
						.count();
					CraftStudio.LOGGER.info(
						"Preview prepared asset_id={} variant_count={} face_count={} diagnostic_count={} source_revision={} operation=preview_prepare",
						scene.root().identifier(),
						scene.variants().size(),
						faceCount,
						scene.diagnostics().size(),
						scene.sourceRevision()
					);
				}
			});
			return future;
		});
	}

	public CompletableFuture<PreviewScene> refreshPreview(
		AssetResolutionResult catalogResolution
	) {
		invalidatePreviewCache(catalogResolution.root());
		return createPreview(catalogResolution);
	}

	public EditorSettings editorSettings() {
		return editorSettings;
	}

	public long settingsRevision() {
		return settingsRevision.get();
	}

	public boolean autoReloadEnabled() {
		CraftStudioProject project = activeProject;
		return project != null && project.metadata().settings().autoReload();
	}

	public ProjectReloadEvent projectReloadEvent() {
		return projectReloadEvent;
	}

	public CompletableFuture<SettingsSnapshot> saveReloadAndEditorSettings(
		String preferredImageEditor,
		boolean autoReload
	) {
		CraftStudioProject project = activeProject;
		EditorSettings requestedEditorSettings = new EditorSettings(preferredImageEditor);
		return CompletableFuture.supplyAsync(() -> {
			try {
				editorSettingsRepository.save(requestedEditorSettings);
				CraftStudioProject updatedProject = project;
				if (project != null) {
					ProjectMetadata.ProjectSettings current = project.metadata().settings();
					updatedProject = projectService.updateSettings(
						project,
						new ProjectMetadata.ProjectSettings(autoReload, current.advancedMode())
					);
				}
				editorSettings = requestedEditorSettings;
				if (updatedProject != null) {
					updateActiveProjectMetadata(updatedProject);
				}
				settingsRevision.incrementAndGet();
				return new SettingsSnapshot(requestedEditorSettings, autoReload);
			} catch (IOException | ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<ExternalEditorService.LaunchResult> openProjectTexture(
		Variant variant
	) {
		CraftStudioProject project = requireActiveProject();
		Texture texture = preferredProjectTexture(variant).orElseThrow(
			() -> new IllegalStateException(
				"This preview has no project texture override. Add the asset bundle first."
			)
		);
		Path file = project.packRoot().resolve(texture.path().packPath()).normalize();
		if (!file.startsWith(project.packRoot())) {
			return CompletableFuture.failedFuture(
				new IOException("Project texture path escaped the active pack.")
			);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return externalEditorService.openImage(file, editorSettings);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<CopyPlan> createCopyPlan(
		AssetResolutionResult resolution,
		SelectionMode mode,
		Set<String> customPackPaths
	) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				return bundleService.createCopyPlan(project, resolution, mode, customPackPaths);
			} catch (ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<BundleOperationResult> addToProject(
		CopyPlan plan,
		Set<String> replacePackPaths
	) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				BundleOperationResult result = bundleService.addToProject(
					project,
					plan,
					replacePackPaths
				);
				refreshActiveProject(result.project());
				CraftStudio.LOGGER.info(
					"Bundle added asset_id={} mode={} copied={} kept={} conflict_replacements={} operation=bundle_add",
					plan.root().identifier(),
					plan.mode().metadataValue(),
					result.copiedFiles(),
					result.keptFiles(),
					replacePackPaths.size()
				);
				return result;
			} catch (ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<BundleOperationResult> restoreVanilla(
		AssetResolutionResult resolution
	) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				BundleOperationResult result = bundleService.restoreVanilla(project, resolution);
				refreshActiveProject(result.project());
				CraftStudio.LOGGER.info(
					"Bundle restored asset_id={} restored={} operation=bundle_restore",
					resolution.root().identifier(),
					result.copiedFiles()
				);
				return result;
			} catch (ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<RemovalPlan> createRemovalPlan(
		AssetResolutionResult resolution
	) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				List<AssetResolutionResult> otherRoots = project.metadata().selectedRoots().stream()
					.map(this::selectedRootKey)
					.filter(key -> !key.equals(resolution.root()))
					.map(this::resolveRoot)
					.toList();
				return bundleService.createRemovalPlan(project, resolution, otherRoots);
			} catch (ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public CompletableFuture<BundleOperationResult> removeRoot(RemovalPlan plan) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				BundleOperationResult result = bundleService.removeRoot(project, plan);
				refreshActiveProject(result.project());
				CraftStudio.LOGGER.info(
					"Bundle root removed asset_id={} removed={} retained={} operation=bundle_remove",
					plan.root().identifier(),
					result.removedFiles(),
					result.keptFiles()
				);
				return result;
			} catch (ProjectOperationException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public boolean isSelectedRoot(CatalogAsset asset) {
		CraftStudioProject project = activeProject;
		return project != null && project.metadata().selectedRoots().stream()
			.anyMatch(root -> root.type().equalsIgnoreCase(asset.kind().name())
				&& root.id().equals(asset.identifier()));
	}

	public CompletableFuture<BundleFileComparison> compareWithVanilla(ResourcePath path) {
		CraftStudioProject project = requireActiveProject();
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] projectBytes = new ProjectAssetSource(project).read(path)
					.orElseThrow(() -> new IOException(
						"Project file is no longer available: " + path.packPath()
					))
					.bytes();
				byte[] vanillaBytes = vanillaAssetSource.read(path)
					.orElseThrow(() -> new IOException(
						"Vanilla file is unavailable: " + path.packPath()
					))
					.bytes();
				return new BundleFileComparison(
					path,
					projectBytes.length,
					vanillaBytes.length,
					sha256(projectBytes),
					sha256(vanillaBytes)
				);
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}, backgroundExecutor);
	}

	public List<RecentProjectView> recentProjects() {
		return recentProjects;
	}

	public long recentProjectsRevision() {
		return recentProjectsRevision.get();
	}

	public CatalogState catalogState() {
		return new CatalogState(catalogIndex, catalogLoading, catalogError, catalogRevision.get());
	}

	@Override
	public void close() {
		closeProjectWatcher();
		backgroundExecutor.shutdownNow();
	}

	public static String userMessage(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof CompletionException || current.getCause() != null)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isBlank() ? "The operation failed unexpectedly." : message;
	}

	private synchronized void recordOpenedProject(CraftStudioProject project) {
		closeProjectWatcher();
		activeProject = project;
		activeProjectSource = new ProjectAssetSource(project);
		invalidatePreviewCache();
		startProjectWatcher(project);
		try {
			setRecentProjects(recentProjectRegistry.touch(project, Instant.now()));
		} catch (IOException exception) {
			CraftStudio.LOGGER.warn(
				"Could not update recent projects for project_id={} operation=recent_project_touch",
				project.metadata().projectId(),
				exception
			);
		}
	}

	private CraftStudioProject requireActiveProject() {
		CraftStudioProject project = activeProject;
		if (project == null) {
			throw new IllegalStateException("Open or create a CraftStudio project first.");
		}
		return project;
	}

	private synchronized void refreshActiveProject(CraftStudioProject project) {
		if (activeProject == null
			|| !activeProject.metadata().projectId().equals(project.metadata().projectId())) {
			return;
		}
		activeProject = project;
		activeProjectSource = new ProjectAssetSource(project);
		invalidatePreviewCache();
	}

	private void invalidatePreviewCache() {
		previewRevision.incrementAndGet();
		previewCache.clear();
		previewCacheRoots.clear();
		previewDependencies.clear();
		previewRootRevisions.clear();
	}

	private void invalidatePreviewCache(AssetKey root) {
		previewRootRevisions.computeIfAbsent(root, ignored -> new AtomicLong()).incrementAndGet();
		previewCacheRoots.forEach((cacheKey, cacheRoot) -> {
			if (cacheRoot.equals(root)) {
				previewCache.remove(cacheKey);
				previewCacheRoots.remove(cacheKey);
			}
		});
	}

	private void updateActiveProjectMetadata(CraftStudioProject project) {
		synchronized (this) {
			if (activeProject != null
				&& activeProject.metadata().projectId().equals(project.metadata().projectId())) {
				activeProject = project;
			}
		}
	}

	private Optional<Texture> preferredProjectTexture(Variant variant) {
		Optional<Texture> frontTexture = variant.faces().stream()
			.filter(face -> face.direction().equals("north"))
			.map(face -> variant.textures().get(face.textureKey()))
			.filter(Objects::nonNull)
			.filter(texture -> texture.sourceLayer()
				== dev.arcn.craftstudio.resource.domain.SourceLayer.PROJECT)
			.findFirst();
		if (frontTexture.isPresent()) {
			return frontTexture;
		}
		return variant.textures().values().stream()
			.filter(texture -> texture.sourceLayer()
				== dev.arcn.craftstudio.resource.domain.SourceLayer.PROJECT)
			.sorted(java.util.Comparator.comparing(texture -> texture.path().packPath()))
			.findFirst();
	}

	private synchronized void startProjectWatcher(CraftStudioProject project) {
		try {
			projectFileWatcher = new ProjectFileWatcher(
				project.packRoot(),
				ProjectFileWatcher.DEFAULT_DEBOUNCE,
				batch -> handleProjectFileChanges(project.metadata().projectId(), batch)
			);
			CraftStudio.LOGGER.info(
				"Watching project pack project_id={} pack_root={} operation=project_watch_start",
				project.metadata().projectId(),
				project.packRoot()
			);
		} catch (IOException exception) {
			CraftStudio.LOGGER.error(
				"Could not watch project pack project_id={} pack_root={} operation=project_watch_start",
				project.metadata().projectId(),
				project.packRoot(),
				exception
			);
		}
	}

	private synchronized void closeProjectWatcher() {
		ProjectFileWatcher watcher = projectFileWatcher;
		projectFileWatcher = null;
		if (watcher != null) {
			watcher.close();
		}
	}

	private void handleProjectFileChanges(String projectId, ProjectFileChangeBatch batch) {
		CraftStudioProject project = activeProject;
		ProjectAssetSource projectSource = activeProjectSource;
		if (project == null
			|| projectSource == null
			|| !project.metadata().projectId().equals(projectId)) {
			return;
		}

		LinkedHashSet<ResourcePath> changedResources = new LinkedHashSet<>();
		batch.changes().keySet().forEach(relativePath -> batch.resourcePath(relativePath)
			.ifPresent(path -> {
				changedResources.add(path);
				if (path.path().endsWith(".png.mcmeta")) {
					changedResources.add(new ResourcePath(
						path.namespace(),
						path.path().substring(0, path.path().length() - ".mcmeta".length())
					));
				}
			}));
		Set<AssetKey> affectedRoots;
		boolean broad = batch.requiresBroadPreviewInvalidation();
		if (broad) {
			affectedRoots = Set.copyOf(previewDependencies.keySet());
			invalidatePreviewCache();
		} else {
			affectedRoots = previewDependencies.entrySet().stream()
				.filter(entry -> entry.getValue().stream().anyMatch(changedResources::contains))
				.map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
			affectedRoots.forEach(this::invalidatePreviewCache);
		}
		projectSource.advanceRevision();
		long revision = projectReloadRevision.incrementAndGet();
		projectReloadEvent = new ProjectReloadEvent(
			revision,
			batch.changes(),
			Set.copyOf(changedResources),
			affectedRoots,
			broad
		);
		CraftStudio.LOGGER.info(
			"Project files changed project_id={} file_count={} affected_preview_count={} broad={} auto_reload={} operation=project_watch_change",
			projectId,
			batch.changes().size(),
			affectedRoots.size(),
			broad,
			project.metadata().settings().autoReload()
		);
	}

	private AssetKey selectedRootKey(ProjectMetadata.SelectedRoot root) {
		try {
			AssetKind kind = AssetKind.valueOf(root.type().toUpperCase(java.util.Locale.ROOT));
			int separator = root.id().indexOf(':');
			if (separator <= 0 || separator == root.id().length() - 1) {
				throw new IllegalArgumentException("Missing namespace or path.");
			}
			return new AssetKey(
				kind,
				root.id().substring(0, separator),
				root.id().substring(separator + 1)
			);
		} catch (RuntimeException exception) {
			throw new CompletionException(new ProjectOperationException(
				"Project contains an invalid selected root: " + root.id(),
				exception
			));
		}
	}

	private AssetResolutionResult resolveRoot(AssetKey key) {
		return switch (key.kind()) {
			case BLOCK -> blockDependencyResolver.resolve(key);
			case ITEM -> itemDependencyResolver.resolve(key);
		};
	}

	private String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes)
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private void verifyVanillaSourceSafely(
		ResourcePath activePackProbe,
		boolean activeStackContainsProbe
	) {
		List<ResourcePath> expectedResources = List.of(
			new ResourcePath("minecraft", "blockstates/stone.json"),
			new ResourcePath("minecraft", "models/block/stone.json"),
			new ResourcePath("minecraft", "textures/block/stone.png")
		);
		try {
			for (ResourcePath path : expectedResources) {
				if (vanillaAssetSource.read(path).isEmpty()) {
					throw new IOException("Missing vanilla base resource: " + path.packPath());
				}
			}
			boolean vanillaContainsProbe = vanillaAssetSource.read(activePackProbe).isPresent();
			if (!activeStackContainsProbe) {
				throw new IOException("Active resource stack did not expose the CraftStudio probe resource.");
			}
			if (vanillaContainsProbe) {
				throw new IOException("Vanilla source included a CraftStudio active-pack resource.");
			}
			CraftStudio.LOGGER.info(
				"Vanilla source verified resource_count={} active_pack_probe={} base_pack_probe={} revision={} operation=vanilla_source_verify",
				expectedResources.size(),
				activeStackContainsProbe,
				vanillaContainsProbe,
				vanillaAssetSource.revision()
			);
		} catch (IOException | RuntimeException exception) {
			CraftStudio.LOGGER.error("Could not verify vanilla source operation=vanilla_source_verify", exception);
		}
	}

	private void loadRecentProjectsSafely() {
		try {
			setRecentProjects(recentProjectRegistry.load());
		} catch (IOException exception) {
			CraftStudio.LOGGER.warn("Could not load recent projects operation=recent_project_load", exception);
		}
	}

	private void loadEditorSettingsSafely() {
		try {
			editorSettings = editorSettingsRepository.load();
		} catch (IOException exception) {
			CraftStudio.LOGGER.warn(
				"Could not load editor settings operation=editor_settings_load",
				exception
			);
			editorSettings = EditorSettings.DEFAULT;
		}
		settingsRevision.incrementAndGet();
	}

	private void setRecentProjects(List<RecentProjectEntry> entries) {
		recentProjects = entries.stream()
			.map(entry -> new RecentProjectView(entry, isAvailable(entry)))
			.toList();
		recentProjectsRevision.incrementAndGet();
	}

	private boolean isAvailable(RecentProjectEntry entry) {
		try {
			Path path = Path.of(entry.path()).toAbsolutePath().normalize();
			return Files.isDirectory(path)
				&& Files.isRegularFile(path.resolve(ProjectMetadataRepository.FILE_NAME));
		} catch (InvalidPathException exception) {
			return false;
		}
	}

	public record RecentProjectView(RecentProjectEntry entry, boolean available) {
	}

	public record CatalogState(
		CatalogIndex index,
		boolean loading,
		String error,
		long revision
	) {
		public boolean ready() {
			return index != null;
		}
	}

	public record SettingsSnapshot(EditorSettings editorSettings, boolean autoReload) {
		public SettingsSnapshot {
			editorSettings = Objects.requireNonNull(editorSettings, "editorSettings");
		}
	}

	public record BackgroundTask<T>(
		CompletableFuture<T> future,
		OperationCancellation cancellation
	) {
		public BackgroundTask {
			future = Objects.requireNonNull(future, "future");
			cancellation = Objects.requireNonNull(cancellation, "cancellation");
		}

		public void cancel() {
			cancellation.cancel();
		}
	}

	public record ProjectReloadEvent(
		long revision,
		Map<Path, ReloadClassification> changes,
		Set<ResourcePath> changedResources,
		Set<AssetKey> affectedRoots,
		boolean broadPreviewInvalidation
	) {
		private static final ProjectReloadEvent NONE = new ProjectReloadEvent(
			0,
			Map.of(),
			Set.of(),
			Set.of(),
			false
		);

		public ProjectReloadEvent {
			changes = Map.copyOf(Objects.requireNonNull(changes, "changes"));
			changedResources = Set.copyOf(
				Objects.requireNonNull(changedResources, "changedResources")
			);
			affectedRoots = Set.copyOf(Objects.requireNonNull(affectedRoots, "affectedRoots"));
		}

		public boolean affects(AssetKey root) {
			return broadPreviewInvalidation || affectedRoots.contains(root);
		}
	}
}
