package dev.arcn.craftstudio.preview.minecraft;

import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.util.Identifier;

public record PreviewGuiElementRenderState(
	Variant variant,
	Map<String, Identifier> textures,
	float yaw,
	float pitch,
	float panX,
	float panY,
	float centerX,
	float centerY,
	float centerZ,
	int x1,
	int y1,
	int x2,
	int y2,
	float scale,
	ScreenRect scissorArea,
	ScreenRect bounds
) implements SpecialGuiElementRenderState {
	public PreviewGuiElementRenderState(
		Variant variant,
		Map<String, Identifier> textures,
		float yaw,
		float pitch,
		float panX,
		float panY,
		float centerX,
		float centerY,
		float centerZ,
		int x1,
		int y1,
		int x2,
		int y2,
		float scale,
		ScreenRect scissorArea
	) {
		this(
			variant,
			textures,
			yaw,
			pitch,
			panX,
			panY,
			centerX,
			centerY,
			centerZ,
			x1,
			y1,
			x2,
			y2,
			scale,
			scissorArea,
			SpecialGuiElementRenderState.createBounds(x1, y1, x2, y2, scissorArea)
		);
	}

	public PreviewGuiElementRenderState {
		variant = Objects.requireNonNull(variant, "variant");
		textures = Map.copyOf(Objects.requireNonNull(textures, "textures"));
		bounds = Objects.requireNonNull(bounds, "bounds");
		if (!Float.isFinite(scale) || scale <= 0.0F) {
			throw new IllegalArgumentException("Preview scale must be positive and finite.");
		}
		if (!Float.isFinite(centerX)
			|| !Float.isFinite(centerY)
			|| !Float.isFinite(centerZ)) {
			throw new IllegalArgumentException("Preview center must be finite.");
		}
	}
}
