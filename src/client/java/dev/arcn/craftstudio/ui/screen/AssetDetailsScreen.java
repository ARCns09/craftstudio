package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class AssetDetailsScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 420;
	private static final int PANEL_HEIGHT = 214;

	private final Screen parent;
	private final CatalogAsset asset;

	public AssetDetailsScreen(Screen parent, CatalogAsset asset) {
		super(Text.translatable("screen.craftstudio.asset_details.title"));
		this.parent = parent;
		this.asset = asset;
	}

	@Override
	protected void init() {
		int panelX = getPanelX();
		int panelY = getPanelY();
		addDrawableChild(
			ButtonWidget.builder(
				Text.translatable("screen.craftstudio.catalog.back"),
				button -> close()
			).dimensions(
				panelX + CraftStudioTheme.SPACE_4,
				panelY + PANEL_HEIGHT - 32,
				getPanelWidth() - CraftStudioTheme.SPACE_4 * 2,
				20
			).build()
		);
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int textX = panelX + CraftStudioTheme.SPACE_4;

		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(
			panelX,
			panelY,
			panelX + getPanelWidth(),
			panelY + PANEL_HEIGHT,
			CraftStudioTheme.PANEL
		);
		drawContext.fill(
			panelX,
			panelY,
			panelX + getPanelWidth(),
			panelY + CraftStudioTheme.SPACE_1,
			CraftStudioTheme.ACCENT
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			Text.literal(asset.displayName()),
			width / 2,
			panelY + CraftStudioTheme.SPACE_4,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawLine(drawContext, "screen.craftstudio.asset_details.id", asset.identifier(), textX, panelY + 42);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.type",
			Text.translatable(
				asset.kind() == AssetKind.BLOCK
					? "screen.craftstudio.catalog.type.block"
					: "screen.craftstudio.catalog.type.item"
			).getString(),
			textX,
			panelY + 58
		);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.namespace",
			asset.namespace(),
			textX,
			panelY + 74
		);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.translation_key",
			asset.translationKey(),
			textX,
			panelY + 90
		);
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.asset_details.placeholder"),
			textX,
			panelY + 118,
			CraftStudioTheme.INFORMATION
		);
		drawContext.drawWrappedTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.asset_details.placeholder_detail"),
			textX,
			panelY + 136,
			getPanelWidth() - CraftStudioTheme.SPACE_4 * 2,
			CraftStudioTheme.TEXT_MUTED
		);

		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private void drawLine(
		DrawContext drawContext,
		String labelKey,
		String value,
		int x,
		int y
	) {
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable(labelKey, value),
			x,
			y,
			CraftStudioTheme.TEXT_MUTED
		);
	}

	private int getPanelWidth() {
		return Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2);
	}

	private int getPanelX() {
		return (width - getPanelWidth()) / 2;
	}

	private int getPanelY() {
		return (height - PANEL_HEIGHT) / 2;
	}
}
