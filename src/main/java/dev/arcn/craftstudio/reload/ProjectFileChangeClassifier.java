package dev.arcn.craftstudio.reload;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class ProjectFileChangeClassifier {
	private ProjectFileChangeClassifier() {
	}

	public static ReloadClassification classify(Path relativePath) {
		String path = Objects.requireNonNull(relativePath, "relativePath")
			.normalize()
			.toString()
			.replace('\\', '/')
			.toLowerCase(Locale.ROOT);
		if (path.equals("pack.mcmeta")) {
			return ReloadClassification.PACK_METADATA;
		}
		if (path.endsWith(".png") || path.endsWith(".png.mcmeta")) {
			return ReloadClassification.TEXTURE;
		}
		if (path.startsWith("assets/")
			&& path.contains("/atlases/")
			&& path.endsWith(".json")) {
			return ReloadClassification.ATLAS;
		}
		if (path.startsWith("assets/")
			&& path.endsWith(".json")
			&& (path.contains("/blockstates/")
				|| path.contains("/models/")
				|| path.contains("/items/"))) {
			return ReloadClassification.MODEL_GRAPH;
		}
		return ReloadClassification.UNKNOWN;
	}
}
