package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class OpenProjectScreen extends Screen {
	private static final Text TITLE = Text.translatable("screen.craftstudio.open_project.title");
	private static final int PANEL_MAX_WIDTH = 440;
	private static final int PANEL_HEIGHT = 132;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;

	private String projectPath;
	private boolean busy;
	private Text statusMessage;
	private TextFieldWidget pathField;
	private ButtonWidget openButton;

	public OpenProjectScreen(CraftStudioClientContext context, Screen parent) {
		super(TITLE);
		this.context = context;
		this.parent = parent;
		this.projectPath = context.defaultWorkspaceRoot().toString();
	}

	@Override
	protected void init() {
		int fieldX = getPanelX() + CraftStudioTheme.SPACE_4;
		int fieldWidth = getPanelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int fieldY = getPanelY() + 48;

		pathField = new TextFieldWidget(
			textRenderer,
			fieldX,
			fieldY,
			fieldWidth,
			20,
			Text.translatable("screen.craftstudio.open_project.path")
		);
		pathField.setMaxLength(1024);
		pathField.setText(projectPath);
		pathField.setChangedListener(value -> {
			projectPath = value;
			updateOpenButton();
		});
		pathField.setEditable(!busy);
		addDrawableChild(pathField);

		int buttonY = getPanelY() + PANEL_HEIGHT - BUTTON_HEIGHT - CraftStudioTheme.SPACE_2;
		int buttonWidth = (fieldWidth - CraftStudioTheme.SPACE_2) / 2;
		openButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.open_project.open"),
			button -> openProject()
		).dimensions(fieldX, buttonY, buttonWidth, BUTTON_HEIGHT).build();
		addDrawableChild(openButton);

		ButtonWidget cancelButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.common.cancel"),
			button -> close()
		).dimensions(
			fieldX + buttonWidth + CraftStudioTheme.SPACE_2,
			buttonY,
			buttonWidth,
			BUTTON_HEIGHT
		).build();
		cancelButton.active = !busy;
		addDrawableChild(cancelButton);

		updateOpenButton();
		setInitialFocus(pathField);
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		int panelX = getPanelX();
		int panelY = getPanelY();

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
			title,
			width / 2,
			panelY + CraftStudioTheme.SPACE_3,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.open_project.path"),
			panelX + CraftStudioTheme.SPACE_4,
			panelY + 36,
			CraftStudioTheme.TEXT_MUTED
		);
		if (statusMessage != null) {
			drawContext.drawCenteredTextWithShadow(
				textRenderer,
				statusMessage,
				width / 2,
				panelY + 72,
				busy ? CraftStudioTheme.INFORMATION : CraftStudioTheme.ERROR
			);
		}

		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!busy && client != null) {
			client.setScreen(parent);
		}
	}

	private void openProject() {
		if (busy) {
			return;
		}

		Path selectedPath;
		try {
			selectedPath = Path.of(projectPath.strip());
		} catch (InvalidPathException exception) {
			statusMessage = Text.literal("Project path is invalid.");
			return;
		}

		busy = true;
		statusMessage = Text.translatable("screen.craftstudio.project.opening");
		pathField.setEditable(false);
		updateOpenButton();

		context.openProject(selectedPath).whenCompleteAsync((project, error) -> {
			busy = false;
			if (error == null) {
				if (client.currentScreen == this) {
					client.setScreen(parent);
				}
			} else {
				statusMessage = Text.literal(shortMessage(CraftStudioClientContext.userMessage(error)));
				pathField.setEditable(true);
				updateOpenButton();
			}
		}, client);
	}

	private void updateOpenButton() {
		if (openButton != null) {
			openButton.active = !busy && !projectPath.isBlank();
		}
	}

	private String shortMessage(String message) {
		return message.length() <= 84 ? message : message.substring(0, 81) + "...";
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
