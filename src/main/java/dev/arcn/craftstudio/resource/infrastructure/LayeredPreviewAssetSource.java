package dev.arcn.craftstudio.resource.infrastructure;

import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class LayeredPreviewAssetSource implements AssetSource {
	private final AssetSource projectSource;
	private final AssetSource vanillaSource;

	public LayeredPreviewAssetSource(AssetSource projectSource, AssetSource vanillaSource) {
		this.projectSource = projectSource;
		this.vanillaSource = Objects.requireNonNull(vanillaSource, "vanillaSource");
	}

	@Override
	public Optional<ResourceData> read(ResourcePath path) throws IOException {
		Objects.requireNonNull(path, "path");
		if (projectSource != null) {
			Optional<ResourceData> projectResource = projectSource.read(path);
			if (projectResource.isPresent()) {
				return projectResource;
			}
		}
		return vanillaSource.read(path);
	}

	@Override
	public Stream<ResourcePath> list(String namespace, String prefix) throws IOException {
		LinkedHashSet<ResourcePath> paths = new LinkedHashSet<>();
		if (projectSource != null) {
			try (Stream<ResourcePath> projectPaths = projectSource.list(namespace, prefix)) {
				projectPaths.forEach(paths::add);
			}
		}
		try (Stream<ResourcePath> vanillaPaths = vanillaSource.list(namespace, prefix)) {
			vanillaPaths.forEach(paths::add);
		}
		return paths.stream().sorted();
	}

	@Override
	public SourceLayer layer() {
		return projectSource == null ? vanillaSource.layer() : SourceLayer.PROJECT;
	}

	@Override
	public String revision() {
		return "layered:"
			+ (projectSource == null ? "none" : projectSource.revision())
			+ ">"
			+ vanillaSource.revision();
	}
}
