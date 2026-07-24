package dev.arcn.craftstudio.client.bootstrap;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.catalog.application.CatalogIndex;
import dev.arcn.craftstudio.catalog.domain.CatalogAssetSeed;
import dev.arcn.craftstudio.catalog.infrastructure.minecraft.MinecraftVanillaCatalogAdapter;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.platform.paths.WorkspacePaths;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.project.domain.RecentProjectEntry;
import dev.arcn.craftstudio.project.infrastructure.PackMetadataWriter;
import dev.arcn.craftstudio.project.infrastructure.ProjectMetadataRepository;
import dev.arcn.craftstudio.project.infrastructure.RecentProjectRegistry;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;

public final class CraftStudioClientContext implements AutoCloseable {
	private final WorkspacePaths workspacePaths;
	private final ProjectService projectService;
	private final RecentProjectRegistry recentProjectRegistry;
	private final MinecraftVanillaCatalogAdapter catalogAdapter;
	private final ExecutorService backgroundExecutor;
	private final AtomicLong recentProjectsRevision = new AtomicLong();
	private final AtomicLong catalogRevision = new AtomicLong();

	private volatile List<RecentProjectView> recentProjects = List.of();
	private volatile CraftStudioProject activeProject;
	private volatile CatalogIndex catalogIndex;
	private volatile boolean catalogLoading;
	private volatile String catalogError;
	private boolean catalogStarted;

	private CraftStudioClientContext(
		WorkspacePaths workspacePaths,
		ProjectService projectService,
		RecentProjectRegistry recentProjectRegistry,
		MinecraftVanillaCatalogAdapter catalogAdapter
	) {
		this.workspacePaths = workspacePaths;
		this.projectService = projectService;
		this.recentProjectRegistry = recentProjectRegistry;
		this.catalogAdapter = catalogAdapter;
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
		ProjectService projectService = new ProjectService(
			TargetVersionManifest.minecraft_1_21_11(),
			metadataRepository,
			new PackMetadataWriter(atomicFileWriter),
			atomicFileWriter,
			Clock.systemUTC()
		);
		RecentProjectRegistry recentProjects = new RecentProjectRegistry(
			paths.recentProjectsFile(),
			atomicFileWriter
		);
		return new CraftStudioClientContext(
			paths,
			projectService,
			recentProjects,
			new MinecraftVanillaCatalogAdapter()
		);
	}

	public void initialize() {
		CompletableFuture.runAsync(this::loadRecentProjectsSafely, backgroundExecutor);
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
