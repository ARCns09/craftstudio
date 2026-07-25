package dev.arcn.craftstudio.preview.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.preview.domain.PreviewMode;
import dev.arcn.craftstudio.preview.domain.PreviewScene;
import dev.arcn.craftstudio.preview.domain.PreviewScene.DisplayTransform;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Face;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Texture;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Vertex;
import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;

public final class PreviewService {
	private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
	private static final ResourcePath MISSING_TEXTURE_PATH =
		new ResourcePath("minecraft", "textures/missingno.png");

	private final AssetSource source;
	private final String targetVersion;
	private final Map<ResourceIdentifier, EffectiveModel> modelCache = new HashMap<>();

	public PreviewService(AssetSource source, String targetVersion) {
		this.source = Objects.requireNonNull(source, "source");
		this.targetVersion = requireText(targetVersion, "targetVersion");
	}

	public PreviewScene createScene(AssetResolutionResult resolution) {
		Objects.requireNonNull(resolution, "resolution");
		List<String> diagnostics = new ArrayList<>();
		List<Variant> variants = new ArrayList<>();
		boolean hasSpecialRenderer = resolution.graph().nodes().values().stream()
			.anyMatch(node -> node.type() == GraphNodeType.SPECIAL_RENDERER);

		if (resolution.root().kind() == AssetKind.BLOCK) {
			variants.addAll(createBlockVariants(resolution, diagnostics));
			variants.addAll(createItemVariants(
				resolution.root().namespace(),
				resolution.root().path(),
				"Inventory",
				diagnostics
			));
		} else {
			variants.addAll(createItemVariants(
				resolution.root().namespace(),
				resolution.root().path(),
				"Item",
				diagnostics
			));
		}
		if (hasSpecialRenderer) {
			diagnostics.add(
				"Some branches use a special renderer and are not represented as standard JSON geometry."
			);
		}
		if (variants.isEmpty()) {
			diagnostics.add(
				hasSpecialRenderer
					? "Preview unavailable because this asset uses a special renderer."
					: "Preview unavailable because no supported standard JSON model was resolved."
			);
		}
		return new PreviewScene(
			resolution.root(),
			variants,
			diagnostics.stream().distinct().toList(),
			source.revision() + "@" + targetVersion
		);
	}

	private List<Variant> createBlockVariants(
		AssetResolutionResult resolution,
		List<String> sceneDiagnostics
	) {
		ResourcePath path = new ResourcePath(
			resolution.root().namespace(),
			"blockstates/" + resolution.root().path() + ".json"
		);
		JsonObject blockstate = readJson(path, sceneDiagnostics, "blockstate");
		if (blockstate == null) {
			return List.of();
		}
		List<Variant> variants = new ArrayList<>();
		if (blockstate.has("variants") && blockstate.get("variants").isJsonObject()) {
			int branchIndex = 0;
			for (Map.Entry<String, JsonElement> branch
				: blockstate.getAsJsonObject("variants").entrySet()) {
				List<JsonObject> applications = modelApplications(branch.getValue());
				for (int alternative = 0; alternative < applications.size(); alternative++) {
					String condition = branch.getKey().isBlank() ? "Default" : branch.getKey();
					String label = applications.size() == 1
						? condition
						: condition + " · alternative " + (alternative + 1);
					Variant variant = bakeApplication(
						"block-" + branchIndex + "-" + alternative,
						label,
						PreviewMode.BLOCK,
						parseProperties(branch.getKey()),
						applications.get(alternative)
					);
					if (variant != null) {
						variants.add(variant);
					}
				}
				branchIndex++;
			}
		}
		if (blockstate.has("multipart")) {
			sceneDiagnostics.add(
				"Multipart branch composition is not rendered in this initial preview; its dependencies remain visible."
			);
		}
		if (variants.isEmpty() && !blockstate.has("multipart")) {
			sceneDiagnostics.add("Blockstate has no supported direct variant applications.");
		}
		return variants;
	}

