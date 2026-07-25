package dev.arcn.craftstudio.project.infrastructure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import dev.arcn.craftstudio.platform.filesystem.AtomicFileWriter;
import dev.arcn.craftstudio.version.TargetVersionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
			new PackSection(
				target.resourcePackFormat(),
				target.resourcePackFormat(),
				target.resourcePackFormat(),
				Objects.requireNonNull(description, "description")
			)
		);
		atomicFileWriter.writeUtf8(
			packRoot.resolve("pack.mcmeta"),
			gson.toJson(metadata) + System.lineSeparator()
		);
	}

	public boolean addRequiredFormatRangeToLegacyMetadata(
		Path packRoot,
		TargetVersionManifest target
	) throws IOException {
		Path metadataPath = Objects.requireNonNull(packRoot, "packRoot")
			.toAbsolutePath()
			.normalize()
			.resolve("pack.mcmeta");
		JsonObject root;
		try {
			root = JsonParser.parseString(
				Files.readString(metadataPath, StandardCharsets.UTF_8)
			).getAsJsonObject();
		} catch (RuntimeException exception) {
			return false;
		}
		if (!root.has("pack") || !root.get("pack").isJsonObject()) {
			return false;
		}
		JsonObject pack = root.getAsJsonObject("pack");
		if (!hasTargetLegacyFormat(pack, target.resourcePackFormat())) {
			return false;
		}
		boolean changed = false;
		if (!pack.has("min_format")) {
			pack.addProperty("min_format", target.resourcePackFormat());
			changed = true;
		}
		if (!pack.has("max_format")) {
			pack.addProperty("max_format", target.resourcePackFormat());
			changed = true;
		}
		if (changed) {
			atomicFileWriter.writeUtf8(
				metadataPath,
				gson.toJson(root) + System.lineSeparator()
			);
		}
		return changed;
	}

	private boolean hasTargetLegacyFormat(JsonObject pack, int targetFormat) {
		if (!pack.has("pack_format")
			|| !pack.get("pack_format").isJsonPrimitive()
			|| !pack.getAsJsonPrimitive("pack_format").isNumber()) {
			return false;
		}
		try {
			return pack.get("pack_format").getAsBigDecimal().intValueExact() == targetFormat;
		} catch (ArithmeticException | NumberFormatException exception) {
			return false;
		}
	}

	private record PackMetadata(PackSection pack) {
	}

	private record PackSection(
		@SerializedName("pack_format") int packFormat,
		@SerializedName("min_format") int minimumFormat,
		@SerializedName("max_format") int maximumFormat,
		String description
	) {
	}
}
