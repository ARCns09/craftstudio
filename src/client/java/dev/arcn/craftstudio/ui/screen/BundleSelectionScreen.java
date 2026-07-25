package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.SelectionMode;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.ui.widget.ScrollableActionListWidget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class BundleSelectionScreen extends Screen {
	private final CraftStudioClientContext context;
	private final AssetDetailsScreen parent;
	private final AssetResolutionResult resolution;
	private final CopyPlan initialPlan;
	private final Set<String> selectedPaths = new LinkedHashSet<>();
	private double scrollY;
	private boolean busy;
	private Text error;
	private ScrollableActionListWidget<CopyPlan.Entry> fileList;

	BundleSelectionScreen(
		CraftStudioClientContext context,
		AssetDetailsScreen parent,
		AssetResolutionResult resolution,
		CopyPlan initialPlan
	) {
		super(Text.translatable("screen.craftstudio.bundle.custom_title"));
		this.context = context;
		this.parent = parent;
		this.resolution = resolution;
		this.initialPlan = initialPlan;
		initialPlan.selectedEntries().stream()
			.map(entry -> entry.path().packPath())
			.forEach(selectedPaths::add);
	}

	@Override
	protected void init() {
		if (fileList != null) {
			scrollY = fileList.getScrollY();
		}
		int margin = width < 340 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4;
		int contentWidth = width - margin * 2;
		int buttonY = height - margin - 20;
		List<ScrollableActionListWidget.Row<CopyPlan.Entry>> rows = initialPlan.entries().stream()
			.map(entry -> new ScrollableActionListWidget.Row<>(
				entry,
				Text.literal(selectionLabel(entry)),
				!busy && !entry.required()
			))
			.toList();
		fileList = new ScrollableActionListWidget<>(
			margin,
			50,
			contentWidth,
			Math.max(1, buttonY - 58),
			24,
			title,
			textRenderer,
			rows,
			this::toggle
		);
		fileList.setScrollY(scrollY);
		addDrawableChild(fileList);
		int gap = CraftStudioTheme.SPACE_2;
		int buttonWidth = (contentWidth - gap) / 2;
		ButtonWidget continueButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.bundle.continue"),
			button -> continueWithSelection()
		).dimensions(margin, buttonY, buttonWidth, 20).build();
		continueButton.active = !busy;
		addDrawableChild(continueButton);
		ButtonWidget cancelButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.common.cancel"),
			button -> close()
		).dimensions(margin + buttonWidth + gap, buttonY, buttonWidth, 20).build();
		cancelButton.active = !busy;
		addDrawableChild(cancelButton);
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
		Text summary = error != null
			? error
			: Text.translatable(
				"screen.craftstudio.bundle.custom_summary",
				selectedPaths.size(),
				initialPlan.entries().size()
			);
		context.drawCenteredTextWithShadow(
			textRenderer,
			summary,
			width / 2,
			35,
			error == null ? CraftStudioTheme.TEXT_MUTED : CraftStudioTheme.ERROR
		);
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!busy && client != null) {
			client.setScreen(parent);
		}
	}

	private String selectionLabel(CopyPlan.Entry entry) {
		String mark = selectedPaths.contains(entry.path().packPath()) ? "[x] " : "[ ] ";
		String suffix = entry.required() ? "  (required)" : "  (" + entry.classification() + ")";
		return mark + entry.path().packPath() + suffix;
	}

	private void toggle(CopyPlan.Entry entry) {
		String path = entry.path().packPath();
		if (!selectedPaths.remove(path)) {
			selectedPaths.add(path);
		}
		clearAndInit();
	}

	private void continueWithSelection() {
		busy = true;
		error = null;
		clearAndInit();
		context.createCopyPlan(resolution, SelectionMode.CUSTOM, selectedPaths)
			.whenCompleteAsync((plan, failure) -> {
				busy = false;
				if (failure == null) {
					client.setScreen(parent);
					parent.reviewPlan(plan);
				} else {
					error = Text.literal(CraftStudioClientContext.userMessage(failure));
					clearAndInit();
				}
			}, client);
	}
}
