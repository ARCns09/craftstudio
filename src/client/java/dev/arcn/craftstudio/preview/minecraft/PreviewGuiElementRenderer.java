package dev.arcn.craftstudio.preview.minecraft;

import dev.arcn.craftstudio.preview.domain.PreviewScene.Face;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Vertex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

public final class PreviewGuiElementRenderer
	extends SpecialGuiElementRenderer<PreviewGuiElementRenderState> {

	public PreviewGuiElementRenderer(VertexConsumerProvider.Immediate vertexConsumers) {
		super(vertexConsumers);
	}

	@Override
	public Class<PreviewGuiElementRenderState> getElementClass() {
		return PreviewGuiElementRenderState.class;
	}

	@Override
	protected void render(PreviewGuiElementRenderState state, MatrixStack matrices) {
		MinecraftClient.getInstance()
			.gameRenderer
			.getDiffuseLighting()
			.setShaderLights(DiffuseLighting.Type.ENTITY_IN_UI);
		matrices.translate(
			state.panX() / state.scale(),
			state.panY() / state.scale(),
			0.0F
		);
		matrices.scale(1.0F, -1.0F, 1.0F);
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.pitch()));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.yaw()));
		matrices.translate(-state.centerX(), -state.centerY(), -state.centerZ());

		for (Face face : state.variant().faces()) {
			Identifier texture = state.textures().get(face.textureKey());
			if (texture == null) {
				continue;
			}
			RenderLayer layer = RenderLayers.entityCutoutNoCull(texture);
			VertexConsumer consumer = vertexConsumers.getBuffer(layer);
			float[] normal = faceNormal(face);
			int color = MathHelper.clamp((int) (face.brightness() * 255.0F), 0, 255);
			for (Vertex vertex : face.vertices()) {
				consumer.vertex(matrices.peek(), vertex.x(), vertex.y(), vertex.z())
					.color(color, color, color, 255)
					.texture(vertex.u(), vertex.v())
					.overlay(OverlayTexture.DEFAULT_UV)
					.light(LightmapTextureManager.pack(15, 15))
					.normal(matrices.peek(), normal[0], normal[1], normal[2]);
			}
		}
	}

	@Override
	protected float getYOffset(int renderTargetHeight, int guiScale) {
		return renderTargetHeight / 2.0F;
	}

	@Override
	protected String getName() {
		return "CraftStudio preview";
	}

	private float[] faceNormal(Face face) {
		Vertex a = face.vertices().get(0);
		Vertex b = face.vertices().get(1);
		Vertex c = face.vertices().get(2);
		float abX = b.x() - a.x();
		float abY = b.y() - a.y();
		float abZ = b.z() - a.z();
		float acX = c.x() - a.x();
		float acY = c.y() - a.y();
		float acZ = c.z() - a.z();
		float x = abY * acZ - abZ * acY;
		float y = abZ * acX - abX * acZ;
		float z = abX * acY - abY * acX;
		float length = Math.max(0.0001F, MathHelper.sqrt(x * x + y * y + z * z));
		return new float[] {x / length, y / length, z / length};
	}
}
