package dev.arcn.craftstudio.preview.ui;

import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.preview.minecraft.PreviewGuiElementRenderState;
import dev.arcn.craftstudio.preview.minecraft.PreviewTextureLibrary;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public final class PreviewViewportWidget extends ClickableWidget {
	private final MinecraftClient client;
	private final PreviewTextureLibrary textureLibrary;
	private Variant variant;
	private float yaw = -35.0F;
	private float pitch = 25.0F;
	private float zoom = 1.0F;
	private float panX;
	private float panY;

	public PreviewViewportWidget(
		int x,
		int y,
		int width,
		int height,
		MinecraftClient client,
		PreviewTextureLibrary textureLibrary,
		Variant variant
	) {
		super(x, y, width, height, Text.translatable("screen.craftstudio.preview.viewport"));
		this.client = Objects.requireNonNull(client, "client");
		this.textureLibrary = Objects.requireNonNull(textureLibrary, "textureLibrary");
		this.variant = Objects.requireNonNull(variant, "variant");
	}

	public void resetCamera() {
		yaw = -35.0F;
		pitch = 25.0F;
		zoom = 1.0F;
		panX = 0.0F;
		panY = 0.0F;
	}

	@Override
	protected void renderWidget(
		DrawContext context,
		int mouseX,
		int mouseY,
		float deltaTicks
	) {
		context.fillGradient(
			getX(),
			getY(),
			getRight(),
			getBottom(),
			0xFF252B35,
			0xFF11151B
		);
		context.drawStrokedRectangle(
			getX(),
			getY(),
			getWidth(),
			getHeight(),
			isFocused() ? CraftStudioTheme.ACCENT : 0xFF3A424F
		);
		if (variant.faces().isEmpty()) {
			context.drawCenteredTextWithShadow(
				client.textRenderer,
				Text.translatable("screen.craftstudio.preview.unavailable"),
				getX() + getWidth() / 2,
				getY() + getHeight() / 2 - client.textRenderer.fontHeight / 2,
				CraftStudioTheme.WARNING
			);
			return;
		}

		Map<String, Identifier> textures = new LinkedHashMap<>();
		for (Map.Entry<String, dev.arcn.craftstudio.preview.domain.PreviewScene.Texture> texture
			: variant.textures().entrySet()) {
			textures.put(
				texture.getKey(),
				textureLibrary.texture(texture.getKey(), texture.getValue())
			);
		}
		float scale = Math.max(1.0F, Math.min(getWidth(), getHeight()) / 24.0F) * zoom;
		context.state.addSpecialElement(new PreviewGuiElementRenderState(
			variant,
			textures,
			yaw,
			pitch,
			panX,
			panY,
			getX(),
			getY(),
			getRight(),
			getBottom(),
			scale,
			new net.minecraft.client.gui.ScreenRect(
				getX(),
				getY(),
				getWidth(),
				getHeight()
			)
		));
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		setFocused(true);
		if (doubled) {
			resetCamera();
		}
	}

	@Override
	protected boolean isValidClickButton(MouseInput input) {
		return input.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
			|| input.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
	}

	@Override
	protected void onDrag(Click click, double deltaX, double deltaY) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			yaw += (float) deltaX * 0.8F;
			pitch = MathHelper.clamp(pitch + (float) deltaY * 0.8F, -89.0F, 89.0F);
		} else if (click.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			panX += (float) deltaX;
			panY += (float) deltaY;
		}
	}

	@Override
	public boolean mouseScrolled(
		double mouseX,
		double mouseY,
		double horizontalAmount,
		double verticalAmount
	) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		zoom = MathHelper.clamp(
			zoom * (verticalAmount > 0 ? 1.12F : 0.89F),
			0.35F,
			3.0F
		);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		switch (input.key()) {
			case GLFW.GLFW_KEY_LEFT -> yaw -= 5.0F;
			case GLFW.GLFW_KEY_RIGHT -> yaw += 5.0F;
			case GLFW.GLFW_KEY_UP -> pitch = Math.max(-89.0F, pitch - 5.0F);
			case GLFW.GLFW_KEY_DOWN -> pitch = Math.min(89.0F, pitch + 5.0F);
			case GLFW.GLFW_KEY_R -> resetCamera();
			default -> {
				return super.keyPressed(input);
			}
		}
		return true;
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}

}
