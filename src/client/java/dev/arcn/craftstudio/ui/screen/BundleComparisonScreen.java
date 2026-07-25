package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.project.domain.BundleFileComparison;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class BundleComparisonScreen extends Screen {
	private final Screen parent;
	private final BundleFileComparison comparison;

	BundleComparisonScreen(Screen parent, BundleFileComparison comparison) {
		super(Text.translatable("screen.craftstudio.bundle.compare_title"));
		this.parent = parent;
		this.comparison = comparison;
	}

	@Override
	protected void init() {
		int buttonWidth = Math.min(220, width - CraftStudioTheme.SPACE_4 * 2);
		addDrawableChild(ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.back"),
			button -> close()
		).dimensions((width - buttonWidth) / 2, height - 36, buttonWidth, 20).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		context.fill(0, 0, width, 28, CraftStudioTheme.PANEL);
		context.fill(0, 26, width, 28, CraftStudioTheme.ACCENT);
		context.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			CraftStudioTheme.SPACE_2,
			CraftStudioTheme.TEXT_PRIMARY
		);
		List<String> lines = List.of(
			comparison.path().packPath(),
			"Project: " + comparison.projectSize() + " bytes",
			"SHA-256: " + comparison.projectSha256(),
			"Vanilla: " + comparison.vanillaSize() + " bytes",
			"SHA-256: " + comparison.vanillaSha256(),
			comparison.identical() ? "Files are identical." : "Files differ."
		);
		int y = 44;
		for (String line : lines) {
			String visible = textRenderer.trimToWidth(line, width - 32);
			context.drawTextWithShadow(
				textRenderer,
				Text.literal(visible),
				16,
				y,
				line.endsWith("differ.") ? CraftStudioTheme.WARNING : CraftStudioTheme.TEXT_MUTED
			);
			y += 18;
		}
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
