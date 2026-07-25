package dev.arcn.craftstudio.preview.domain;

import dev.arcn.craftstudio.graph.domain.AssetKey;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PreviewScene(
	AssetKey root,
	List<Variant> variants,
	List<String> diagnostics,
	String sourceRevision
) {
	public PreviewScene {
		root = Objects.requireNonNull(root, "root");
		variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
		diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
		sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
	}

	public List<Variant> variants(PreviewMode mode) {
		return variants.stream().filter(variant -> variant.mode() == mode).toList();
	}

	public boolean supports(PreviewMode mode) {
		return variants.stream().anyMatch(variant -> variant.mode() == mode);
	}

	public boolean available() {
		return !variants.isEmpty();
	}

	public record Variant(
		String id,
		String label,
		PreviewMode mode,
		Map<String, String> properties,
		List<Face> faces,
		Map<String, Texture> textures,
		List<String> diagnostics
	) {
		public Variant {
			id = requireText(id, "id");
			label = requireText(label, "label");
			mode = Objects.requireNonNull(mode, "mode");
			properties = Map.copyOf(new LinkedHashMap<>(
				Objects.requireNonNull(properties, "properties")
			));
			faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
			textures = Map.copyOf(new LinkedHashMap<>(
				Objects.requireNonNull(textures, "textures")
			));
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
		}

		public long missingFaceCount() {
			return faces.stream().filter(Face::missingTexture).count();
		}
	}

	public record Face(
		String direction,
		String textureKey,
		List<Vertex> vertices,
		float brightness,
		boolean missingTexture
	) {
		public Face {
			direction = requireText(direction, "direction");
			textureKey = requireText(textureKey, "textureKey");
			vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
			if (vertices.size() != 4) {
				throw new IllegalArgumentException("A preview face must have four vertices.");
			}
			if (!Float.isFinite(brightness) || brightness < 0.0F || brightness > 1.0F) {
				throw new IllegalArgumentException("Face brightness must be between zero and one.");
			}
		}
	}

	public record Vertex(float x, float y, float z, float u, float v) {
		public Vertex {
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
				|| !Float.isFinite(u) || !Float.isFinite(v)) {
				throw new IllegalArgumentException("Preview vertex values must be finite.");
			}
		}
	}

	public record Texture(
		ResourcePath path,
		byte[] pngBytes,
		SourceLayer sourceLayer,
		boolean missing
	) {
		public Texture {
			path = Objects.requireNonNull(path, "path");
			pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
			sourceLayer = Objects.requireNonNull(sourceLayer, "sourceLayer");
			if (missing && pngBytes.length != 0) {
				throw new IllegalArgumentException("Missing textures cannot contain image bytes.");
			}
		}

		@Override
		public byte[] pngBytes() {
			return pngBytes.clone();
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Texture other
				&& path.equals(other.path)
				&& Arrays.equals(pngBytes, other.pngBytes)
				&& sourceLayer == other.sourceLayer
				&& missing == other.missing;
		}

		@Override
		public int hashCode() {
			return Objects.hash(path, Arrays.hashCode(pngBytes), sourceLayer, missing);
		}
	}

	private static String requireText(String value, String name) {
		String result = Objects.requireNonNull(value, name).strip();
		if (result.isEmpty()) {
			throw new IllegalArgumentException(name + " cannot be blank.");
		}
		return result;
	}
}
