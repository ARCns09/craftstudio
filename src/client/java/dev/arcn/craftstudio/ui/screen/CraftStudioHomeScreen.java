package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class CraftStudioHomeScreen extends Screen {
	private static final Text TITLE = Text.translatable("screen.craftstudio.home.title");
	private static final int PANEL_MAX_WIDTH = 280;
	private static final int PANEL_HEIGHT = 176;
	private static final int BUTTON_HEIGHT = 20;

	private final Screen parent;

	public CraftStudioHomeScreen(Screen parent) {
		super(TITLE);
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int buttonX = panelX + CraftStudioTheme.SPACE_4;
		int buttonWidth = getPanelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int buttonY = panelY + 68;

		ButtonWidget newProjectButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.new_project"),
			button -> {
			}
		).dimensions(buttonX, buttonY, buttonWidth, BUTTON_HEIGHT).build();
		newProjectButton.active = false;
		addDrawableChild(newProjectButton);

		ButtonWidget openProjectButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.open_project"),
			button -> {
			}
		).dimensions(
			buttonX,
			buttonY + BUTTON_HEIGHT + CraftStudioTheme.SPACE_2,
			buttonWidth,
			BUTTON_HEIGHT
		).build();
		openProjectButton.active = false;
		addDrawableChild(openProjectButton);

		addDrawableChild(
			ButtonWidget.builder(
				Text.translatable("screen.craftstudio.home.close"),
				button -> close()
			).dimensions(
				buttonX,
				buttonY + (BUTTON_HEIGHT + CraftStudioTheme.SPACE_2) * 2,
				buttonWidth,
				BUTTON_HEIGHT
			).build()
		);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int panelWidth = getPanelWidth();

		context.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		context.fill(
			panelX,
			panelY,
			panelX + panelWidth,
			panelY + PANEL_HEIGHT,
			CraftStudioTheme.PANEL
		);
		context.fill(
			panelX,
			panelY,
			panelX + panelWidth,
			panelY + CraftStudioTheme.SPACE_1,
			CraftStudioTheme.ACCENT
		);
		context.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			panelY + CraftStudioTheme.SPACE_4,
			CraftStudioTheme.TEXT_PRIMARY
		);
		context.drawCenteredTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.home.subtitle"),
			width / 2,
			panelY + CraftStudioTheme.SPACE_4 + 22,
			CraftStudioTheme.TEXT_MUTED
		);

		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private int getPanelWidth() {
		return Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2);
	}

	private int getPanelX() {
		return (width - getPanelWidth()) / 2;
	}

	private int getPanelY() {
		return Math.max(CraftStudioTheme.SPACE_4, (height - PANEL_HEIGHT) / 2);
	}
}
