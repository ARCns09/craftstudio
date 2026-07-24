package dev.arcn.craftstudio.client;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.client.input.CraftStudioKeyBindings;
import net.fabricmc.api.ClientModInitializer;

public class CraftStudioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CraftStudioKeyBindings.register();
		CraftStudio.LOGGER.info("CraftStudio client initialized.");
	}
}
