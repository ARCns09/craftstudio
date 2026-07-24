package dev.arcn.craftstudio.graph.domain;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import java.util.Objects;

public record AssetKey(AssetKind kind, String namespace, String path) {
	public AssetKey {
		kind = Objects.requireNonNull(kind, "kind");
		namespace = ResourcePath.validateNamespace(namespace);
		path = new ResourcePath(namespace, path).path();
	}

	public String identifier() {
		return namespace + ":" + path;
	}
}
