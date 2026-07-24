package dev.arcn.craftstudio.graph.domain;

import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AssetGraphNode(
	GraphNodeType type,
	String namespace,
	String logicalPath,
	String targetVersion,
	String packPath,
	SourceLayer sourceLayer,
	DependencyClassification classification,
	Map<String, String> attributes
) {
	public AssetGraphNode {
		type = Objects.requireNonNull(type, "type");
		namespace = ResourcePath.validateNamespace(namespace);
		logicalPath = requireText(logicalPath, "logicalPath");
		targetVersion = requireText(targetVersion, "targetVersion");
		packPath = Objects.requireNonNull(packPath, "packPath");
		if (!packPath.isEmpty()) {
			ResourcePath.fromPackPath(packPath);
		}
		sourceLayer = Objects.requireNonNull(sourceLayer, "sourceLayer");
		classification = Objects.requireNonNull(classification, "classification");
		attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
	}

	public String id() {
		return type + "|" + namespace + "|" + logicalPath + "|" + targetVersion;
	}

	public Optional<ResourcePath> resourcePath() {
		return packPath.isEmpty()
			? Optional.empty()
			: Optional.of(ResourcePath.fromPackPath(packPath));
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
