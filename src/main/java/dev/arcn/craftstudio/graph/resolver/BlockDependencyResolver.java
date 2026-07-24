package dev.arcn.craftstudio.graph.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetGraph;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.DependencyClassification;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.graph.domain.ResolutionIssue;
import dev.arcn.craftstudio.graph.domain.ResolutionIssueSeverity;
import dev.arcn.craftstudio.graph.domain.ResolutionStats;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class BlockDependencyResolver {
	private static final String DEFAULT_NAMESPACE = "minecraft";

	private final AssetSource source;
	private final String targetVersion;

	public BlockDependencyResolver(AssetSource source, String targetVersion) {
		this.source = Objects.requireNonNull(source, "source");
		this.targetVersion = requireText(targetVersion, "targetVersion");
	}

	public AssetResolutionResult resolve(AssetKey block) {
		if (block.kind() != AssetKind.BLOCK) {
			throw new IllegalArgumentException("BlockDependencyResolver only accepts block asset keys.");
		}
		ResolutionContext context = new ResolutionContext(block);
		return context.resolve();
	}

	private final class ResolutionContext {
		private final AssetKey root;
		private final LinkedHashMap<String, AssetGraphNode> nodes = new LinkedHashMap<>();
		private final LinkedHashSet<AssetGraphEdge> edges = new LinkedHashSet<>();
		private final List<ResolutionIssue> issues = new ArrayList<>();
		private final Map<ResourceIdentifier, ModelResolution> modelCache = new HashMap<>();
		private final Set<ResourceIdentifier> resolvingModels = new LinkedHashSet<>();
		private final Set<String> resolvedModelTextures = new HashSet<>();
		private final Map<String, String> atlasNodes = new HashMap<>();
		private final String rootNodeId;

		private ResolutionContext(AssetKey root) {
			this.root = root;
			AssetGraphNode rootNode = node(
				GraphNodeType.BLOCK,
				root.namespace(),
				root.path(),
				"",
				source.layer(),
				DependencyClassification.REQUIRED_ROOT,
				Map.of("identifier", root.identifier())
			);
			addNode(rootNode);
			rootNodeId = rootNode.id();
		}

		private AssetResolutionResult resolve() {
			ResourcePath blockstatePath = new ResourcePath(
				root.namespace(),
				"blockstates/" + root.path() + ".json"
			);
			LoadedJson blockstate = loadJson(
				blockstatePath,
				GraphNodeType.BLOCKSTATE_FILE,
				DependencyClassification.REQUIRED_TRANSITIVE,
				true,
				"MISSING_BLOCKSTATE"
			);
			addEdge(rootNodeId, blockstate.nodeId(), GraphEdgeType.HAS_BLOCKSTATE, "block appearance");
			if (blockstate.json() != null) {
				parseBlockstate(blockstate);
			}
			resolveBlockItemRepresentation();

			AssetGraph graph = new AssetGraph(rootNodeId, nodes, List.copyOf(edges));
			int missingCount = (int) nodes.values().stream()
				.filter(node -> node.sourceLayer() == SourceLayer.MISSING)
				.count();
			return new AssetResolutionResult(
				root,
				graph,
				issues,
				new ResolutionStats(nodes.size(), edges.size(), issues.size(), missingCount)
			);
		}

		private void parseBlockstate(LoadedJson blockstate) {
			JsonObject json = blockstate.json();
			boolean foundDefinition = false;
			if (json.has("variants")) {
				foundDefinition = true;
				if (json.get("variants").isJsonObject()) {
					for (Map.Entry<String, JsonElement> variant : json.getAsJsonObject("variants").entrySet()) {
						String condition = variant.getKey().isEmpty() ? "default" : variant.getKey();
						parseModelApplications(
							blockstate.nodeId(),
							variant.getValue(),
							GraphEdgeType.HAS_VARIANT,
							"variant " + condition,
							blockstate.path(),
							"$.variants." + variant.getKey()
						);
					}
				} else {
					addIssue(
						ResolutionIssueSeverity.ERROR,
						"INVALID_VARIANTS",
						"Blockstate variants must be a JSON object.",
						blockstate.path(),
						"$.variants"
					);
				}
			}
			if (json.has("multipart")) {
				foundDefinition = true;
				if (json.get("multipart").isJsonArray()) {
					JsonArray multipart = json.getAsJsonArray("multipart");
					for (int index = 0; index < multipart.size(); index++) {
						JsonElement partElement = multipart.get(index);
						if (!partElement.isJsonObject()) {
							addIssue(
								ResolutionIssueSeverity.ERROR,
								"INVALID_MULTIPART_CASE",
								"Multipart cases must be JSON objects.",
								blockstate.path(),
								"$.multipart[" + index + "]"
							);
							continue;
						}
						JsonObject part = partElement.getAsJsonObject();
						String condition = part.has("when")
							? describeCondition(part.get("when"))
							: "always";
						if (!part.has("apply")) {
							addIssue(
								ResolutionIssueSeverity.ERROR,
								"MISSING_MULTIPART_APPLY",
								"Multipart case has no apply entry.",
								blockstate.path(),
								"$.multipart[" + index + "]"
							);
							continue;
						}
						parseModelApplications(
							blockstate.nodeId(),
							part.get("apply"),
							GraphEdgeType.HAS_MULTIPART_CASE,
							"multipart " + (index + 1) + " when " + condition,
							blockstate.path(),
							"$.multipart[" + index + "].apply"
						);
					}
				} else {
					addIssue(
						ResolutionIssueSeverity.ERROR,
						"INVALID_MULTIPART",
						"Blockstate multipart must be a JSON array.",
						blockstate.path(),
						"$.multipart"
					);
				}
			}
			if (!foundDefinition) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_BLOCKSTATE_DEFINITION",
					"Blockstate contains neither variants nor multipart definitions.",
					blockstate.path(),
					"$"
				);
			}
		}

		private void parseModelApplications(
			String ownerNodeId,
			JsonElement applications,
			GraphEdgeType edgeType,
			String branchLabel,
			ResourcePath ownerPath,
			String jsonPath
		) {
			if (applications.isJsonArray()) {
				JsonArray alternatives = applications.getAsJsonArray();
				for (int index = 0; index < alternatives.size(); index++) {
					parseModelApplication(
						ownerNodeId,
						alternatives.get(index),
						edgeType,
						branchLabel + " alternative " + (index + 1),
						ownerPath,
						jsonPath + "[" + index + "]"
					);
				}
				return;
			}
			parseModelApplication(ownerNodeId, applications, edgeType, branchLabel, ownerPath, jsonPath);
		}

		private void parseModelApplication(
			String ownerNodeId,
			JsonElement application,
			GraphEdgeType edgeType,
			String branchLabel,
			ResourcePath ownerPath,
			String jsonPath
		) {
			if (!application.isJsonObject()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_MODEL_APPLICATION",
					"Blockstate model application must be a JSON object.",
					ownerPath,
					jsonPath
				);
				return;
			}
			JsonObject object = application.getAsJsonObject();
			String rawModel = stringValue(object, "model");
			if (rawModel == null) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_MODEL_REFERENCE",
					"Blockstate model application has no model identifier.",
					ownerPath,
					jsonPath + ".model"
				);
				return;
			}
			ResourceIdentifier modelIdentifier = parseIdentifier(
				rawModel,
				ownerPath,
				jsonPath + ".model"
			);
			if (modelIdentifier == null) {
				return;
			}
			ModelResolution model = loadModel(modelIdentifier);
			String label = describeApplication(branchLabel, object);
			addEdge(ownerNodeId, model.nodeId(), edgeType, label);
			resolveModelTextures(model);
		}

		private ModelResolution loadModel(ResourceIdentifier identifier) {
			ModelResolution cached = modelCache.get(identifier);
			if (cached != null) {
				return cached;
			}
			if (resolvingModels.contains(identifier)) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MODEL_PARENT_CYCLE",
					"Model parent cycle detected at " + identifier + ".",
					modelPath(identifier),
					"$.parent",
					resolvingModels.stream().map(ResourceIdentifier::toString).toList()
				);
				String nodeId = modelNode(
					identifier,
					source.layer(),
					DependencyClassification.REQUIRED_TRANSITIVE,
					Map.of()
				).id();
				return new ModelResolution(identifier, nodeId, Map.of(), Set.of(), false, false);
			}

			resolvingModels.add(identifier);
			ResourcePath path = modelPath(identifier);
			Optional<ResourceData> resource = read(path);
			if (resource.isEmpty()) {
				resolvingModels.remove(identifier);
				if (identifier.path().startsWith("builtin/")) {
					AssetGraphNode builtin = node(
						GraphNodeType.BUILTIN_MODEL,
						identifier.namespace(),
						identifier.path(),
						"",
						source.layer(),
						DependencyClassification.SHARED_VANILLA,
						Map.of("model", identifier.toString())
					);
					addNode(builtin);
					boolean special = identifier.path().equals("builtin/entity");
					if (special) {
						markSpecial(builtin.id(), identifier.toString(), "Built-in entity renderer");
					}
					ModelResolution result = new ModelResolution(
						identifier,
						builtin.id(),
						Map.of(),
						Set.of(),
						special,
						false
					);
					modelCache.put(identifier, result);
					return result;
				}
				AssetGraphNode missing = modelNode(
					identifier,
					SourceLayer.MISSING,
					DependencyClassification.MISSING,
					Map.of()
				);
				addNode(missing);
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_MODEL",
					"Model file is missing: " + path.packPath(),
					path,
					"$"
				);
				ModelResolution result = new ModelResolution(
					identifier,
					missing.id(),
					Map.of(),
					Set.of(),
					false,
					false
				);
				modelCache.put(identifier, result);
				return result;
			}

			JsonObject json = parseJsonObject(resource.get(), path, "INVALID_MODEL_JSON");
			AssetGraphNode modelNode = modelNode(
				identifier,
				resource.get().layer(),
				DependencyClassification.REQUIRED_TRANSITIVE,
				json == null ? Map.of() : jsonAttributes(json)
			);
			addNode(modelNode);
			if (json == null) {
				resolvingModels.remove(identifier);
				ModelResolution result = new ModelResolution(
					identifier,
					modelNode.id(),
					Map.of(),
					Set.of(),
					false,
					false
				);
				modelCache.put(identifier, result);
				return result;
			}

			Map<String, String> effectiveTextures = new LinkedHashMap<>();
			Set<String> usedTextureReferences = new LinkedHashSet<>();
			boolean special = false;
			boolean hasGeometry = false;
			String parentValue = stringValue(json, "parent");
			if (parentValue != null) {
				ResourceIdentifier parentIdentifier = parseIdentifier(parentValue, path, "$.parent");
				if (parentIdentifier != null) {
					ModelResolution parent = loadModel(parentIdentifier);
					addEdge(modelNode.id(), parent.nodeId(), GraphEdgeType.INHERITS_MODEL, "parent");
					effectiveTextures.putAll(parent.effectiveTextures());
					usedTextureReferences.addAll(parent.usedTextureReferences());
					special = parent.specialRenderer();
					hasGeometry = parent.hasGeometry();
				}
			}
			if (json.has("textures")) {
				if (json.get("textures").isJsonObject()) {
					for (Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures").entrySet()) {
						if (texture.getValue().isJsonPrimitive()
							&& texture.getValue().getAsJsonPrimitive().isString()) {
							effectiveTextures.put(texture.getKey(), texture.getValue().getAsString());
						} else {
							addIssue(
								ResolutionIssueSeverity.WARNING,
								"INVALID_TEXTURE_VALUE",
								"Model texture value is not a string.",
								path,
								"$.textures." + texture.getKey()
							);
						}
					}
				} else {
					addIssue(
						ResolutionIssueSeverity.ERROR,
						"INVALID_TEXTURE_TABLE",
						"Model textures must be a JSON object.",
						path,
						"$.textures"
					);
				}
			}
			if (json.has("elements")) {
				usedTextureReferences.clear();
				collectElementTextureReferences(json.get("elements"), usedTextureReferences, path);
				hasGeometry = json.get("elements").isJsonArray()
					&& !json.getAsJsonArray("elements").isEmpty();
			}
			if (effectiveTextures.containsKey("particle")) {
				usedTextureReferences.add("#particle");
			}

			resolvingModels.remove(identifier);
			ModelResolution result = new ModelResolution(
				identifier,
				modelNode.id(),
				Map.copyOf(effectiveTextures),
				Set.copyOf(usedTextureReferences),
				special,
				hasGeometry
			);
			modelCache.put(identifier, result);
			if (special) {
				markSpecial(modelNode.id(), identifier.toString(), "Inherited special renderer");
			}
			return result;
		}

		private void collectElementTextureReferences(
			JsonElement elements,
			Set<String> destination,
			ResourcePath modelPath
		) {
			if (!elements.isJsonArray()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_MODEL_ELEMENTS",
					"Model elements must be a JSON array.",
					modelPath,
					"$.elements"
				);
				return;
			}
			JsonArray array = elements.getAsJsonArray();
			for (int elementIndex = 0; elementIndex < array.size(); elementIndex++) {
				JsonElement element = array.get(elementIndex);
				if (!element.isJsonObject()) {
					continue;
				}
				JsonElement facesElement = element.getAsJsonObject().get("faces");
				if (facesElement == null || !facesElement.isJsonObject()) {
					continue;
				}
				for (Map.Entry<String, JsonElement> face : facesElement.getAsJsonObject().entrySet()) {
					if (!face.getValue().isJsonObject()) {
						continue;
					}
					String texture = stringValue(face.getValue().getAsJsonObject(), "texture");
					if (texture != null) {
						destination.add(texture);
					}
				}
			}
		}

		private void resolveModelTextures(ModelResolution model) {
			if (!resolvedModelTextures.add(model.nodeId())) {
				return;
			}
			if (!model.hasGeometry() && !model.specialRenderer()) {
				markSpecial(
					model.nodeId(),
					model.identifier() + "#no_standard_geometry",
					"Model has no standard JSON geometry"
				);
			}
			for (String reference : model.usedTextureReferences()) {
				TextureResolution texture = resolveTextureReference(
					reference,
					model.effectiveTextures(),
					model.identifier(),
					new LinkedHashSet<>()
				);
				if (texture == null) {
					continue;
				}
				String textureNodeId = addTexture(texture.identifier(), model.identifier());
				if (reference.startsWith("#")) {
					addEdge(
						model.nodeId(),
						textureNodeId,
						GraphEdgeType.USES_TEXTURE_VARIABLE,
						texture.chain()
					);
				}
				addEdge(
					model.nodeId(),
					textureNodeId,
					GraphEdgeType.RESOLVES_TEXTURE,
					reference + " → " + texture.identifier()
				);
			}
		}

		private TextureResolution resolveTextureReference(
			String reference,
			Map<String, String> textures,
			ResourceIdentifier model,
			LinkedHashSet<String> variableChain
		) {
			if (!reference.startsWith("#")) {
				ResourceIdentifier direct = parseIdentifier(reference, modelPath(model), "$.textures");
				return direct == null ? null : new TextureResolution(direct, reference);
			}
			String variable = reference.substring(1);
			if (variable.isEmpty()) {
				addTextureIssue("UNDEFINED_TEXTURE_VARIABLE", "Texture variable name is empty.", model, variableChain);
				return null;
			}
			if (!variableChain.add(variable)) {
				addTextureIssue(
					"TEXTURE_VARIABLE_CYCLE",
					"Texture variable cycle detected: " + String.join(" → ", variableChain) + " → " + variable,
					model,
					variableChain
				);
				return null;
			}
			String value = textures.get(variable);
			if (value == null) {
				addTextureIssue(
					"UNDEFINED_TEXTURE_VARIABLE",
					"Texture variable #" + variable + " is not defined.",
					model,
					variableChain
				);
				return null;
			}
			TextureResolution resolved = resolveTextureReference(value, textures, model, variableChain);
			if (resolved == null) {
				return null;
			}
			return new TextureResolution(
				resolved.identifier(),
				"#" + String.join(" → #", variableChain) + " → " + resolved.identifier()
			);
		}

		private String addTexture(ResourceIdentifier identifier, ResourceIdentifier modelIdentifier) {
			ResourcePath texturePath = new ResourcePath(
				identifier.namespace(),
				"textures/" + identifier.path() + ".png"
			);
			Optional<ResourceData> texture = read(texturePath);
			AssetGraphNode textureNode;
			if (texture.isPresent()) {
				textureNode = node(
					GraphNodeType.TEXTURE_FILE,
					identifier.namespace(),
					identifier.path(),
					texturePath.packPath(),
					texture.get().layer(),
					DependencyClassification.REQUIRED_TRANSITIVE,
					Map.of("texture", identifier.toString())
				);
			} else {
				textureNode = node(
					GraphNodeType.TEXTURE_FILE,
					identifier.namespace(),
					identifier.path(),
					texturePath.packPath(),
					SourceLayer.MISSING,
					DependencyClassification.MISSING,
					Map.of("texture", identifier.toString())
				);
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_TEXTURE",
					"Texture file is missing: " + texturePath.packPath(),
					texturePath,
					"$"
				);
			}
			addNode(textureNode);

			ResourcePath metadataPath = new ResourcePath(
				identifier.namespace(),
				texturePath.path() + ".mcmeta"
			);
			Optional<ResourceData> metadata = read(metadataPath);
			if (metadata.isPresent()) {
				AssetGraphNode metadataNode = node(
					GraphNodeType.TEXTURE_METADATA_FILE,
					identifier.namespace(),
					identifier.path() + ".png.mcmeta",
					metadataPath.packPath(),
					metadata.get().layer(),
					DependencyClassification.OPTIONAL,
					Map.of()
				);
				addNode(metadataNode);
				addEdge(textureNode.id(), metadataNode.id(), GraphEdgeType.USES_METADATA, "animation metadata");
			}

			String atlas = identifier.path().startsWith("item/") ? "items" : "blocks";
			String atlasNodeId = ensureAtlas(atlas);
			addEdge(textureNode.id(), atlasNodeId, GraphEdgeType.REQUIRES_ATLAS, atlas + " atlas");
			return textureNode.id();
		}

		private String ensureAtlas(String atlas) {
			String cached = atlasNodes.get(atlas);
			if (cached != null) {
				return cached;
			}
			ResourcePath atlasPath = new ResourcePath(DEFAULT_NAMESPACE, "atlases/" + atlas + ".json");
			Optional<ResourceData> data = read(atlasPath);
			AssetGraphNode node = node(
				GraphNodeType.ATLAS_FILE,
				DEFAULT_NAMESPACE,
				atlas,
				atlasPath.packPath(),
				data.map(ResourceData::layer).orElse(SourceLayer.MISSING),
				data.isPresent()
					? DependencyClassification.SHARED_VANILLA
					: DependencyClassification.MISSING,
				Map.of("atlas", atlas)
			);
			addNode(node);
			if (data.isEmpty()) {
				addIssue(
					ResolutionIssueSeverity.WARNING,
					"MISSING_ATLAS",
					"Atlas definition is missing: " + atlasPath.packPath(),
					atlasPath,
					"$"
				);
			}
			atlasNodes.put(atlas, node.id());
			return node.id();
		}

		private void resolveBlockItemRepresentation() {
			ResourcePath itemPath = new ResourcePath(root.namespace(), "items/" + root.path() + ".json");
			LoadedJson item = loadJson(
				itemPath,
				GraphNodeType.CLIENT_ITEM_FILE,
				DependencyClassification.REQUIRED_TRANSITIVE,
				false,
				"MISSING_CLIENT_ITEM"
			);
			addEdge(rootNodeId, item.nodeId(), GraphEdgeType.HAS_CLIENT_ITEM, "inventory appearance");
			if (item.json() == null) {
				return;
			}
			JsonElement modelElement = item.json().get("model");
			if (modelElement == null || !modelElement.isJsonObject()) {
				addIssue(
					ResolutionIssueSeverity.WARNING,
					"UNSUPPORTED_ITEM_DEFINITION",
					"Client item definition has no supported model object.",
					itemPath,
					"$.model"
				);
				return;
			}
			JsonObject model = modelElement.getAsJsonObject();
			String type = stringValue(model, "type");
			if ("minecraft:model".equals(type)) {
				String modelValue = stringValue(model, "model");
				ResourceIdentifier identifier = modelValue == null
					? null
					: parseIdentifier(modelValue, itemPath, "$.model.model");
				if (identifier != null) {
					ModelResolution resolved = loadModel(identifier);
					addEdge(item.nodeId(), resolved.nodeId(), GraphEdgeType.SELECTS_MODEL, "inventory model");
					resolveModelTextures(resolved);
				}
			} else {
				boolean special = scanForSpecialRenderers(modelElement, item.nodeId(), "$.model");
				addIssue(
					ResolutionIssueSeverity.INFO,
					special ? "SPECIAL_ITEM_RENDERER" : "ITEM_DEFINITION_DEFERRED",
					special
						? "Block item uses a special renderer; standard preview will be limited."
						: "Complex block-item definition traversal is deferred to Milestone 6.",
					itemPath,
					"$.model"
				);
			}
		}

		private boolean scanForSpecialRenderers(JsonElement element, String ownerNodeId, String jsonPath) {
			boolean found = false;
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				if ("minecraft:special".equals(stringValue(object, "type"))) {
					JsonObject specialModel = object.has("model") && object.get("model").isJsonObject()
						? object.getAsJsonObject("model")
						: null;
					String specialType = specialModel == null
						? "minecraft:unknown"
						: Objects.requireNonNullElse(stringValue(specialModel, "type"), "minecraft:unknown");
					markSpecial(ownerNodeId, specialType + "@" + jsonPath, "Special item renderer");
					String base = stringValue(object, "base");
					if (base != null) {
						ResourceIdentifier baseIdentifier = parseIdentifier(
							base,
							new ResourcePath(root.namespace(), "items/" + root.path() + ".json"),
							jsonPath + ".base"
						);
						if (baseIdentifier != null) {
							ModelResolution baseModel = loadModel(baseIdentifier);
							addEdge(ownerNodeId, baseModel.nodeId(), GraphEdgeType.USES_MODEL, "special renderer base");
							resolveModelTextures(baseModel);
						}
					}
					found = true;
				}
				for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
					found |= scanForSpecialRenderers(
						entry.getValue(),
						ownerNodeId,
						jsonPath + "." + entry.getKey()
					);
				}
			} else if (element.isJsonArray()) {
				JsonArray array = element.getAsJsonArray();
				for (int index = 0; index < array.size(); index++) {
					found |= scanForSpecialRenderers(
						array.get(index),
						ownerNodeId,
						jsonPath + "[" + index + "]"
					);
				}
			}
			return found;
		}

		private void markSpecial(String ownerNodeId, String logicalPath, String reason) {
			AssetGraphNode special = node(
				GraphNodeType.SPECIAL_RENDERER,
				root.namespace(),
				logicalPath,
				"",
				source.layer(),
				DependencyClassification.UNSUPPORTED_SPECIAL_CASE,
				Map.of("reason", reason)
			);
			addNode(special);
			addEdge(ownerNodeId, special.id(), GraphEdgeType.USES_SPECIAL_RENDERER, reason);
		}

		private LoadedJson loadJson(
			ResourcePath path,
			GraphNodeType type,
			DependencyClassification classification,
			boolean required,
			String missingCode
		) {
			Optional<ResourceData> resource = read(path);
			if (resource.isEmpty()) {
				AssetGraphNode missing = node(
					type,
					path.namespace(),
					path.path(),
					path.packPath(),
					SourceLayer.MISSING,
					DependencyClassification.MISSING,
					Map.of()
				);
				addNode(missing);
				addIssue(
					required ? ResolutionIssueSeverity.ERROR : ResolutionIssueSeverity.INFO,
					missingCode,
					"Resource is missing: " + path.packPath(),
					path,
					"$"
				);
				return new LoadedJson(missing.id(), path, null);
			}
			JsonObject json = parseJsonObject(resource.get(), path, "INVALID_JSON");
			AssetGraphNode loaded = node(
				type,
				path.namespace(),
				path.path(),
				path.packPath(),
				resource.get().layer(),
				classification,
				json == null ? Map.of() : jsonAttributes(json)
			);
			addNode(loaded);
			return new LoadedJson(loaded.id(), path, json);
		}

		private JsonObject parseJsonObject(ResourceData data, ResourcePath path, String code) {
			try {
				JsonElement parsed = JsonParser.parseString(
					new String(data.bytes(), StandardCharsets.UTF_8)
				);
				if (!parsed.isJsonObject()) {
					throw new JsonParseException("Root value is not a JSON object.");
				}
				return parsed.getAsJsonObject();
			} catch (JsonParseException | IllegalStateException exception) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					code,
					"Could not parse " + path.packPath() + ": " + exception.getMessage(),
					path,
					"$"
				);
				return null;
			}
		}

		private Optional<ResourceData> read(ResourcePath path) {
			try {
				return source.read(path);
			} catch (IOException exception) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"RESOURCE_READ_FAILED",
					"Could not read " + path.packPath() + ": " + exception.getMessage(),
					path,
					"$"
				);
				return Optional.empty();
			}
		}

		private ResourceIdentifier parseIdentifier(
			String value,
			ResourcePath ownerPath,
			String jsonPath
		) {
			try {
				return ResourceIdentifier.parse(value);
			} catch (IllegalArgumentException exception) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_RESOURCE_IDENTIFIER",
					exception.getMessage(),
					ownerPath,
					jsonPath
				);
				return null;
			}
		}

		private void addTextureIssue(
			String code,
			String message,
			ResourceIdentifier model,
			Set<String> variableChain
		) {
			addIssue(
				ResolutionIssueSeverity.ERROR,
				code,
				message,
				modelPath(model),
				"$.textures",
				variableChain.stream().map(variable -> "#" + variable).toList()
			);
		}

		private void addIssue(
			ResolutionIssueSeverity severity,
			String code,
			String message,
			ResourcePath path,
			String jsonPath
		) {
			addIssue(severity, code, message, path, jsonPath, List.of(root.identifier(), path.packPath()));
		}

		private void addIssue(
			ResolutionIssueSeverity severity,
			String code,
			String message,
			ResourcePath path,
			String jsonPath,
			List<String> chain
		) {
			issues.add(new ResolutionIssue(
				severity,
				code,
				message,
				path.packPath(),
				jsonPath,
				chain
			));
		}

		private AssetGraphNode modelNode(
			ResourceIdentifier identifier,
			SourceLayer layer,
			DependencyClassification classification,
			Map<String, String> attributes
		) {
			return node(
				GraphNodeType.MODEL_FILE,
				identifier.namespace(),
				identifier.path(),
				modelPath(identifier).packPath(),
				layer,
				classification,
				attributes
			);
		}

		private AssetGraphNode node(
			GraphNodeType type,
			String namespace,
			String logicalPath,
			String packPath,
			SourceLayer layer,
			DependencyClassification classification,
			Map<String, String> attributes
		) {
			return new AssetGraphNode(
				type,
				namespace,
				logicalPath,
				targetVersion,
				packPath,
				layer,
				classification,
				attributes
			);
		}

		private void addNode(AssetGraphNode node) {
			nodes.put(node.id(), node);
		}

		private void addEdge(String from, String to, GraphEdgeType type, String label) {
			edges.add(new AssetGraphEdge(from, to, type, label));
		}

		private ResourcePath modelPath(ResourceIdentifier identifier) {
			return new ResourcePath(
				identifier.namespace(),
				"models/" + identifier.path() + ".json"
			);
		}
	}

	private static String describeApplication(String branchLabel, JsonObject application) {
		List<String> details = new ArrayList<>();
		details.add(branchLabel);
		for (String rotation : List.of("x", "y", "z")) {
			if (application.has(rotation) && application.get(rotation).isJsonPrimitive()) {
				details.add(rotation + "=" + application.get(rotation).getAsString());
			}
		}
		if (application.has("uvlock") && application.get("uvlock").isJsonPrimitive()) {
			details.add("uvlock=" + application.get("uvlock").getAsString());
		}
		if (application.has("weight") && application.get("weight").isJsonPrimitive()) {
			details.add("weight=" + application.get("weight").getAsString());
		}
		return String.join("; ", details);
	}

	private static String describeCondition(JsonElement condition) {
		if (!condition.isJsonObject()) {
			return condition.toString();
		}
		List<String> clauses = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : condition.getAsJsonObject().entrySet()) {
			if ((entry.getKey().equals("OR") || entry.getKey().equals("AND"))
				&& entry.getValue().isJsonArray()) {
				List<String> nested = new ArrayList<>();
				for (JsonElement child : entry.getValue().getAsJsonArray()) {
					nested.add(describeCondition(child));
				}
				clauses.add(entry.getKey() + "(" + String.join(", ", nested) + ")");
			} else {
				clauses.add(entry.getKey() + "=" + primitiveDescription(entry.getValue()));
			}
		}
		return String.join(" AND ", clauses);
	}

	private static String primitiveDescription(JsonElement value) {
		return value.isJsonPrimitive() ? value.getAsString() : value.toString();
	}

	private static String stringValue(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null
			&& value.isJsonPrimitive()
			&& value.getAsJsonPrimitive().isString()
			? value.getAsString()
			: null;
	}

	private static Map<String, String> jsonAttributes(JsonObject object) {
		List<String> fields = new ArrayList<>(object.keySet());
		Collections.sort(fields);
		return Map.of("json_fields", String.join(",", fields));
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}

	private record LoadedJson(String nodeId, ResourcePath path, JsonObject json) {
	}

	private record ModelResolution(
		ResourceIdentifier identifier,
		String nodeId,
		Map<String, String> effectiveTextures,
		Set<String> usedTextureReferences,
		boolean specialRenderer,
		boolean hasGeometry
	) {
	}

	private record TextureResolution(ResourceIdentifier identifier, String chain) {
	}

	private record ResourceIdentifier(String namespace, String path) {
		private ResourceIdentifier {
			namespace = ResourcePath.validateNamespace(namespace);
			path = new ResourcePath(namespace, path).path();
		}

		private static ResourceIdentifier parse(String value) {
			String raw = requireText(value, "resource identifier");
			int separator = raw.indexOf(':');
			if (separator < 0) {
				return new ResourceIdentifier(DEFAULT_NAMESPACE, raw);
			}
			if (separator == 0 || separator != raw.lastIndexOf(':') || separator == raw.length() - 1) {
				throw new IllegalArgumentException("Invalid resource identifier: " + raw);
			}
			return new ResourceIdentifier(raw.substring(0, separator), raw.substring(separator + 1));
		}

		@Override
		public String toString() {
			return namespace + ":" + path;
		}
	}
}
