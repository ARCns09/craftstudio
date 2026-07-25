package dev.arcn.craftstudio.preview.ui;

import dev.arcn.craftstudio.preview.domain.PreviewMode;
import dev.arcn.craftstudio.preview.domain.PreviewScene.DisplayTransform;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Face;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Vertex;
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
	private static final float CAMERA_PADDING = 1.15F;

	private final MinecraftClient client;
	private final PreviewTextureLibrary textureLibrary;
	private Variant variant;
	private float yaw;
	private float pitch;
	private float zoom;
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
		resetCamera();
	}

	public void resetCamera() {
		yaw = variant.mode() == PreviewMode.ITEM
			? 0.0F
			: 225.0F;
		pitch = variant.mode() == PreviewMode.ITEM
			? 0.0F
			: 30.0F;
		zoom = 1.0F;
		panX = 0.0F;
		panY = 0.0F;
	}

	public void setVariant(Variant variant) {
		this.variant = Objects.requireNonNull(variant, "variant");
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
		CameraFrame frame = CameraFrame.forVariant(variant);
		float scale = Math.max(
			1.0F,
			Math.min(getWidth(), getHeight()) / (frame.diagonal() * CAMERA_PADDING)
		) * inventoryFitMultiplier() * zoom;
		context.state.addSpecialElement(new PreviewGuiElementRenderState(
			variant,
			textures,
			yaw,
			pitch,
			panX,
			panY,
			frame.centerX(),
			frame.centerY(),
			frame.centerZ(),
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
			yaw -= (float) deltaX * 0.8F;
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

	private float inventoryFitMultiplier() {
		if (variant.mode() != PreviewMode.ITEM) {
			return 1.0F;
		}
		DisplayTransform transform = variant.displayTransform();
		float largestScale = Math.max(
			Math.abs(transform.scaleX()),
			Math.max(Math.abs(transform.scaleY()), Math.abs(transform.scaleZ()))
		);
		return largestScale > 0.001F ? 1.0F / largestScale : 1.0F;
	}

	private record CameraFrame(float centerX, float centerY, float centerZ, float diagonal) {
		private static CameraFrame forVariant(Variant variant) {
			float minX = Float.POSITIVE_INFINITY;
			float minY = Float.POSITIVE_INFINITY;
			float minZ = Float.POSITIVE_INFINITY;
			float maxX = Float.NEGATIVE_INFINITY;
			float maxY = Float.NEGATIVE_INFINITY;
			float maxZ = Float.NEGATIVE_INFINITY;

			for (Face face : variant.faces()) {
				for (Vertex vertex : face.vertices()) {
					minX = Math.min(minX, vertex.x());
					minY = Math.min(minY, vertex.y());
					minZ = Math.min(minZ, vertex.z());
					maxX = Math.max(maxX, vertex.x());
					maxY = Math.max(maxY, vertex.y());
					maxZ = Math.max(maxZ, vertex.z());
				}
			}

			if (!Float.isFinite(minX)) {
				return new CameraFrame(8.0F, 8.0F, 8.0F, 16.0F);
			}
			float width = maxX - minX;
			float height = maxY - minY;
			float depth = maxZ - minZ;
			float diagonal = Math.max(1.0F, MathHelper.sqrt(
				width * width + height * height + depth * depth
			));
			return new CameraFrame(
				(minX + maxX) / 2.0F,
				(minY + maxY) / 2.0F,
				(minZ + maxZ) / 2.0F,
				diagonal
			);
		}
	}

}
