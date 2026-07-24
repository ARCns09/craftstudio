package dev.arcn.craftstudio.resource.application;

import dev.arcn.craftstudio.resource.domain.ResourceData;
import dev.arcn.craftstudio.resource.domain.ResourcePath;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

public interface AssetSource {
	Optional<ResourceData> read(ResourcePath path) throws IOException;

	Stream<ResourcePath> list(String namespace, String prefix) throws IOException;

	SourceLayer layer();

	String revision();
}
