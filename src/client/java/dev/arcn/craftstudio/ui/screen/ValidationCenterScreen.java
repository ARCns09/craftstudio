package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.ui.widget.DependencyTreeWidget;
import dev.arcn.craftstudio.validation.domain.ValidationIssue;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import dev.arcn.craftstudio.validation.domain.ValidationSeverity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class ValidationCenterScreen extends Screen {
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;
	private ValidationReport report;
	private CraftStudioClientContext.BackgroundTask<ValidationReport> task;
	private Text failure;
	private double scrollY;
	private DependencyTreeWidget issueList;

	public ValidationCenterScreen(CraftStudioClientContext context, Screen parent) {
		super(Text.translatable("screen.craftstudio.validation.title"));
		this.context = context;
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (issueList != null) {
			scrollY = issueList.getScrollY();
		}
		int margin = width < 340 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4;
		int contentWidth = width - margin * 2;
		int footerY = height - margin - BUTTON_HEIGHT;
		issueList = new DependencyTreeWidget(
			margin,
			48,
			contentWidth,
			Math.max(1, footerY - 72),
			title,
			textRenderer,
			rows()
		);
		issueList.setScrollY(scrollY);
		addDrawableChild(issueList);

		int gap = CraftStudioTheme.SPACE_2;
		int thirdWidth = (contentWidth - gap * 2) / 3;
		ButtonWidget validate = ButtonWidget.builder(
			Text.translatable(
				task == null
					? "screen.craftstudio.validation.run"
					: "screen.craftstudio.validation.cancel"
			),
			button -> {
				if (task == null) {
					startValidation();
				} else {
					task.cancel();
				}
			}
		).dimensions(margin, footerY, thirdWidth, BUTTON_HEIGHT).build();
		addDrawableChild(validate);
		ButtonWidget export = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.validation.export"),
			button -> client.setScreen(new ExportScreen(context, this, report))
		).dimensions(
			margin + thirdWidth + gap,
			footerY,
			thirdWidth,
			BUTTON_HEIGHT
		).build();
		export.active = task == null && report != null;
		addDrawableChild(export);
		ButtonWidget back = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.back"),
			button -> close()
		).dimensions(
			margin + (thirdWidth + gap) * 2,
			footerY,
			contentWidth - thirdWidth * 2 - gap * 2,
			BUTTON_HEIGHT
		).build();
		back.active = task == null;
		addDrawableChild(back);

		if (report == null && task == null && failure == null) {
			startValidation();
		}
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(0, 0, width, 28, CraftStudioTheme.PANEL);
		drawContext.fill(0, 26, width, 28, CraftStudioTheme.ACCENT);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			CraftStudioTheme.SPACE_2,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			summary(),
			width / 2,
			34,
			summaryColor()
		);
		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (task != null) {
			task.cancel();
			return;
		}
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private void startValidation() {
		if (task != null) {
			return;
		}
		report = null;
		failure = null;
		task = context.validateActiveProject();
		CraftStudioClientContext.BackgroundTask<ValidationReport> runningTask = task;
		clearAndInit();
		runningTask.future().whenCompleteAsync((result, error) -> {
			if (task != runningTask) {
				return;
			}
			task = null;
			if (error == null) {
				report = result;
			} else {
				failure = Text.literal(CraftStudioClientContext.userMessage(error));
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private List<DependencyTreeWidget.Row> rows() {
		if (task != null) {
			return List.of(DependencyTreeWidget.Row.notice(
				Text.translatable("screen.craftstudio.validation.running"),
				CraftStudioTheme.INFORMATION
			));
		}
		if (failure != null) {
			return List.of(DependencyTreeWidget.Row.notice(failure, CraftStudioTheme.ERROR));
		}
		if (report == null) {
			return List.of();
		}
		List<DependencyTreeWidget.Row> rows = new ArrayList<>();
		for (ValidationSeverity severity : ValidationSeverity.values()) {
			List<ValidationIssue> issues = report.issues().stream()
				.filter(issue -> issue.severity() == severity)
				.toList();
			if (issues.isEmpty()) {
				continue;
			}
			rows.add(DependencyTreeWidget.Row.section(
				Text.literal(friendlySeverity(severity)),
				issues.size()
			));
			for (ValidationIssue issue : issues) {
				String reason = issue.suggestedRepair().isBlank()
					? issue.summary()
					: issue.summary() + " · " + issue.suggestedRepair();
				if (!issue.jsonPath().isBlank()) {
					reason += " · " + issue.jsonPath();
				}
				if (!issue.dependencyChain().isEmpty()) {
					reason += " · " + String.join(" → ", issue.dependencyChain());
				}
				rows.add(DependencyTreeWidget.Row.dependency(
					Text.literal(issue.summary()),
					Text.literal(severity.name()),
					issue.packPath().isBlank() ? "Project-wide check" : issue.packPath(),
					Text.literal(reason),
					Text.literal(issue.code()),
					severityColor(severity)
				));
			}
		}
		return List.copyOf(rows);
	}

	private Text summary() {
		if (task != null) {
			return Text.translatable("screen.craftstudio.validation.running");
		}
		if (failure != null) {
			return failure;
		}
		if (report == null) {
			return Text.empty();
		}
		return Text.translatable(
			"screen.craftstudio.validation.summary",
			report.fileCount(),
			report.count(ValidationSeverity.ERROR),
			report.count(ValidationSeverity.WARNING),
			report.count(ValidationSeverity.PASSED)
		);
	}

	private int summaryColor() {
		if (failure != null) {
			return CraftStudioTheme.ERROR;
		}
		if (task != null || report == null) {
			return CraftStudioTheme.INFORMATION;
		}
		return report.canExport() ? CraftStudioTheme.SUCCESS : CraftStudioTheme.ERROR;
	}

	private String friendlySeverity(ValidationSeverity severity) {
		return switch (severity) {
			case ERROR -> "Errors";
			case WARNING -> "Warnings";
			case INFORMATION -> "Information";
			case PASSED -> "Passed checks";
		};
	}

	private int severityColor(ValidationSeverity severity) {
		return switch (severity) {
			case ERROR -> CraftStudioTheme.ERROR;
			case WARNING -> CraftStudioTheme.WARNING;
			case INFORMATION -> CraftStudioTheme.INFORMATION;
			case PASSED -> CraftStudioTheme.SUCCESS;
		};
	}
}
