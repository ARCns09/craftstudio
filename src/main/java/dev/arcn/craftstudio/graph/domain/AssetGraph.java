package dev.arcn.craftstudio.graph.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AssetGraph(
	String rootNodeId,
	Map<String, AssetGraphNode> nodes,
	List<AssetGraphEdge> edges
) {
	public AssetGraph {
		rootNodeId = Objects.requireNonNull(rootNodeId, "rootNodeId");
		nodes = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(nodes, "nodes")));
		edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
		if (!nodes.containsKey(rootNodeId)) {
			throw new IllegalArgumentException("Asset graph does not contain its root node.");
		}
		for (AssetGraphEdge edge : edges) {
			if (!nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
				throw new IllegalArgumentException("Asset graph edge refers to an unknown node.");
			}
		}
	}

	public List<AssetGraphEdge> outgoing(String nodeId) {
		return edges.stream().filter(edge -> edge.fromNodeId().equals(nodeId)).toList();
	}
}
