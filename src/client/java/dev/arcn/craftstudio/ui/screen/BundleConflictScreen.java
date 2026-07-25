package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.ui.widget.ScrollableActionListWidget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class BundleConflictScreen extends Screen {
	private final CraftStudioClientContext context;
	private final AssetDetailsScreen parent;
	private final CopyPlan plan;
	private final Set<String> replacements = new LinkedHashSet<>();
	private CopyPlan.Entry focused;
	private double scrollY;
	private Text error;
	private boolean busy;
	private ScrollableActionListWidget<CopyPlan.Entry> conflictList;

	BundleConflictScreen(
		CraftStudioClientContext context,
		AssetDetailsScreen parent,
		CopyPlan plan
	) {
		super(Text.translatable("screen.craftstudio.bundle.conflicts_title"));
		this.context = context;
		this.parent = parent;
		this.plan = plan;
	}

	@Override
	protected void init() {
		if (conflictList != null) {
			scrollY = conflictList.getScrollY();
		}
		int margin = width < 340 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4;
		int contentWidth = width - margin * 2;
		int firstButtonY = height - margin - 44;
		List<ScrollableActionListWidget.Row<CopyPlan.Entry>> rows = plan.conflicts().stream()
			.map(entry -> new ScrollableActionListWidget.Row<>(
				entry,
				Text.literal(conflictLabel(entry)),
				!busy
			))
			.toList();
		conflictList = new ScrollableActionListWidget<>(
			margin,
			50,
			contentWidth,
			Math.max(1, firstButtonY - 58),
			24,
			title,
			textRenderer,
			rows,
			this::toggle
		);
		conflictList.setScrollY(scrollY);
		addDrawableChild(conflictList);

		int gap = CraftStudioTheme.SPACE_2;
		int buttonWidth = (contentWidth - gap) / 2;
		ButtonWidget apply = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.bundle.apply_choices"),
			button -> parent.applyPlan(plan, replacements)
		).dimensions(margin, firstButtonY, buttonWidth, 20).build();
		apply.active = !busy;
		addDrawableChild(apply);
		ButtonWidget compare = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.bundle.compare"),
			button -> compareFocused()
		).dimensions(margin + buttonWidth + gap, firstButtonY, buttonWidth, 20).build();
		compare.active = !busy && focused != null;
		addDrawableChild(compare);
		ButtonWidget replaceAll = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.bundle.replace_all"),
			button -> {
				plan.conflicts().stream()
					.map(entry -> entry.path().packPath())
					.forEach(replacements::add);
				clearAndInit();
			}
		).dimensions(margin, firstButtonY + 24, buttonWidth, 20).build();
		replaceAll.active = !busy;
		addDrawableChild(replaceAll);
		ButtonWidget cancel = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.common.cancel"),
			button -> close()
		).dimensions(margin + buttonWidth + gap, firstButtonY + 24, buttonWidth, 20).build();
		cancel.active = !busy;
		addDrawableChild(cancel);
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
		Text status = error == null
			? Text.translatable(
				"screen.craftstudio.bundle.conflicts_summary",
				plan.conflicts().size(),
				replacements.size()
			)
			: error;
		context.drawCenteredTextWithShadow(
			textRenderer,
			status,
			width / 2,
			35,
			error == null ? CraftStudioTheme.WARNING : CraftStudioTheme.ERROR
		);
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!busy && client != null) {
			client.setScreen(parent);
		}
	}

	private String conflictLabel(CopyPlan.Entry entry) {
		String choice = replacements.contains(entry.path().packPath()) ? "[REPLACE] " : "[KEEP] ";
		return choice + entry.path().packPath();
	}

	private void toggle(CopyPlan.Entry entry) {
		focused = entry;
		String path = entry.path().packPath();
		if (!replacements.remove(path)) {
			replacements.add(path);
		}
		clearAndInit();
	}

	private void compareFocused() {
		if (focused == null) {
			return;
		}
		busy = true;
		error = null;
		clearAndInit();
		context.compareWithVanilla(focused.path()).whenCompleteAsync((comparison, failure) -> {
			busy = false;
			if (failure == null) {
				client.setScreen(new BundleComparisonScreen(this, comparison));
			} else {
				error = Text.literal(CraftStudioClientContext.userMessage(failure));
				clearAndInit();
			}
		}, client);
	}
}
