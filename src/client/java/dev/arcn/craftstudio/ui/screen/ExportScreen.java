package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.export.domain.ExistingOutputPolicy;
import dev.arcn.craftstudio.export.domain.ExportRequest;
import dev.arcn.craftstudio.export.domain.ExportResult;
import dev.arcn.craftstudio.export.domain.ExportType;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.validation.domain.ValidationReport;
import dev.arcn.craftstudio.validation.domain.ValidationSeverity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class ExportScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 540;
	private static final int PANEL_MAX_HEIGHT = 250;
	private static final int FIELD_HEIGHT = 20;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;
	private ValidationReport validation;
	private ExportType type = ExportType.CURRENT_INSTANCE;
	private String exportName;
	private String destination;
	private String customDestination;
	private boolean replaceExisting;
	private CraftStudioClientContext.BackgroundTask<ExportResult> task;
	private ExportResult result;
	private Text status;
	private int statusColor = CraftStudioTheme.TEXT_MUTED;
	private TextFieldWidget nameField;
	private TextFieldWidget destinationField;

	public ExportScreen(
		CraftStudioClientContext context,
		Screen parent,
		ValidationReport validation
	) {
		super(Text.translatable("screen.craftstudio.export.title"));
		this.context = context;
		this.parent = parent;
		this.validation = validation;
		exportName = context.activeProject().metadata().slug();
		customDestination = context.defaultExportRoot().toString();
		destination = customDestination;
	}

	@Override
	protected void init() {
		int panelX = (width - panelWidth()) / 2;
		int panelY = (height - panelHeight()) / 2;
		int contentX = panelX + CraftStudioTheme.SPACE_4;
		int contentWidth = panelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int firstY = panelY + 48;

		nameField = new TextFieldWidget(
			textRenderer,
			contentX,
			firstY,
			contentWidth,
			FIELD_HEIGHT,
			Text.translatable("screen.craftstudio.export.name")
		);
		nameField.setMaxLength(80);
		nameField.setText(exportName);
		nameField.setChangedListener(value -> exportName = value);
		nameField.setEditable(task == null);
		addDrawableChild(nameField);

		destinationField = new TextFieldWidget(
			textRenderer,
			contentX,
			firstY + 31,
			contentWidth,
			FIELD_HEIGHT,
			Text.translatable("screen.craftstudio.export.destination")
		);
		destination = type == ExportType.CURRENT_INSTANCE
			? context.currentInstanceResourcePacksRoot().toString()
			: customDestination;
		destinationField.setText(destination);
		destinationField.setMaxLength(2048);
		destinationField.setChangedListener(value -> {
			destination = value;
			if (type == ExportType.CUSTOM_LOCATION) {
				customDestination = value;
			}
		});
		destinationField.setEditable(task == null && type != ExportType.CURRENT_INSTANCE);
		addDrawableChild(destinationField);

		int gap = CraftStudioTheme.SPACE_2;
		int halfWidth = (contentWidth - gap) / 2;
		ButtonWidget typeButton = ButtonWidget.builder(
			typeLabel(),
			button -> cycleType()
		).dimensions(contentX, firstY + 62, halfWidth, BUTTON_HEIGHT).build();
		typeButton.active = task == null;
		addDrawableChild(typeButton);
		ButtonWidget overwriteButton = ButtonWidget.builder(
			overwriteLabel(),
			button -> {
				replaceExisting = !replaceExisting;
				button.setMessage(overwriteLabel());
			}
		).dimensions(
			contentX + halfWidth + gap,
			firstY + 62,
			contentWidth - halfWidth - gap,
			BUTTON_HEIGHT
		).build();
		overwriteButton.active = task == null;
		addDrawableChild(overwriteButton);

		int footerY = panelY + panelHeight() - BUTTON_HEIGHT - CraftStudioTheme.SPACE_3;
		int thirdWidth = (contentWidth - gap * 2) / 3;
		ButtonWidget exportButton = ButtonWidget.builder(
			Text.translatable(
				task == null
					? "screen.craftstudio.export.start"
					: "screen.craftstudio.export.cancel"
			),
			button -> {
				if (task == null) {
					beginExport();
				} else {
					task.cancel();
				}
			}
		).dimensions(contentX, footerY, thirdWidth, BUTTON_HEIGHT).build();
		exportButton.active = task != null || validation != null && validation.canExport();
		addDrawableChild(exportButton);
		ButtonWidget validationButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.export.validation"),
			button -> client.setScreen(new ValidationCenterScreen(context, this))
		).dimensions(
			contentX + thirdWidth + gap,
			footerY,
			thirdWidth,
			BUTTON_HEIGHT
		).build();
		validationButton.active = task == null;
		addDrawableChild(validationButton);
		ButtonWidget back = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.back"),
			button -> close()
		).dimensions(
			contentX + (thirdWidth + gap) * 2,
			footerY,
			contentWidth - thirdWidth * 2 - gap * 2,
			BUTTON_HEIGHT
		).build();
		back.active = task == null;
		addDrawableChild(back);
		setInitialFocus(nameField);
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		int panelX = (width - panelWidth()) / 2;
		int panelY = (height - panelHeight()) / 2;
		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(
			panelX,
			panelY,
			panelX + panelWidth(),
			panelY + panelHeight(),
			CraftStudioTheme.PANEL
		);
		drawContext.fill(
			panelX,
			panelY,
			panelX + panelWidth(),
			panelY + CraftStudioTheme.SPACE_1,
			CraftStudioTheme.ACCENT
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			panelY + CraftStudioTheme.SPACE_3,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.export.name"),
			panelX + CraftStudioTheme.SPACE_4,
			panelY + 36,
			CraftStudioTheme.TEXT_MUTED
		);
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.export.destination"),
			panelX + CraftStudioTheme.SPACE_4,
			panelY + 67,
			CraftStudioTheme.TEXT_MUTED
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			statusText(),
			width / 2,
			panelY + 149,
			statusColor()
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

	private void cycleType() {
		type = switch (type) {
			case CURRENT_INSTANCE -> ExportType.CUSTOM_LOCATION;
			case CUSTOM_LOCATION -> ExportType.CURRENT_INSTANCE;
		};
		status = null;
		result = null;
		clearAndInit();
	}

	private void beginExport() {
		ExportRequest request;
		try {
			if (destination.isBlank()) {
				throw new IllegalArgumentException("Choose a ZIP destination folder.");
			}
			Path destinationRoot = Path.of(destination.strip());
			request = new ExportRequest(
				type,
				destinationRoot,
				exportName,
				replaceExisting
					? ExistingOutputPolicy.REPLACE_WITH_BACKUP
					: ExistingOutputPolicy.CANCEL
			);
		} catch (IllegalArgumentException exception) {
			setError(exception.getMessage());
			return;
		}
		Path output = context.plannedExportPath(request);
		if (Files.exists(output)) {
			if (!replaceExisting) {
				setError(
					Text.translatable("screen.craftstudio.export.exists").getString()
				);
				return;
			}
			client.setScreen(new BundleConfirmationScreen(
				this,
				Text.translatable("screen.craftstudio.export.replace_title"),
				Text.translatable("screen.craftstudio.export.replace_warning"),
				List.of(
					"Existing output: " + output,
					"A complete backup will be created before replacement."
				),
				() -> runExport(request)
			));
			return;
		}
		runExport(request);
	}

	private void runExport(ExportRequest request) {
		if (task != null) {
			return;
		}
		result = null;
		status = Text.translatable("screen.craftstudio.export.running");
		statusColor = CraftStudioTheme.INFORMATION;
		task = context.exportActiveProject(request);
		CraftStudioClientContext.BackgroundTask<ExportResult> runningTask = task;
		clearAndInit();
		runningTask.future().whenCompleteAsync((exported, failure) -> {
			if (task != runningTask) {
				return;
			}
			task = null;
			if (failure == null) {
				result = exported;
				validation = exported.validation();
				status = Text.translatable(
					"screen.craftstudio.export.complete",
					exported.fileCount(),
					exported.output().toString()
				);
				statusColor = CraftStudioTheme.SUCCESS;
			} else {
				setError(CraftStudioClientContext.userMessage(failure));
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private Text statusText() {
		if (task != null) {
			return Text.translatable("screen.craftstudio.export.running");
		}
		if (status != null) {
			return Text.literal(shorten(status.getString()));
		}
		if (validation == null) {
			return Text.translatable("screen.craftstudio.export.validation_required");
		}
		if (!validation.canExport()) {
			return Text.translatable(
				"screen.craftstudio.export.blocked",
				validation.count(ValidationSeverity.ERROR)
			);
		}
		return Text.translatable(
			"screen.craftstudio.export.ready",
			validation.count(ValidationSeverity.WARNING)
		);
	}

	private int statusColor() {
		if (task != null) {
			return CraftStudioTheme.INFORMATION;
		}
		if (status != null) {
			return statusColor;
		}
		return validation != null && validation.canExport()
			? CraftStudioTheme.SUCCESS
			: CraftStudioTheme.ERROR;
	}

	private void setError(String message) {
		status = Text.literal(message == null || message.isBlank() ? "Export failed." : message);
		statusColor = CraftStudioTheme.ERROR;
	}

	private Text typeLabel() {
		return Text.translatable(
			switch (type) {
				case CURRENT_INSTANCE -> "screen.craftstudio.export.type_instance";
				case CUSTOM_LOCATION -> "screen.craftstudio.export.type_custom";
			}
		);
	}

	private Text overwriteLabel() {
		return Text.translatable(
			replaceExisting
				? "screen.craftstudio.export.overwrite_backup"
				: "screen.craftstudio.export.overwrite_cancel"
		);
	}

	private String shorten(String value) {
		return value.length() <= 100 ? value : value.substring(0, 97) + "...";
	}

	private int panelWidth() {
		return Math.max(1, Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2));
	}

	private int panelHeight() {
		return Math.max(1, Math.min(PANEL_MAX_HEIGHT, height - CraftStudioTheme.SPACE_2 * 2));
	}
}
