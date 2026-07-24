package dev.arcn.craftstudio.resource.infrastructure.minecraft;

import dev.arcn.craftstudio.resource.application.AssetSource;
import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

public final class VanillaAssetSource implements AssetSource {
	private static final int MAX_RESOURCE_BYTES = 64 * 1024 * 1024;

	private final ResourcePack vanillaPack;
	private final String revision;

	public VanillaAssetSource(ResourcePack vanillaPack, String minecraftVersion) {
		this.vanillaPack = Objects.requireNonNull(vanillaPack, "vanillaPack");
		String version = Objects.requireNonNull(minecraftVersion, "minecraftVersion").strip();
		if (version.isEmpty()) {
			throw new IllegalArgumentException("minecraftVersion cannot be blank.");
		}
		this.revision = "vanilla:" + version + ":" + vanillaPack.getId();
	}

	@Override
	public Optional<ResourceData> read(ResourcePath path) throws IOException {
		Objects.requireNonNull(path, "path");
		InputSupplier<InputStream> supplier = vanillaPack.open(
			ResourceType.CLIENT_RESOURCES,
			Identifier.of(path.namespace(), path.path())
		);
		if (supplier == null) {
			return Optional.empty();
		}
		try (InputStream input = supplier.get()) {
			byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
			if (bytes.length > MAX_RESOURCE_BYTES) {
				throw new IOException("Resource exceeds the 64 MiB read limit: " + path.packPath());
			}
			return Optional.of(new ResourceData(path, bytes, layer(), revision));
		}
	}

	@Override
	public Stream<ResourcePath> list(String namespace, String prefix) {
		String safeNamespace = ResourcePath.validateNamespace(namespace);
		String safePrefix = ResourcePath.normalizePrefix(prefix);
		TreeSet<ResourcePath> resources = new TreeSet<>();
		vanillaPack.findResources(
			ResourceType.CLIENT_RESOURCES,
			safeNamespace,
			safePrefix,
			(identifier, supplier) -> resources.add(new ResourcePath(
				identifier.getNamespace(),
				identifier.getPath()
			))
		);
		return resources.stream();
	}

	@Override
	public SourceLayer layer() {
		return SourceLayer.VANILLA_BASE;
	}

	@Override
	public String revision() {
		return revision;
	}
}
