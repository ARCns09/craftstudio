package dev.arcn.craftstudio;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftStudio implements ModInitializer {
	public static final String MOD_ID = "craftstudio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("CraftStudio initialized.");
	}
}
