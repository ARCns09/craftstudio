package dev.arcn.craftstudio.graph.domain;

import java.util.Objects;

public record AssetGraphEdge(
	String fromNodeId,
	String toNodeId,
	GraphEdgeType type,
	String label
) {
	public AssetGraphEdge {
		fromNodeId = requireText(fromNodeId, "fromNodeId");
		toNodeId = requireText(toNodeId, "toNodeId");
		type = Objects.requireNonNull(type, "type");
		label = Objects.requireNonNull(label, "label");
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
