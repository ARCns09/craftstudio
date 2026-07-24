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
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.paths.WorkspacePaths;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.RecentProjectEntry;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.project.infrastructure.RecentProjectRegistry;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import dev.arcn.craftstudio.resource.infrastructure.minecraft.VanillaAssetSource;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
	private final WorkspacePaths workspacePaths;
	private final ProjectService projectService;
	private final RecentProjectRegistry recentProjectRegistry;
	private final MinecraftVanillaCatalogAdapter catalogAdapter;
	private final VanillaAssetSource vanillaAssetSource;
	private final BlockDependencyResolver blockDependencyResolver;
	private final ItemDependencyResolver itemDependencyResolver;
	private final ExecutorService backgroundExecutor;
	private final ConcurrentHashMap<String, CompletableFuture<AssetResolutionResult>> blockResolutionCache =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CompletableFuture<AssetResolutionResult>> itemResolutionCache =
		new ConcurrentHashMap<>();
	private final AtomicLong recentProjectsRevision = new AtomicLong();
	private final AtomicLong catalogRevision = new AtomicLong();

	private volatile List<RecentProjectView> recentProjects = List.of();
	private volatile CraftStudioProject activeProject;
	private volatile ProjectAssetSource activeProjectSource;
	private volatile CatalogIndex catalogIndex;
	private volatile boolean catalogLoading;
	private volatile String catalogError;
	private boolean catalogStarted;

	private CraftStudioClientContext(
		WorkspacePaths workspacePaths,
		ProjectService projectService,
		RecentProjectRegistry recentProjectRegistry,
		MinecraftVanillaCatalogAdapter catalogAdapter,
		VanillaAssetSource vanillaAssetSource,
		BlockDependencyResolver blockDependencyResolver,
		ItemDependencyResolver itemDependencyResolver
	) {
		this.workspacePaths = workspacePaths;
		this.projectService = projectService;
		this.recentProjectRegistry = recentProjectRegistry;
		this.catalogAdapter = catalogAdapter;
		this.vanillaAssetSource = vanillaAssetSource;
		this.blockDependencyResolver = blockDependencyResolver;
		this.itemDependencyResolver = itemDependencyResolver;
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
		ProjectService projectService = new ProjectService(
			target,
			metadataRepository,
			new PackMetadataWriter(atomicFileWriter),
			atomicFileWriter,
			Clock.systemUTC()
		);
		RecentProjectRegistry recentProjects = new RecentProjectRegistry(
			paths.recentProjectsFile(),
			atomicFileWriter
		);
		VanillaAssetSource vanillaAssets = new VanillaAssetSource(
			MinecraftClient.getInstance().getDefaultResourcePack(),
			target.minecraftVersion()
		);
		return new CraftStudioClientContext(
			paths,
			projectService,
			recentProjects,
			new MinecraftVanillaCatalogAdapter(),
			vanillaAssets,
			new BlockDependencyResolver(vanillaAssets, target.minecraftVersion()),
			new ItemDependencyResolver(vanillaAssets, target.minecraftVersion())
		);
	}

	public void initialize() {
		CompletableFuture.runAsync(this::loadRecentProjectsSafely, backgroundExecutor);
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

	private void recordOpenedProject(CraftStudioProject project) {
		activeProject = project;
		activeProjectSource = new ProjectAssetSource(project);
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
}
