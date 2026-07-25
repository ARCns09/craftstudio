package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.ui.widget.ScrollableActionListWidget;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class BundleConfirmationScreen extends Screen {
	private final Screen parent;
	private final Text explanation;
	private final List<String> details;
	private final Runnable confirmation;

	BundleConfirmationScreen(
		Screen parent,
		Text title,
		Text explanation,
		List<String> details,
		Runnable confirmation
	) {
		super(title);
		this.parent = Objects.requireNonNull(parent, "parent");
		this.explanation = Objects.requireNonNull(explanation, "explanation");
		this.details = List.copyOf(Objects.requireNonNull(details, "details"));
		this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
	}

	@Override
	protected void init() {
		int margin = width < 340 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4;
		int contentWidth = width - margin * 2;
		int buttonY = height - margin - 20;
		List<ScrollableActionListWidget.Row<String>> rows = details.stream()
			.map(line -> new ScrollableActionListWidget.Row<>(line, Text.literal(line), false))
			.toList();
		addDrawableChild(new ScrollableActionListWidget<>(
			margin,
			62,
			contentWidth,
			Math.max(1, buttonY - 70),
			22,
			title,
			textRenderer,
			rows,
			ignored -> {
			}
		));
		int gap = CraftStudioTheme.SPACE_2;
		int buttonWidth = (contentWidth - gap) / 2;
		addDrawableChild(ButtonWidget.builder(
			Text.translatable("screen.craftstudio.bundle.confirm"),
			button -> {
				client.setScreen(parent);
				confirmation.run();
			}
		).dimensions(margin, buttonY, buttonWidth, 20).build());
		addDrawableChild(ButtonWidget.builder(
			Text.translatable("screen.craftstudio.common.cancel"),
			button -> close()
		).dimensions(margin + buttonWidth + gap, buttonY, buttonWidth, 20).build());
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
		context.drawCenteredTextWithShadow(
			textRenderer,
			explanation,
			width / 2,
			42,
			CraftStudioTheme.WARNING
		);
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}
}