	private List<Variant> createItemVariants(
		String namespace,
		String path,
		String labelPrefix,
		List<String> sceneDiagnostics
	) {
		ResourcePath definitionPath = new ResourcePath(namespace, "items/" + path + ".json");
		JsonObject definition = readJson(definitionPath, sceneDiagnostics, "client item definition");
		if (definition == null) {
			return List.of();
		}
		LinkedHashMap<ResourceIdentifier, String> models = new LinkedHashMap<>();
		collectItemModels(definition, "$.model", models);
		List<Variant> variants = new ArrayList<>();
		int index = 0;
		for (Map.Entry<ResourceIdentifier, String> entry : models.entrySet()) {
			JsonObject application = new JsonObject();
			application.addProperty("model", entry.getKey().toString());
			Variant variant = bakeApplication(
				"item-" + index,
				models.size() == 1 ? labelPrefix : labelPrefix + " · " + entry.getValue(),
				PreviewMode.ITEM,
				Map.of(),
				application
			);
			if (variant != null) {
				variants.add(variant);
			}
			index++;
		}
		if (models.isEmpty()) {
			sceneDiagnostics.add(
				"Item appearance has no supported plain JSON model branch."
			);
		}
		return variants;
	}

	private void collectItemModels(
		JsonElement element,
		String jsonPath,
		Map<ResourceIdentifier, String> models
	) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (int index = 0; index < array.size(); index++) {
				collectItemModels(array.get(index), jsonPath + "[" + index + "]", models);
			}
			return;
		}
		if (!element.isJsonObject()) {
			return;
		}
		JsonObject object = element.getAsJsonObject();
		String type = stringValue(object, "type");
		String model = stringValue(object, "model");
		if (model != null && (type == null || type.endsWith(":model") || type.equals("model"))) {
			parseIdentifier(model).ifPresent(identifier -> models.putIfAbsent(identifier, jsonPath));
		}
		for (Map.Entry<String, JsonElement> child : object.entrySet()) {
			if (!child.getKey().equals("model") || !child.getValue().isJsonPrimitive()) {
				collectItemModels(child.getValue(), jsonPath + "." + child.getKey(), models);
			}
		}
	}

	private Variant bakeApplication(
		String id,
		String label,
		PreviewMode mode,
		Map<String, String> properties,
		JsonObject application
	) {
		List<String> diagnostics = new ArrayList<>();
		String modelValue = stringValue(application, "model");
		if (modelValue == null) {
			return null;
		}
		Optional<ResourceIdentifier> identifier = parseIdentifier(modelValue);
		if (identifier.isEmpty()) {
			diagnostics.add("Invalid model identifier: " + modelValue);
			return new Variant(
				id,
				label,
				mode,
				properties,
				List.of(),
				Map.of(),
				DisplayTransform.IDENTITY,
				diagnostics
			);
		}
		EffectiveModel model = loadModel(identifier.get(), new LinkedHashSet<>(), diagnostics);
		if (model.elements().isEmpty()) {
			diagnostics.add(
				"Model has no supported standard JSON geometry: " + identifier.get()
			);
			return new Variant(
				id,
				label,
				mode,
				properties,
				List.of(),
				Map.of(),
				DisplayTransform.IDENTITY,
				diagnostics
			);
		}
		float xRotation = floatValue(application, "x", 0.0F);
		float yRotation = floatValue(application, "y", 0.0F);
		Map<String, Texture> textures = new LinkedHashMap<>();
		List<Face> faces = new ArrayList<>();
		for (ElementDefinition element : model.elements()) {
			bakeElement(
				element,
				model.textures(),
				xRotation,
				yRotation,
				faces,
				textures,
				diagnostics
			);
		}
		return new Variant(
			id,
			label,
			mode,
			properties,
			faces,
			textures,
			mode == PreviewMode.ITEM
				? model.guiTransform()
				: DisplayTransform.IDENTITY,
			diagnostics.stream().distinct().toList()
		);
	}

	private EffectiveModel loadModel(
		ResourceIdentifier identifier,
		LinkedHashSet<ResourceIdentifier> chain,
		List<String> diagnostics
	) {
		EffectiveModel cached = modelCache.get(identifier);
		if (cached != null) {
			return cached;
		}
		if (!chain.add(identifier)) {
			diagnostics.add("Model parent cycle: " + chain + " → " + identifier);
			return EffectiveModel.EMPTY;
		}
		ResourcePath path = new ResourcePath(
			identifier.namespace(),
			"models/" + identifier.path() + ".json"
		);
		JsonObject json = readJson(path, diagnostics, "model");
		if (json == null) {
			chain.remove(identifier);
			return EffectiveModel.EMPTY;
		}

		EffectiveModel parent = EffectiveModel.EMPTY;
		String parentValue = stringValue(json, "parent");
		if (parentValue != null && !parentValue.startsWith("builtin/")) {
			Optional<ResourceIdentifier> parentIdentifier = parseIdentifier(parentValue);
			if (parentIdentifier.isPresent()) {
				parent = loadModel(parentIdentifier.get(), chain, diagnostics);
			} else {
				diagnostics.add("Invalid parent model identifier: " + parentValue);
			}
		} else if (parentValue != null) {
			diagnostics.add("Built-in model parent is not supported by the standard preview: " + parentValue);
		}

		Map<String, String> textures = new LinkedHashMap<>(parent.textures());
		if (json.has("textures") && json.get("textures").isJsonObject()) {
			for (Map.Entry<String, JsonElement> texture
				: json.getAsJsonObject("textures").entrySet()) {
				if (texture.getValue().isJsonPrimitive()
					&& texture.getValue().getAsJsonPrimitive().isString()) {
					textures.put(texture.getKey(), texture.getValue().getAsString());
				}
			}
		}
		List<ElementDefinition> elements = json.has("elements")
			? parseElements(json.get("elements"), path, diagnostics)
			: parent.elements();
		DisplayTransform guiTransform = parent.guiTransform();
		if (json.has("display") && json.get("display").isJsonObject()) {
			JsonObject display = json.getAsJsonObject("display");
			if (display.has("gui") && display.get("gui").isJsonObject()) {
				guiTransform = parseDisplayTransform(display.getAsJsonObject("gui"));
			}
		}
		EffectiveModel result = new EffectiveModel(
			Map.copyOf(textures),
			List.copyOf(elements),
			guiTransform
		);
		chain.remove(identifier);
		modelCache.put(identifier, result);
		return result;
	}

	private List<ElementDefinition> parseElements(
		JsonElement value,
		ResourcePath path,
		List<String> diagnostics
	) {
		if (!value.isJsonArray()) {
			diagnostics.add("Model elements are not an array: " + path.packPath());
			return List.of();
		}
		List<ElementDefinition> elements = new ArrayList<>();
		JsonArray array = value.getAsJsonArray();
		for (int index = 0; index < array.size(); index++) {
			if (!array.get(index).isJsonObject()) {
				continue;
			}
			JsonObject element = array.get(index).getAsJsonObject();
			float[] from = vector(element.get("from"), new float[] {0, 0, 0});
			float[] to = vector(element.get("to"), new float[] {16, 16, 16});
			Map<String, FaceDefinition> faces = new LinkedHashMap<>();
			if (element.has("faces") && element.get("faces").isJsonObject()) {
				for (Map.Entry<String, JsonElement> face : element.getAsJsonObject("faces").entrySet()) {
					if (!face.getValue().isJsonObject()) {
						continue;
					}
					JsonObject faceObject = face.getValue().getAsJsonObject();
					String texture = stringValue(faceObject, "texture");
					if (texture == null) {
						continue;
					}
					float[] uv = faceObject.has("uv")
						? vector4(faceObject.get("uv"), defaultUv(face.getKey(), from, to))
						: defaultUv(face.getKey(), from, to);
					int rotation = Math.floorMod((int) floatValue(faceObject, "rotation", 0.0F), 360);
					faces.put(face.getKey(), new FaceDefinition(texture, uv, rotation));
				}
			}
			ElementRotation rotation = parseElementRotation(element.get("rotation"));
			elements.add(new ElementDefinition(from, to, Map.copyOf(faces), rotation));
		}
		return elements;
	}

	private DisplayTransform parseDisplayTransform(JsonObject object) {
		float[] rotation = vector(object.get("rotation"), new float[] {0, 0, 0});
		float[] translation = vector(object.get("translation"), new float[] {0, 0, 0});
		float[] scale = vector(object.get("scale"), new float[] {1, 1, 1});
		return new DisplayTransform(
			rotation[0],
			rotation[1],
			rotation[2],
			clamp(translation[0], -80.0F, 80.0F),
			clamp(translation[1], -80.0F, 80.0F),
			clamp(translation[2], -80.0F, 80.0F),
			clamp(scale[0], -4.0F, 4.0F),
			clamp(scale[1], -4.0F, 4.0F),
			clamp(scale[2], -4.0F, 4.0F)
		);
	}

	private ElementRotation parseElementRotation(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return ElementRotation.NONE;
		}
		JsonObject object = element.getAsJsonObject();
		float[] origin = vector(object.get("origin"), new float[] {8, 8, 8});
		String axis = stringValue(object, "axis");
		float angle = floatValue(object, "angle", 0.0F);
		boolean rescale = object.has("rescale")
			&& object.get("rescale").isJsonPrimitive()
			&& object.get("rescale").getAsBoolean();
		return new ElementRotation(origin, axis == null ? "" : axis, angle, rescale);
	}

	private void bakeElement(
		ElementDefinition element,
		Map<String, String> textureVariables,
		float applicationX,
		float applicationY,
		List<Face> destination,
		Map<String, Texture> textures,
		List<String> diagnostics
	) {
		for (Map.Entry<String, FaceDefinition> faceEntry : element.faces().entrySet()) {
			String direction = faceEntry.getKey();
			FaceDefinition face = faceEntry.getValue();
			ResolvedTexture resolved = resolveTexture(
				face.texture(),
				textureVariables,
				new LinkedHashSet<>(),
				diagnostics
			);
			Texture texture = loadTexture(resolved, diagnostics);
			String textureKey = texture.path().packPath() + (texture.missing() ? "|missing" : "");
			textures.putIfAbsent(textureKey, texture);
			List<float[]> positions = facePositions(direction, element.from(), element.to());
			if (positions.isEmpty()) {
				diagnostics.add("Unsupported model face direction: " + direction);
				continue;
			}
			List<float[]> uv = uvCorners(face.uv(), face.rotation());
			List<Vertex> vertices = new ArrayList<>(4);
			for (int index = 0; index < 4; index++) {
				float[] point = positions.get(index).clone();
				applyElementRotation(point, element.rotation());
				rotateAroundCenter(point, applicationX, applicationY);
				vertices.add(new Vertex(
					point[0],
					point[1],
					point[2],
					uv.get(index)[0] / 16.0F,
					uv.get(index)[1] / 16.0F
				));
			}
			destination.add(new Face(
				direction,
				textureKey,
				vertices,
				brightness(direction),
				texture.missing()
			));
		}
	}

	private ResolvedTexture resolveTexture(
		String reference,
		Map<String, String> variables,
		LinkedHashSet<String> chain,
		List<String> diagnostics
	) {
		if (!reference.startsWith("#")) {
			return parseIdentifier(reference)
				.map(identifier -> new ResolvedTexture(identifier, false))
				.orElseGet(() -> {
					diagnostics.add("Invalid texture identifier: " + reference);
					return ResolvedTexture.MISSING;
				});
		}
		String variable = reference.substring(1);
		if (!chain.add(variable)) {
			diagnostics.add("Texture-variable cycle: #" + String.join(" → #", chain));
			return ResolvedTexture.MISSING;
		}
		String value = variables.get(variable);
		if (value == null) {
			diagnostics.add("Undefined texture variable: #" + variable);
			return ResolvedTexture.MISSING;
		}
		return resolveTexture(value, variables, chain, diagnostics);
	}

	private Texture loadTexture(ResolvedTexture resolved, List<String> diagnostics) {
		ResourcePath path = resolved.missing()
			? MISSING_TEXTURE_PATH
			: new ResourcePath(
				resolved.identifier().namespace(),
				"textures/" + resolved.identifier().path() + ".png"
			);
		if (resolved.missing()) {
			return new Texture(path, new byte[0], SourceLayer.MISSING, true);
		}
		try {
			Optional<ResourceData> resource = source.read(path);
			if (resource.isEmpty()) {
				diagnostics.add("Missing texture: " + path.packPath());
				return new Texture(path, new byte[0], SourceLayer.MISSING, true);
			}
			byte[] bytes = resource.get().bytes();
			if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
				diagnostics.add("Texture is not a decodable image: " + path.packPath());
				return new Texture(path, new byte[0], SourceLayer.MISSING, true);
			}
			return new Texture(path, bytes, resource.get().layer(), false);
		} catch (IOException exception) {
			diagnostics.add("Could not read texture " + path.packPath() + ": " + exception.getMessage());
			return new Texture(path, new byte[0], SourceLayer.MISSING, true);
		}
	}

	private JsonObject readJson(
		ResourcePath path,
		List<String> diagnostics,
		String description
	) {
		try {
			Optional<ResourceData> resource = source.read(path);
			if (resource.isEmpty()) {
				diagnostics.add("Missing " + description + ": " + path.packPath());
				return null;
			}
			byte[] bytes = resource.get().bytes();
			if (bytes.length > MAX_JSON_BYTES) {
				diagnostics.add(description + " exceeds the 8 MiB preview limit: " + path.packPath());
				return null;
			}
			JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				diagnostics.add(description + " root is not an object: " + path.packPath());
				return null;
			}
			return parsed.getAsJsonObject();
		} catch (IOException | RuntimeException exception) {
			diagnostics.add(
				"Could not parse " + description + " " + path.packPath() + ": "
					+ safeMessage(exception)
			);
			return null;
		}
	}

	private List<JsonObject> modelApplications(JsonElement element) {
		if (element.isJsonObject()) {
			return List.of(element.getAsJsonObject());
		}
		if (!element.isJsonArray()) {
			return List.of();
		}
		List<JsonObject> applications = new ArrayList<>();
		for (JsonElement child : element.getAsJsonArray()) {
			if (child.isJsonObject()) {
				applications.add(child.getAsJsonObject());
			}
		}
		return applications;
	}

	private Map<String, String> parseProperties(String condition) {
		if (condition.isBlank()) {
			return Map.of();
		}
		Map<String, String> properties = new LinkedHashMap<>();
		for (String assignment : condition.split(",")) {
			int separator = assignment.indexOf('=');
			if (separator > 0 && separator < assignment.length() - 1) {
				properties.put(
					assignment.substring(0, separator).strip(),
					assignment.substring(separator + 1).strip()
				);
			}
		}
		return properties;
	}

	private Optional<ResourceIdentifier> parseIdentifier(String value) {
		try {
			String normalized = requireText(value, "identifier");
			int separator = normalized.indexOf(':');
			String namespace = separator < 0 ? "minecraft" : normalized.substring(0, separator);
			String path = separator < 0 ? normalized : normalized.substring(separator + 1);
			ResourcePath validator = new ResourcePath(namespace, path);
			return Optional.of(new ResourceIdentifier(validator.namespace(), validator.path()));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private List<float[]> facePositions(String direction, float[] from, float[] to) {
		float x1 = from[0];
		float y1 = from[1];
		float z1 = from[2];
		float x2 = to[0];
		float y2 = to[1];
		float z2 = to[2];
		return switch (direction) {
			case "north" -> points(
				point(x2, y2, z1), point(x2, y1, z1), point(x1, y1, z1), point(x1, y2, z1)
			);
			case "south" -> points(
				point(x1, y2, z2), point(x1, y1, z2), point(x2, y1, z2), point(x2, y2, z2)
			);
			case "west" -> points(
				point(x1, y2, z1), point(x1, y1, z1), point(x1, y1, z2), point(x1, y2, z2)
			);
			case "east" -> points(
				point(x2, y2, z2), point(x2, y1, z2), point(x2, y1, z1), point(x2, y2, z1)
			);
			case "up" -> points(
				point(x1, y2, z1), point(x1, y2, z2), point(x2, y2, z2), point(x2, y2, z1)
			);
			case "down" -> points(
				point(x1, y1, z2), point(x1, y1, z1), point(x2, y1, z1), point(x2, y1, z2)
			);
			default -> List.of();
		};
	}

	private float[] defaultUv(String direction, float[] from, float[] to) {
		return switch (direction) {
			case "down" -> new float[] {from[0], 16 - to[2], to[0], 16 - from[2]};
			case "up" -> new float[] {from[0], from[2], to[0], to[2]};
			case "north" -> new float[] {16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
			case "south" -> new float[] {from[0], 16 - to[1], to[0], 16 - from[1]};
			case "west" -> new float[] {from[2], 16 - to[1], to[2], 16 - from[1]};
			case "east" -> new float[] {16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
			default -> new float[] {0, 0, 16, 16};
		};
	}

	private List<float[]> uvCorners(float[] uv, int rotation) {
		List<float[]> corners = new ArrayList<>(List.of(
			point2(uv[0], uv[1]),
			point2(uv[0], uv[3]),
			point2(uv[2], uv[3]),
			point2(uv[2], uv[1])
		));
		int turns = Math.floorMod(rotation / 90, 4);
		for (int count = 0; count < turns; count++) {
			corners.add(corners.removeFirst());
		}
		return corners;
	}

	private void applyElementRotation(float[] point, ElementRotation rotation) {
		if (rotation.axis().isEmpty() || rotation.angle() == 0.0F) {
			return;
		}
		rotate(point, rotation.origin(), rotation.axis(), rotation.angle());
		if (rotation.rescale()) {
			double radians = Math.toRadians(rotation.angle());
			float scale = (float) (1.0D / Math.max(0.01D, Math.cos(radians)));
			for (int axis = 0; axis < 3; axis++) {
				if (!rotation.axis().equals(axisName(axis))) {
					point[axis] = rotation.origin()[axis]
						+ (point[axis] - rotation.origin()[axis]) * scale;
				}
			}
		}
	}

	private void rotateAroundCenter(float[] point, float xDegrees, float yDegrees) {
		float[] center = new float[] {8, 8, 8};
		if (xDegrees != 0.0F) {
			rotate(point, center, "x", xDegrees);
		}
		if (yDegrees != 0.0F) {
			rotate(point, center, "y", yDegrees);
		}
	}

	private void rotate(float[] point, float[] origin, String axis, float degrees) {
		double radians = Math.toRadians(degrees);
		double sine = Math.sin(radians);
		double cosine = Math.cos(radians);
		float x = point[0] - origin[0];
		float y = point[1] - origin[1];
		float z = point[2] - origin[2];
		switch (axis) {
			case "x" -> {
				point[1] = origin[1] + (float) (y * cosine - z * sine);
				point[2] = origin[2] + (float) (y * sine + z * cosine);
			}
			case "y" -> {
				point[0] = origin[0] + (float) (x * cosine + z * sine);
				point[2] = origin[2] + (float) (-x * sine + z * cosine);
			}
			case "z" -> {
				point[0] = origin[0] + (float) (x * cosine - y * sine);
				point[1] = origin[1] + (float) (x * sine + y * cosine);
			}
			default -> {
			}
		}
	}

	private float brightness(String direction) {
		return switch (direction) {
			case "up" -> 1.0F;
			case "down" -> 0.55F;
			case "north", "south" -> 0.82F;
			case "east", "west" -> 0.68F;
			default -> 0.85F;
		};
	}

	private float[] vector(JsonElement element, float[] fallback) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
			return fallback.clone();
		}
		JsonArray array = element.getAsJsonArray();
		try {
			return new float[] {
				array.get(0).getAsFloat(),
				array.get(1).getAsFloat(),
				array.get(2).getAsFloat()
			};
		} catch (RuntimeException exception) {
			return fallback.clone();
		}
	}

	private float[] vector4(JsonElement element, float[] fallback) {
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 4) {
			return fallback.clone();
		}
		JsonArray array = element.getAsJsonArray();
		try {
			return new float[] {
				array.get(0).getAsFloat(),
				array.get(1).getAsFloat(),
				array.get(2).getAsFloat(),
				array.get(3).getAsFloat()
			};
		} catch (RuntimeException exception) {
			return fallback.clone();
		}
	}

	private String stringValue(JsonObject object, String key) {
		if (!object.has(key) || !object.get(key).isJsonPrimitive()
			|| !object.get(key).getAsJsonPrimitive().isString()) {
			return null;
		}
		return object.get(key).getAsString();
	}

	private float floatValue(JsonObject object, String key, float fallback) {
		if (!object.has(key) || !object.get(key).isJsonPrimitive()
			|| !object.get(key).getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return object.get(key).getAsFloat();
	}

	private float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private String axisName(int axis) {
		return switch (axis) {
			case 0 -> "x";
			case 1 -> "y";
			default -> "z";
		};
	}

	private String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank()
			? throwable.getClass().getSimpleName()
			: message;
	}

	private static float[] point(float x, float y, float z) {
		return new float[] {x, y, z};
	}

	private static float[] point2(float u, float v) {
		return new float[] {u, v};
	}

	private static List<float[]> points(float[]... points) {
		return List.of(points);
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}

	private record ResourceIdentifier(String namespace, String path) {
		private ResourceIdentifier {
			namespace = ResourcePath.validateNamespace(namespace);
			path = new ResourcePath(namespace, path).path();
		}

		@Override
		public String toString() {
			return namespace + ":" + path;
		}
	}

	private record EffectiveModel(
		Map<String, String> textures,
		List<ElementDefinition> elements,
		DisplayTransform guiTransform
	) {
		private static final EffectiveModel EMPTY = new EffectiveModel(
			Map.of(),
			List.of(),
			DisplayTransform.IDENTITY
		);
	}

	private record ElementDefinition(
		float[] from,
		float[] to,
		Map<String, FaceDefinition> faces,
		ElementRotation rotation
	) {
	}

	private record FaceDefinition(String texture, float[] uv, int rotation) {
	}

	private record ElementRotation(float[] origin, String axis, float angle, boolean rescale) {
		private static final ElementRotation NONE =
			new ElementRotation(new float[] {8, 8, 8}, "", 0, false);
	}

	private record ResolvedTexture(ResourceIdentifier identifier, boolean missing) {
		private static final ResolvedTexture MISSING =
			new ResolvedTexture(new ResourceIdentifier("minecraft", "missingno"), true);
	}
}
