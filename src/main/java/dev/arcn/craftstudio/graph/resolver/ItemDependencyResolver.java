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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ItemDependencyResolver {
	private static final String DEFAULT_NAMESPACE = "minecraft";
	private static final int MAX_DEFINITION_DEPTH = 64;

	private final AssetSource source;
	private final String targetVersion;

	public ItemDependencyResolver(AssetSource source, String targetVersion) {
		this.source = Objects.requireNonNull(source, "source");
		this.targetVersion = requireText(targetVersion, "targetVersion");
	}

	public AssetResolutionResult resolve(AssetKey item) {
		if (item.kind() != AssetKind.ITEM) {
			throw new IllegalArgumentException("ItemDependencyResolver only accepts item asset keys.");
		}
		return new ResolutionContext(item).resolve();
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
		private final Set<String> usedAtlases = new LinkedHashSet<>();
		private final String rootNodeId;
		private ResourcePath itemDefinitionPath;

		private ResolutionContext(AssetKey root) {
			this.root = root;
			AssetGraphNode rootNode = node(
				GraphNodeType.ITEM,
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
			itemDefinitionPath = new ResourcePath(
				root.namespace(),
				"items/" + root.path() + ".json"
			);
			LoadedJson definition = loadJson(
				itemDefinitionPath,
				GraphNodeType.CLIENT_ITEM_FILE,
				DependencyClassification.REQUIRED_TRANSITIVE,
				true,
				"MISSING_CLIENT_ITEM"
			);
			addEdge(rootNodeId, definition.nodeId(), GraphEdgeType.HAS_CLIENT_ITEM, "item definition");
			if (definition.json() != null) {
				JsonElement model = definition.json().get("model");
				if (model == null) {
					addIssue(
						ResolutionIssueSeverity.ERROR,
						"MISSING_ITEM_MODEL_DEFINITION",
						"Client item definition has no model field.",
						itemDefinitionPath,
						"$.model"
					);
				} else {
					traverseDefinition(
						model,
						definition.nodeId(),
						"Default",
						"$.model",
						0
					);
				}
			}
			validateAtlasConstraints();

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

		private void traverseDefinition(
			JsonElement element,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			if (depth > MAX_DEFINITION_DEPTH) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"ITEM_DEFINITION_DEPTH_EXCEEDED",
					"Item model definition exceeds the maximum supported nesting depth.",
					itemDefinitionPath,
					jsonPath
				);
				return;
			}
			if (element == null || !element.isJsonObject()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_ITEM_MODEL_DEFINITION",
					"Item model definition must be a JSON object.",
					itemDefinitionPath,
					jsonPath
				);
				return;
			}
			JsonObject definition = element.getAsJsonObject();
			String type = normalizeType(stringValue(definition, "type"));
			if (type == null) {
				addIssue(
					ResolutionIssueSeverity.WARNING,
					"UNSUPPORTED_PREVIEW",
					"Item model branch has no registered type; direct references were still inspected.",
					itemDefinitionPath,
					jsonPath
				);
				scanDirectReferences(definition, ownerNodeId, branch, jsonPath, depth + 1);
				return;
			}

			switch (type) {
				case "minecraft:model" -> traversePlainModel(
					definition,
					ownerNodeId,
					branch,
					jsonPath
				);
				case "minecraft:composite" -> traverseComposite(
					definition,
					ownerNodeId,
					branch,
					jsonPath,
					depth
				);
				case "minecraft:condition" -> traverseCondition(
					definition,
					ownerNodeId,
					branch,
					jsonPath,
					depth
				);
				case "minecraft:select" -> traverseSelect(
					definition,
					ownerNodeId,
					branch,
					jsonPath,
					depth
				);
				case "minecraft:range_dispatch" -> traverseRange(
					definition,
					ownerNodeId,
					branch,
					jsonPath,
					depth
				);
				case "minecraft:empty" -> addBuiltinBranch(
					ownerNodeId,
					"empty@" + jsonPath,
					branch + " · empty",
					DependencyClassification.OPTIONAL,
					Map.of("definition_type", type)
				);
				case "minecraft:special" -> traverseSpecial(
					definition,
					ownerNodeId,
					branch,
					jsonPath
				);
				case "minecraft:bundle/selected_item" -> traverseDynamicBuiltin(
					ownerNodeId,
					type,
					branch,
					jsonPath
				);
				default -> traverseUnsupported(
					definition,
					ownerNodeId,
					type,
					branch,
					jsonPath,
					depth
				);
			}
		}

		private void traversePlainModel(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath
		) {
			String modelValue = stringValue(definition, "model");
			if (modelValue == null) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_MODEL_REFERENCE",
					"Plain item model branch has no render model identifier.",
					itemDefinitionPath,
					jsonPath + ".model"
				);
				return;
			}
			ResourceIdentifier identifier = parseIdentifier(
				modelValue,
				itemDefinitionPath,
				jsonPath + ".model"
			);
			if (identifier == null) {
				return;
			}
			ModelResolution model = loadModel(identifier);
			addEdge(ownerNodeId, model.nodeId(), GraphEdgeType.SELECTS_MODEL, branch);
			resolveModelTextures(model);
		}

		private void traverseComposite(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			JsonElement models = definition.get("models");
			if (models == null || !models.isJsonArray()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_COMPOSITE_MODELS",
					"Composite item model must contain a models array.",
					itemDefinitionPath,
					jsonPath + ".models"
				);
				return;
			}
			JsonArray array = models.getAsJsonArray();
			for (int index = 0; index < array.size(); index++) {
				traverseDefinition(
					array.get(index),
					ownerNodeId,
					branch + " · composite layer " + (index + 1),
					jsonPath + ".models[" + index + "]",
					depth + 1
				);
			}
		}

		private void traverseCondition(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			String property = Objects.requireNonNullElse(
				stringValue(definition, "property"),
				"unknown property"
			);
			traverseRequiredBranch(
				definition,
				"on_false",
				ownerNodeId,
				branch + " · " + property + " = false",
				jsonPath,
				depth
			);
			traverseRequiredBranch(
				definition,
				"on_true",
				ownerNodeId,
				branch + " · " + property + " = true",
				jsonPath,
				depth
			);
		}

		private void traverseSelect(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			String property = Objects.requireNonNullElse(
				stringValue(definition, "property"),
				"unknown property"
			);
			JsonElement cases = definition.get("cases");
			if (cases == null || !cases.isJsonArray()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_SELECT_CASES",
					"Select item model must contain a cases array.",
					itemDefinitionPath,
					jsonPath + ".cases"
				);
			} else {
				JsonArray array = cases.getAsJsonArray();
				for (int index = 0; index < array.size(); index++) {
					JsonElement caseElement = array.get(index);
					if (!caseElement.isJsonObject()) {
						addIssue(
							ResolutionIssueSeverity.ERROR,
							"INVALID_SELECT_CASE",
							"Select case must be a JSON object.",
							itemDefinitionPath,
							jsonPath + ".cases[" + index + "]"
						);
						continue;
					}
					JsonObject caseObject = caseElement.getAsJsonObject();
					JsonElement model = caseObject.get("model");
					if (model == null) {
						addIssue(
							ResolutionIssueSeverity.ERROR,
							"MISSING_SELECT_MODEL",
							"Select case has no model branch.",
							itemDefinitionPath,
							jsonPath + ".cases[" + index + "].model"
						);
						continue;
					}
					String when = describeValue(caseObject.get("when"));
					traverseDefinition(
						model,
						ownerNodeId,
						branch + " · " + property + " = " + when,
						jsonPath + ".cases[" + index + "].model",
						depth + 1
					);
				}
			}
			traverseOptionalBranch(
				definition,
				"fallback",
				ownerNodeId,
				branch + " · " + property + " fallback",
				jsonPath,
				depth
			);
		}

		private void traverseRange(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			String property = Objects.requireNonNullElse(
				stringValue(definition, "property"),
				"unknown property"
			);
			JsonElement entries = definition.get("entries");
			if (entries == null || !entries.isJsonArray()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_RANGE_ENTRIES",
					"Range-dispatch item model must contain an entries array.",
					itemDefinitionPath,
					jsonPath + ".entries"
				);
			} else {
				JsonArray array = entries.getAsJsonArray();
				for (int index = 0; index < array.size(); index++) {
					JsonElement entryElement = array.get(index);
					if (!entryElement.isJsonObject()) {
						addIssue(
							ResolutionIssueSeverity.ERROR,
							"INVALID_RANGE_ENTRY",
							"Range entry must be a JSON object.",
							itemDefinitionPath,
							jsonPath + ".entries[" + index + "]"
						);
						continue;
					}
					JsonObject entry = entryElement.getAsJsonObject();
					JsonElement model = entry.get("model");
					if (model == null) {
						addIssue(
							ResolutionIssueSeverity.ERROR,
							"MISSING_RANGE_MODEL",
							"Range entry has no model branch.",
							itemDefinitionPath,
							jsonPath + ".entries[" + index + "].model"
						);
						continue;
					}
					String threshold = describeValue(entry.get("threshold"));
					traverseDefinition(
						model,
						ownerNodeId,
						branch + " · " + property + " ≥ " + threshold,
						jsonPath + ".entries[" + index + "].model",
						depth + 1
					);
				}
			}
			traverseOptionalBranch(
				definition,
				"fallback",
				ownerNodeId,
				branch + " · " + property + " fallback",
				jsonPath,
				depth
			);
		}

		private void traverseSpecial(
			JsonObject definition,
			String ownerNodeId,
			String branch,
			String jsonPath
		) {
			String base = stringValue(definition, "base");
			if (base != null) {
				ResourceIdentifier identifier = parseIdentifier(
					base,
					itemDefinitionPath,
					jsonPath + ".base"
				);
				if (identifier != null) {
					ModelResolution model = loadModel(identifier);
					addEdge(
						ownerNodeId,
						model.nodeId(),
						GraphEdgeType.USES_MODEL,
						branch + " · special renderer base"
					);
					resolveModelTextures(model);
				}
			}
			JsonObject specialModel = definition.has("model") && definition.get("model").isJsonObject()
				? definition.getAsJsonObject("model")
				: null;
			String specialType = specialModel == null
				? "minecraft:unknown"
				: Objects.requireNonNullElse(
					normalizeType(stringValue(specialModel, "type")),
					"minecraft:unknown"
				);
			markSpecial(ownerNodeId, specialType + "@" + jsonPath, branch + " · special renderer");
			addIssue(
				ResolutionIssueSeverity.WARNING,
				"UNSUPPORTED_PREVIEW",
				"Special item renderer " + specialType + " is preserved but cannot use standard model preview.",
				itemDefinitionPath,
				jsonPath
			);
		}

		private void traverseDynamicBuiltin(
			String ownerNodeId,
			String type,
			String branch,
			String jsonPath
		) {
			addBuiltinBranch(
				ownerNodeId,
				type.substring("minecraft:".length()) + "@" + jsonPath,
				branch + " · dynamic selected item",
				DependencyClassification.UNSUPPORTED_SPECIAL_CASE,
				Map.of("definition_type", type)
			);
			addIssue(
				ResolutionIssueSeverity.WARNING,
				"UNSUPPORTED_PREVIEW",
				"Dynamic item branch " + type + " is preserved for diagnostics.",
				itemDefinitionPath,
				jsonPath
			);
		}

		private void traverseUnsupported(
			JsonObject definition,
			String ownerNodeId,
			String type,
			String branch,
			String jsonPath,
			int depth
		) {
			AssetGraphNode unknown = node(
				GraphNodeType.UNKNOWN_RESOURCE,
				root.namespace(),
				type + "@" + jsonPath,
				"",
				source.layer(),
				DependencyClassification.UNSUPPORTED_SPECIAL_CASE,
				Map.of(
					"definition_type", type,
					"raw_json", definition.toString()
				)
			);
			addNode(unknown);
			addEdge(ownerNodeId, unknown.id(), GraphEdgeType.SELECTS_MODEL, branch + " · unsupported");
			addIssue(
				ResolutionIssueSeverity.WARNING,
				"UNSUPPORTED_PREVIEW",
				"Unsupported item model type " + type + "; direct references were still inspected.",
				itemDefinitionPath,
				jsonPath
			);
			scanDirectReferences(definition, unknown.id(), branch, jsonPath, depth + 1);
		}

		private void scanDirectReferences(
			JsonElement element,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			if (depth > MAX_DEFINITION_DEPTH) {
				return;
			}
			if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
					String childPath = jsonPath + "." + entry.getKey();
					JsonElement value = entry.getValue();
					if ((entry.getKey().equals("model") || entry.getKey().equals("base"))
						&& value.isJsonPrimitive()
						&& value.getAsJsonPrimitive().isString()) {
						ResourceIdentifier identifier = parseIdentifier(
							value.getAsString(),
							itemDefinitionPath,
							childPath
						);
						if (identifier != null) {
							ModelResolution model = loadModel(identifier);
							addEdge(
								ownerNodeId,
								model.nodeId(),
								GraphEdgeType.USES_MODEL,
								branch + " · discovered reference"
							);
							resolveModelTextures(model);
						}
					} else if (value.isJsonObject()
						&& stringValue(value.getAsJsonObject(), "type") != null) {
						traverseDefinition(
							value,
							ownerNodeId,
							branch + " · discovered branch",
							childPath,
							depth + 1
						);
					} else {
						scanDirectReferences(value, ownerNodeId, branch, childPath, depth + 1);
					}
				}
			} else if (element.isJsonArray()) {
				JsonArray array = element.getAsJsonArray();
				for (int index = 0; index < array.size(); index++) {
					scanDirectReferences(
						array.get(index),
						ownerNodeId,
						branch,
						jsonPath + "[" + index + "]",
						depth + 1
					);
				}
			}
		}

		private void traverseRequiredBranch(
			JsonObject definition,
			String field,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			JsonElement value = definition.get(field);
			if (value == null) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MISSING_CONDITION_BRANCH",
					"Condition item model has no " + field + " branch.",
					itemDefinitionPath,
					jsonPath + "." + field
				);
				return;
			}
			traverseDefinition(value, ownerNodeId, branch, jsonPath + "." + field, depth + 1);
		}

		private void traverseOptionalBranch(
			JsonObject definition,
			String field,
			String ownerNodeId,
			String branch,
			String jsonPath,
			int depth
		) {
			JsonElement value = definition.get(field);
			if (value != null) {
				traverseDefinition(value, ownerNodeId, branch, jsonPath + "." + field, depth + 1);
			}
		}

		private void addBuiltinBranch(
			String ownerNodeId,
			String logicalPath,
			String label,
			DependencyClassification classification,
			Map<String, String> attributes
		) {
			AssetGraphNode builtin = node(
				GraphNodeType.BUILTIN_MODEL,
				root.namespace(),
				logicalPath,
				"",
				source.layer(),
				classification,
				attributes
			);
			addNode(builtin);
			addEdge(ownerNodeId, builtin.id(), GraphEdgeType.SELECTS_MODEL, label);
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
				AssetGraphNode cycleNode = modelNode(
					identifier,
					source.layer(),
					DependencyClassification.REQUIRED_TRANSITIVE,
					Map.of()
				);
				addNode(cycleNode);
				return new ModelResolution(identifier, cycleNode.id(), Map.of(), Set.of(), false, false);
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
			effectiveTextures.keySet().stream()
				.filter(key -> key.matches("layer\\d+"))
				.map(key -> "#" + key)
				.forEach(usedTextureReferences::add);

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
				markSpecial(modelNode.id(), identifier + "#special_parent", "Inherited special renderer");
			}
			return result;
		}

		private void collectElementTextureReferences(
			JsonElement elements,
			Set<String> destination,
			ResourcePath path
		) {
			if (!elements.isJsonArray()) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"INVALID_MODEL_ELEMENTS",
					"Model elements must be a JSON array.",
					path,
					"$.elements"
				);
				return;
			}
			JsonArray array = elements.getAsJsonArray();
			for (JsonElement element : array) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonElement faces = element.getAsJsonObject().get("faces");
				if (faces == null || !faces.isJsonObject()) {
					continue;
				}
				for (JsonElement face : faces.getAsJsonObject().asMap().values()) {
					if (!face.isJsonObject()) {
						continue;
					}
					String texture = stringValue(face.getAsJsonObject(), "texture");
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
			if (!model.hasGeometry()
				&& model.usedTextureReferences().isEmpty()
				&& !model.specialRenderer()
				&& !model.identifier().path().startsWith("builtin/")) {
				markSpecial(
					model.nodeId(),
					model.identifier() + "#no_standard_geometry",
					"Model has no standard geometry or generated layers"
				);
				addIssue(
					ResolutionIssueSeverity.WARNING,
					"UNSUPPORTED_PREVIEW",
					"Render model " + model.identifier() + " has no standard geometry or generated layers.",
					modelPath(model.identifier()),
					"$"
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
				String textureNodeId = addTexture(texture.identifier());
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
				addTextureIssue(
					"UNDEFINED_TEXTURE_VARIABLE",
					"Texture variable name is empty.",
					model,
					variableChain
				);
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

		private String addTexture(ResourceIdentifier identifier) {
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

			String atlas = atlasFor(identifier);
			usedAtlases.add(atlas);
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
			AssetGraphNode atlasNode = node(
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
			addNode(atlasNode);
			if (data.isEmpty()) {
				addIssue(
					ResolutionIssueSeverity.WARNING,
					"MISSING_ATLAS",
					"Atlas definition is missing: " + atlasPath.packPath(),
					atlasPath,
					"$"
				);
			}
			atlasNodes.put(atlas, atlasNode.id());
			return atlasNode.id();
		}

		private void validateAtlasConstraints() {
			if (usedAtlases.contains("items") && usedAtlases.contains("blocks")) {
				addIssue(
					ResolutionIssueSeverity.ERROR,
					"MIXED_ITEM_ATLASES",
					"Item branches resolve textures from both item and block atlases.",
					itemDefinitionPath,
					"$.model"
				);
			}
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
				return new LoadedJson(missing.id(), null);
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
			return new LoadedJson(loaded.id(), json);
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

	private static String normalizeType(String type) {
		if (type == null || type.isBlank()) {
			return null;
		}
		return type.contains(":") ? type : DEFAULT_NAMESPACE + ":" + type;
	}

	private static String atlasFor(ResourceIdentifier texture) {
		return texture.path().startsWith("item/") || texture.path().startsWith("trims/items/")
			? "items"
			: "blocks";
	}

	private static String describeValue(JsonElement value) {
		if (value == null) {
			return "unspecified";
		}
		if (value.isJsonArray()) {
			List<String> values = new ArrayList<>();
			for (JsonElement entry : value.getAsJsonArray()) {
				values.add(describeValue(entry));
			}
			return String.join(" | ", values);
		}
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

	private record LoadedJson(String nodeId, JsonObject json) {
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
