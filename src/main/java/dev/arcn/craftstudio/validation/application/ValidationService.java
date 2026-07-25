package dev.arcn.craftstudio.validation.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.DependencyClassification;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.graph.domain.ResolutionIssueSeverity;
import dev.arcn.craftstudio.graph.resolver.BlockDependencyResolver;
import dev.arcn.craftstudio.graph.resolver.ItemDependencyResolver;
import dev.arcn.craftstudio.platform.task.OperationCancellation;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.project.domain.ProjectMetadata;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.infrastructure.LayeredPreviewAssetSource;
import dev.arcn.craftstudio.resource.infrastructure.filesystem.ProjectAssetSource;
import dev.arcn.craftstudio.validation.domain.ValidationIssue;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import dev.arcn.craftstudio.validation.domain.ValidationSeverity;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class ValidationService {
	private static final long MAX_JSON_BYTES = 8L * 1024L * 1024L;
	private static final int LARGE_TEXTURE_DIMENSION = 8192;
	private static final Set<String> EXPECTED_EXTENSIONS = Set.of(
		".json",
		".mcmeta",
		".png",
		".ogg",
		".ttf",
		".otf",
		".bin",
		".fsh",
		".vsh",
		".glsl"
	);

	private final TargetVersionManifest target;
	private final AssetSource vanillaSource;
	private final Clock clock;

	public ValidationService(
		TargetVersionManifest target,
		AssetSource vanillaSource,
		Clock clock
	) {
		this.target = Objects.requireNonNull(target, "target");
		this.vanillaSource = Objects.requireNonNull(vanillaSource, "vanillaSource");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public ValidationReport validateProject(
		CraftStudioProject project,
		OperationCancellation cancellation
	) {
		Objects.requireNonNull(project, "project");
		ValidationState state = validatePack(
			project.metadata().projectId(),
			project.packRoot(),
			Objects.requireNonNull(cancellation, "cancellation")
		);
		validateSelectedRoots(project, state, cancellation);
		addPassedChecks(state);
		return state.report(clock);
	}

	public ValidationReport validateStagedPack(
		String projectId,
		Path packRoot,
		OperationCancellation cancellation
	) {
		ValidationState state = validatePack(
			Objects.requireNonNull(projectId, "projectId"),
			packRoot,
			Objects.requireNonNull(cancellation, "cancellation")
		);
		addPassedChecks(state);
		return state.report(clock);
	}

	private ValidationState validatePack(
		String projectId,
		Path packRoot,
		OperationCancellation cancellation
	) {
		Path root = Objects.requireNonNull(packRoot, "packRoot").toAbsolutePath().normalize();
		ValidationState state = new ValidationState(projectId, root);
		if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			state.error(
				"PACK_ROOT_INVALID",
				"Pack root is missing, symbolic, or not a directory.",
				"",
				"",
				List.of(),
				"Restore or recreate the project pack folder."
			);
			return state;
		}

		List<Path> entries;
		try (Stream<Path> stream = Files.walk(root)) {
			entries = stream
				.filter(path -> !path.equals(root))
				.sorted(Comparator.comparing(path -> root.relativize(path).toString()))
				.toList();
		} catch (IOException exception) {
			state.error(
				"PACK_LIST_FAILED",
				"Could not list pack contents: " + safeMessage(exception),
				"",
				"",
				List.of(),
				"Check folder permissions and retry."
			);
			return state;
		}

		for (Path entry : entries) {
			cancellation.throwIfCancelled();
			String packPath = relativePath(root, entry);
			if (Files.isSymbolicLink(entry)) {
				state.error(
					"SYMBOLIC_PATH",
					"Symbolic links are not allowed in an exported pack.",
					packPath,
					"",
					List.of(),
					"Replace the link with a regular project file."
				);
				continue;
			}
			if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
				state.error(
					"UNSUPPORTED_FILE_TYPE",
					"Pack entry is not a regular file.",
					packPath,
					"",
					List.of(),
					"Remove the unsupported entry."
				);
				continue;
			}
			state.fileCount++;
			validatePackPath(packPath, state);
			validateFile(entry, packPath, state);
		}
		validatePackMetadata(state);
		validateTextureMetadataTargets(state);
		return state;
	}

	private void validatePackPath(String packPath, ValidationState state) {
		if (packPath.equals("pack.mcmeta") || packPath.equals("pack.png")) {
			return;
		}
		if (!packPath.startsWith("assets/")) {
			state.warning(
				"UNEXPECTED_ROOT_FILE",
				"File is outside assets/ and is not standard pack metadata.",
				packPath,
				"",
				List.of(),
				"Confirm this file is intentionally part of the resource pack."
			);
			return;
		}
		try {
			ResourcePath.fromPackPath(packPath);
		} catch (IllegalArgumentException exception) {
			state.error(
				"INVALID_ASSET_PATH",
				"Asset path or namespace is invalid: " + safeMessage(exception),
				packPath,
				"",
				List.of(),
				"Rename the file to a valid lowercase Minecraft resource path."
			);
			return;
		}
		String lower = packPath.toLowerCase(Locale.ROOT);
		if (EXPECTED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
			state.warning(
				"UNEXPECTED_EXTENSION",
				"Asset uses an extension CraftStudio does not recognize.",
				packPath,
				"",
				List.of(),
				"Confirm Minecraft 1.21.11 supports this file type."
			);
		}
	}

	private void validateFile(Path file, String packPath, ValidationState state) {
		String lower = packPath.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".json") || lower.endsWith(".mcmeta")) {
			validateJson(file, packPath, state);
		}
		if (lower.endsWith(".png")) {
			validateImage(file, packPath, state);
		}
	}

	private void validateJson(Path file, String packPath, ValidationState state) {
		try {
			long size = Files.size(file);
			if (size > MAX_JSON_BYTES) {
				state.error(
					"JSON_TOO_LARGE",
					"JSON file exceeds the 8 MiB validation limit.",
					packPath,
					"$",
					List.of(),
					"Reduce the file size before exporting."
				);
				return;
			}
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			state.json.put(packPath, parsed);
			validateRequiredJsonFields(packPath, parsed, state);
		} catch (IOException | JsonParseException | IllegalStateException exception) {
			state.error(
				"INVALID_JSON",
				"JSON could not be parsed: " + safeMessage(exception),
				packPath,
				"$",
				List.of(),
				"Open the file and correct its JSON syntax."
			);
		}
	}

	private void validateRequiredJsonFields(
		String packPath,
		JsonElement parsed,
		ValidationState state
	) {
		if (!parsed.isJsonObject()) {
			state.error(
				"JSON_ROOT_TYPE",
				"JSON root must be an object.",
				packPath,
				"$",
				List.of(),
				"Replace the root value with a JSON object."
			);
			return;
		}
		JsonObject object = parsed.getAsJsonObject();
		if (packPath.contains("/blockstates/")
			&& !object.has("variants")
			&& !object.has("multipart")) {
			state.error(
				"BLOCKSTATE_CONTENT_MISSING",
				"Blockstate must define variants or multipart.",
				packPath,
				"$",
				List.of(),
				"Add a variants or multipart definition."
			);
		}
		if (packPath.contains("/items/") && !object.has("model")) {
			state.error(
				"ITEM_MODEL_MISSING",
				"Client item definition is missing its model field.",
				packPath,
				"$.model",
				List.of(),
				"Add a valid item model definition."
			);
		}
		if (packPath.contains("/atlases/")
			&& object.has("sources")
			&& !object.get("sources").isJsonArray()) {
			state.error(
				"ATLAS_SOURCES_TYPE",
				"Atlas sources must be an array.",
				packPath,
				"$.sources",
				List.of(),
				"Change sources to a JSON array."
			);
		}
	}

	private void validateImage(Path file, String packPath, ValidationState state) {
		try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
			if (input == null) {
				throw new IOException("No image input provider accepted the file.");
			}
			java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new IOException("PNG decoder could not recognize the file.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0) {
					throw new IOException("Image dimensions must be greater than zero.");
				}
				int subsampling = Math.max(
					1,
					Math.ceilDiv(Math.max(width, height), 2048)
				);
				ImageReadParam readParameters = reader.getDefaultReadParam();
				readParameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
				if (reader.read(0, readParameters) == null) {
					throw new IOException("PNG decoder returned no image.");
				}
				state.images.add(packPath);
				if (width > LARGE_TEXTURE_DIMENSION || height > LARGE_TEXTURE_DIMENSION) {
					state.warning(
						"LARGE_TEXTURE",
						"Texture is unusually large: " + width + "×" + height + ".",
						packPath,
						"",
						List.of(),
						"Confirm the texture size is intentional."
					);
				}
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException exception) {
			state.error(
				"INVALID_PNG",
				"PNG could not be decoded: " + safeMessage(exception),
				packPath,
				"",
				List.of(),
				"Replace the file with a valid PNG image."
			);
		}
	}

	private void validatePackMetadata(ValidationState state) {
		JsonElement metadata = state.json.get("pack.mcmeta");
		if (metadata == null) {
			if (!Files.isRegularFile(state.packRoot.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
				state.error(
					"PACK_METADATA_MISSING",
					"pack.mcmeta is missing from the pack root.",
					"pack.mcmeta",
					"$",
					List.of(),
					"Regenerate pack.mcmeta from the project target."
				);
			}
			return;
		}
		if (!metadata.isJsonObject()
			|| !metadata.getAsJsonObject().has("pack")
			|| !metadata.getAsJsonObject().get("pack").isJsonObject()) {
			state.error(
				"PACK_SECTION_MISSING",
				"pack.mcmeta must contain a pack object.",
				"pack.mcmeta",
				"$.pack",
				List.of(),
				"Regenerate pack.mcmeta."
			);
			return;
		}
		JsonObject pack = metadata.getAsJsonObject().getAsJsonObject("pack");
		if (!pack.has("pack_format")
			|| !pack.get("pack_format").isJsonPrimitive()
			|| !pack.get("pack_format").getAsJsonPrimitive().isNumber()) {
			state.error(
				"PACK_FORMAT_MISSING",
				"pack.mcmeta must contain a numeric pack_format.",
				"pack.mcmeta",
				"$.pack.pack_format",
				List.of(),
				"Regenerate pack.mcmeta."
			);
		} else {
			try {
				int format = pack.get("pack_format").getAsInt();
				if (format != target.resourcePackFormat()) {
					state.error(
						"PACK_FORMAT_INCORRECT",
						"Pack format is " + format + " but Minecraft "
							+ target.minecraftVersion() + " requires "
							+ target.resourcePackFormat() + ".",
						"pack.mcmeta",
						"$.pack.pack_format",
						List.of(),
						"Regenerate pack.mcmeta for Minecraft " + target.minecraftVersion() + "."
					);
				}
			} catch (NumberFormatException exception) {
				state.error(
					"PACK_FORMAT_INVALID",
					"pack_format must be an integer.",
					"pack.mcmeta",
					"$.pack.pack_format",
					List.of(),
					"Regenerate pack.mcmeta."
				);
			}
		}
		if (!pack.has("description")) {
			state.error(
				"PACK_DESCRIPTION_MISSING",
				"pack.mcmeta is missing its description.",
				"pack.mcmeta",
				"$.pack.description",
				List.of(),
				"Regenerate pack.mcmeta."
			);
		}
	}

	private void validateTextureMetadataTargets(ValidationState state) {
		for (String packPath : state.json.keySet()) {
			if (!packPath.endsWith(".png.mcmeta")) {
				continue;
			}
			String texturePath = packPath.substring(0, packPath.length() - ".mcmeta".length());
			if (!Files.isRegularFile(state.packRoot.resolve(texturePath), LinkOption.NOFOLLOW_LINKS)) {
				state.error(
					"TEXTURE_METADATA_TARGET_MISSING",
					"Texture metadata has no matching PNG.",
					packPath,
					"$",
					List.of(texturePath),
					"Restore the PNG or remove the orphaned metadata file."
				);
			}
		}
	}

	private void validateSelectedRoots(
		CraftStudioProject project,
		ValidationState state,
		OperationCancellation cancellation
	) {
		AssetSource projectSource = new ProjectAssetSource(project);
		AssetSource layered = new LayeredPreviewAssetSource(projectSource, vanillaSource);
		Set<ResourcePath> reachable = new LinkedHashSet<>();
		for (ProjectMetadata.SelectedRoot selectedRoot : project.metadata().selectedRoots()) {
			cancellation.throwIfCancelled();
			AssetKey root;
			SelectionMode mode;
			try {
				root = parseSelectedRoot(selectedRoot);
				mode = SelectionMode.fromMetadata(selectedRoot.selectionMode());
			} catch (RuntimeException exception) {
				state.error(
					"SELECTED_ROOT_INVALID",
					"Selected project root is malformed: " + selectedRoot.id(),
					"",
					"",
					List.of(selectedRoot.id()),
					"Remove and add the asset root again."
				);
				continue;
			}
			AssetResolutionResult resolution = switch (root.kind()) {
				case BLOCK -> new BlockDependencyResolver(
					layered,
					target.minecraftVersion()
				).resolve(root);
				case ITEM -> new ItemDependencyResolver(
					layered,
					target.minecraftVersion()
				).resolve(root);
			};
			resolution.graph().nodes().values().stream()
				.map(node -> node.resourcePath().orElse(null))
				.filter(Objects::nonNull)
				.forEach(reachable::add);
			resolution.issues().forEach(issue -> state.add(new ValidationIssue(
				switch (issue.severity()) {
					case ERROR -> ValidationSeverity.ERROR;
					case WARNING -> ValidationSeverity.WARNING;
					case INFO -> ValidationSeverity.INFORMATION;
				},
				issue.code(),
				issue.message(),
				issue.packPath(),
				issue.jsonPath(),
				issue.dependencyChain(),
				suggestedGraphRepair(issue.severity())
			)));
			validateExpectedProjectFiles(project, selectedRoot, mode, resolution, state);
		}
		validateUnreachableProjectFiles(state, reachable);
	}

	private void validateExpectedProjectFiles(
		CraftStudioProject project,
		ProjectMetadata.SelectedRoot selectedRoot,
		SelectionMode mode,
		AssetResolutionResult resolution,
		ValidationState state
	) {
		for (AssetGraphNode node : resolution.graph().nodes().values()) {
			if (node.packPath().isEmpty()
				|| node.classification() == DependencyClassification.MISSING
				|| node.classification() == DependencyClassification.GENERATED
				|| !expectedInProject(mode, node, resolution)) {
				continue;
			}
			Path expected = project.packRoot().resolve(node.packPath()).normalize();
			if (!expected.startsWith(project.packRoot())
				|| !Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(expected)) {
				state.error(
					"SELECTED_BUNDLE_FILE_MISSING",
					"Selected " + mode.metadataValue() + " bundle is missing a required project file.",
					node.packPath(),
					"",
					List.of(selectedRoot.id(), node.packPath()),
					"Restore the selected asset bundle from vanilla."
				);
			}
		}
	}

	private boolean expectedInProject(
		SelectionMode mode,
		AssetGraphNode node,
		AssetResolutionResult resolution
	) {
		return switch (mode) {
			case COMPLETE -> true;
			case UNIQUE_ONLY -> node.classification() != DependencyClassification.SHARED_VANILLA;
			case CUSTOM -> isRequiredCustomNode(node, resolution);
		};
	}

	private boolean isRequiredCustomNode(
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
			.map(edge -> edge.type())
			.anyMatch(rootModelEdges::contains);
	}

	private void validateUnreachableProjectFiles(
		ValidationState state,
		Set<ResourcePath> reachable
	) {
		for (String packPath : state.regularPackPaths()) {
			if (!packPath.startsWith("assets/")) {
				continue;
			}
			ResourcePath resource;
			try {
				resource = ResourcePath.fromPackPath(packPath);
			} catch (IllegalArgumentException exception) {
				continue;
			}
			if (reachable.contains(resource)) {
				continue;
			}
			if (resource.path().startsWith("textures/") && resource.path().endsWith(".png")) {
				state.warning(
					"ORPHANED_PROJECT_TEXTURE",
					"Project texture is not reachable from a selected asset graph.",
					packPath,
					"",
					List.of(),
					"Keep it if it is intentional, or remove it after confirmation."
				);
			} else if (resource.path().startsWith("models/") && resource.path().endsWith(".json")) {
				state.warning(
					"UNREACHABLE_PROJECT_MODEL",
					"Project model is not reachable from a selected asset graph.",
					packPath,
					"",
					List.of(),
					"Keep it if it is intentional, or remove it after confirmation."
				);
			}
		}
	}

	private AssetKey parseSelectedRoot(ProjectMetadata.SelectedRoot selectedRoot) {
		AssetKind kind = AssetKind.valueOf(selectedRoot.type().toUpperCase(Locale.ROOT));
		int separator = selectedRoot.id().indexOf(':');
		if (separator <= 0 || separator == selectedRoot.id().length() - 1) {
			throw new IllegalArgumentException("Selected root lacks namespace or path.");
		}
		return new AssetKey(
			kind,
			selectedRoot.id().substring(0, separator),
			selectedRoot.id().substring(separator + 1)
		);
	}

	private void addPassedChecks(ValidationState state) {
		if (!state.hasCodePrefix("PACK_") && state.json.containsKey("pack.mcmeta")) {
			state.add(ValidationIssue.passed(
				"PACK_METADATA_VALID",
				"pack.mcmeta is present and targets Minecraft " + target.minecraftVersion() + "."
			));
		}
		if (!state.hasCode("INVALID_JSON")
			&& !state.hasCode("JSON_ROOT_TYPE")
			&& !state.json.isEmpty()) {
			state.add(ValidationIssue.passed(
				"JSON_FILES_VALID",
				"All discovered JSON and metadata files parsed successfully."
			));
		}
		if (!state.hasCode("INVALID_PNG") && !state.images.isEmpty()) {
			state.add(ValidationIssue.passed(
				"PNG_FILES_VALID",
				"All discovered PNG textures have valid dimensions and headers."
			));
		}
		if (state.issues.stream().noneMatch(issue ->
			issue.severity() == ValidationSeverity.ERROR
				&& (issue.code().contains("MISSING")
					|| issue.code().contains("CYCLE")
					|| issue.code().contains("ATLAS")))) {
			state.add(ValidationIssue.passed(
				"DEPENDENCIES_VALID",
				"Selected asset dependencies contain no critical missing or cyclic resources."
			));
		}
	}

	private String suggestedGraphRepair(ResolutionIssueSeverity severity) {
		return severity == ResolutionIssueSeverity.ERROR
			? "Open the referenced file or restore the selected bundle dependency."
			: "Review the dependency and confirm the behavior is intentional.";
	}

	private String relativePath(Path root, Path path) {
		return root.relativize(path).toString().replace('\\', '/');
	}

	private String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank()
			? throwable.getClass().getSimpleName()
			: message;
	}

	private static final class ValidationState {
		private final String projectId;
		private final Path packRoot;
		private final List<ValidationIssue> issues = new ArrayList<>();
		private final Map<String, JsonElement> json = new HashMap<>();
		private final Set<String> images = new LinkedHashSet<>();
		private int fileCount;

		private ValidationState(String projectId, Path packRoot) {
			this.projectId = projectId;
			this.packRoot = packRoot;
		}

		private void add(ValidationIssue issue) {
			issues.add(issue);
		}

		private void error(
			String code,
			String summary,
			String packPath,
			String jsonPath,
			List<String> dependencyChain,
			String repair
		) {
			add(new ValidationIssue(
				ValidationSeverity.ERROR,
				code,
				summary,
				packPath,
				jsonPath,
				dependencyChain,
				repair
			));
		}

		private void warning(
			String code,
			String summary,
			String packPath,
			String jsonPath,
			List<String> dependencyChain,
			String repair
		) {
			add(new ValidationIssue(
				ValidationSeverity.WARNING,
				code,
				summary,
				packPath,
				jsonPath,
				dependencyChain,
				repair
			));
		}

		private boolean hasCode(String code) {
			return issues.stream().anyMatch(issue -> issue.code().equals(code));
		}

		private boolean hasCodePrefix(String prefix) {
			return issues.stream()
				.filter(issue -> issue.severity() == ValidationSeverity.ERROR)
				.anyMatch(issue -> issue.code().startsWith(prefix));
		}

		private Set<String> regularPackPaths() {
			try (Stream<Path> paths = Files.walk(packRoot)) {
				return paths
					.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> !Files.isSymbolicLink(path))
					.map(path -> packRoot.relativize(path).toString().replace('\\', '/'))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			} catch (IOException exception) {
				return Set.of();
			}
		}

		private ValidationReport report(Clock clock) {
			List<ValidationIssue> ordered = issues.stream()
				.sorted(Comparator
					.comparingInt((ValidationIssue issue) -> switch (issue.severity()) {
						case ERROR -> 0;
						case WARNING -> 1;
						case INFORMATION -> 2;
						case PASSED -> 3;
					})
					.thenComparing(ValidationIssue::code)
					.thenComparing(ValidationIssue::packPath))
				.toList();
			return new ValidationReport(projectId, clock.instant(), fileCount, ordered);
		}
	}
}
