package dev.arcn.craftstudio.version;

import java.util.List;

public record TargetVersionManifest(
	String minecraftVersion,
	int resourcePackFormat,
	String versionDisplayName,
	String assetsDirectory,
	List<String> supportedDefinitionDirectories,
	List<String> atlasRules,
	List<String> compatibilityNotes
) {
	private static final TargetVersionManifest MINECRAFT_1_21_11 = new TargetVersionManifest(
		"1.21.11",
		75,
		"Minecraft: Java Edition 1.21.11",
		"assets",
		List.of("blockstates", "items", "models", "textures", "atlases"),
		List.of(
			"Block model textures use the blocks atlas.",
			"Item model textures must resolve through one valid atlas."
		),
		List.of("CraftStudio 1.x targets Minecraft 1.21.11 only.")
	);

	public TargetVersionManifest {
		supportedDefinitionDirectories = List.copyOf(supportedDefinitionDirectories);
		atlasRules = List.copyOf(atlasRules);
		compatibilityNotes = List.copyOf(compatibilityNotes);
	}

	public static TargetVersionManifest minecraft_1_21_11() {
		return MINECRAFT_1_21_11;
	}
}
