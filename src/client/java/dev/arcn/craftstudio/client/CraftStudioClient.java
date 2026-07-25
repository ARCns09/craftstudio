package dev.arcn.craftstudio.client;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.client.input.CraftStudioKeyBindings;
import dev.arcn.craftstudio.preview.minecraft.PreviewGuiElementRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;

public class CraftStudioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CraftStudioClientContext context = CraftStudioClientContext.create();
		SpecialGuiElementRegistry.register(
			renderContext -> new PreviewGuiElementRenderer(renderContext.vertexConsumers())
		);
		context.initialize();
		CraftStudioKeyBindings.register(context);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> context.verifyVanillaSource());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> context.close());
		CraftStudio.LOGGER.info("CraftStudio client initialized.");
	}
}
