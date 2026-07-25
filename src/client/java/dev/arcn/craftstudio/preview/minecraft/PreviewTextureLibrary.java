package dev.arcn.craftstudio.preview.minecraft;

import dev.arcn.craftstudio.preview.domain.PreviewScene.Texture;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

public final class PreviewTextureLibrary implements AutoCloseable {
	private final TextureManager textureManager;
	private final String instancePath;
	private final Map<String, Identifier> identifiers = new LinkedHashMap<>();
	private int sequence;

	public PreviewTextureLibrary(MinecraftClient client) {
		this.textureManager = Objects.requireNonNull(client, "client").getTextureManager();
		this.instancePath = UUID.randomUUID().toString().replace("-", "");
	}

	public Identifier texture(String key, Texture texture) {
		return identifiers.computeIfAbsent(
			Objects.requireNonNull(key, "key"),
			ignored -> register(texture)
		);
	}

	@Override
	public void close() {
		for (Identifier identifier : identifiers.values()) {
			textureManager.destroyTexture(identifier);
		}
		identifiers.clear();
	}

	private Identifier register(Texture texture) {
		Objects.requireNonNull(texture, "texture");
		NativeImage image = texture.missing()
			? createMissingImage()
			: decodeOrMissing(texture.pngBytes());
		Identifier identifier = Identifier.of(
			"craftstudio",
			"preview/" + instancePath + "/" + sequence++
		);
		NativeImageBackedTexture backedTexture = new NativeImageBackedTexture(
			() -> "CraftStudio preview " + texture.path().packPath(),
			image
		);
		textureManager.registerTexture(identifier, backedTexture);
		backedTexture.upload();
		return identifier;
	}

	private NativeImage decodeOrMissing(byte[] bytes) {
		try {
			return NativeImage.read(bytes);
		} catch (IOException | RuntimeException exception) {
			return createMissingImage();
		}
	}

	private NativeImage createMissingImage() {
		NativeImage image = new NativeImage(16, 16, false);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				boolean bright = (x / 4 + y / 4) % 2 == 0;
				image.setColorArgb(x, y, bright ? 0xFFFF00FF : 0xFF1A1A1A);
			}
		}
		return image;
	}
}
