package dev.arcn.craftstudio.client;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.client.input.CraftStudioKeyBindings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class CraftStudioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CraftStudioClientContext context = CraftStudioClientContext.create();
		context.initialize();
		CraftStudioKeyBindings.register(context);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> context.close());
		CraftStudio.LOGGER.info("CraftStudio client initialized.");
	}
}
