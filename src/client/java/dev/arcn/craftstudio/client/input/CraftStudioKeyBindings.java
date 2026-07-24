package dev.arcn.craftstudio.client.input;

import dev.arcn.craftstudio.CraftStudio;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.ui.screen.CraftStudioHomeScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

public final class CraftStudioKeyBindings {
	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
		Identifier.of(CraftStudio.MOD_ID, "general")
	);

	private static final KeyBinding OPEN_CRAFTSTUDIO = KeyBindingHelper.registerKeyBinding(
		new KeyBinding(
			"key.craftstudio.open",
			InputUtil.Type.KEYSYM,
			InputUtil.GLFW_KEY_F8,
			CATEGORY
		)
	);

	private CraftStudioKeyBindings() {
	}

	public static void register(CraftStudioClientContext context) {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_CRAFTSTUDIO.wasPressed()) {
				if (!(client.currentScreen instanceof CraftStudioHomeScreen)) {
					client.setScreen(new CraftStudioHomeScreen(context, client.currentScreen));
				}
			}
		});
	}
}
