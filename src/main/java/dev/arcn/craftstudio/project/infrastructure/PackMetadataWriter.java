package dev.arcn.craftstudio.project.infrastructure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class PackMetadataWriter {
	private final AtomicFileWriter atomicFileWriter;
	private final Gson gson;

	public PackMetadataWriter(AtomicFileWriter atomicFileWriter) {
		this.atomicFileWriter = Objects.requireNonNull(atomicFileWriter, "atomicFileWriter");
		this.gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	}

	public void write(Path packRoot, TargetVersionManifest target, String description) throws IOException {
		PackMetadata metadata = new PackMetadata(
			new PackSection(target.resourcePackFormat(), Objects.requireNonNull(description, "description"))
		);
		atomicFileWriter.writeUtf8(
			packRoot.resolve("pack.mcmeta"),
			gson.toJson(metadata) + System.lineSeparator()
		);
	}

	private record PackMetadata(PackSection pack) {
	}

	private record PackSection(
		@SerializedName("pack_format") int packFormat,
		String description
	) {
	}
}
