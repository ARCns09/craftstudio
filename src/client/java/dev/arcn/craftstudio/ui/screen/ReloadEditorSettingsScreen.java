package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class ReloadEditorSettingsScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 480;
	private static final int PANEL_MAX_HEIGHT = 190;
	private static final int FIELD_HEIGHT = 20;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;
	private String preferredImageEditor;
	private boolean autoReload;
	private boolean saving;
	private Text status;
	private int statusColor = CraftStudioTheme.TEXT_MUTED;
	private TextFieldWidget editorField;

	public ReloadEditorSettingsScreen(CraftStudioClientContext context, Screen parent) {
		super(Text.translatable("screen.craftstudio.settings.title"));
		this.context = context;
		this.parent = parent;
		preferredImageEditor = context.editorSettings().preferredImageEditor();
		autoReload = context.autoReloadEnabled();
	}

	@Override
	protected void init() {
		int panelX = (width - panelWidth()) / 2;
		int panelY = (height - panelHeight()) / 2;
		int contentX = panelX + CraftStudioTheme.SPACE_4;
		int contentWidth = panelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int firstY = panelY + 50;

		editorField = new TextFieldWidget(
			textRenderer,
			contentX,
			firstY,
			contentWidth,
			FIELD_HEIGHT,
			Text.translatable("screen.craftstudio.settings.image_editor")
		);
		editorField.setMaxLength(1024);
		editorField.setText(preferredImageEditor);
		editorField.setChangedListener(value -> preferredImageEditor = value);
		editorField.setEditable(!saving);
		addDrawableChild(editorField);

		ButtonWidget autoReloadButton = ButtonWidget.builder(
			autoReloadLabel(),
			button -> {
				autoReload = !autoReload;
				button.setMessage(autoReloadLabel());
			}
		).dimensions(
			contentX,
			firstY + 30,
			contentWidth,
			BUTTON_HEIGHT
		).build();
		autoReloadButton.active = !saving && context.activeProject() != null;
		addDrawableChild(autoReloadButton);

		int buttonY = panelY + panelHeight() - BUTTON_HEIGHT - CraftStudioTheme.SPACE_3;
		int buttonWidth = (contentWidth - CraftStudioTheme.SPACE_2) / 2;
		ButtonWidget save = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.settings.save"),
			button -> save()
		).dimensions(contentX, buttonY, buttonWidth, BUTTON_HEIGHT).build();
		save.active = !saving;
		addDrawableChild(save);
		ButtonWidget back = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.back"),
			button -> close()
		).dimensions(
			contentX + buttonWidth + CraftStudioTheme.SPACE_2,
			buttonY,
			buttonWidth,
			BUTTON_HEIGHT
		).build();
		back.active = !saving;
		addDrawableChild(back);
		setInitialFocus(editorField);
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
			Text.translatable("screen.craftstudio.settings.image_editor"),
			panelX + CraftStudioTheme.SPACE_4,
			panelY + 38,
			CraftStudioTheme.TEXT_MUTED
		);
		Text helper = context.activeProject() == null
			? Text.translatable("screen.craftstudio.settings.no_project")
			: Text.translatable("screen.craftstudio.settings.help");
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			status == null ? helper : status,
			width / 2,
			panelY + 107,
			status == null ? CraftStudioTheme.TEXT_MUTED : statusColor
		);
		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!saving && client != null) {
			client.setScreen(parent);
		}
	}

	private void save() {
		if (saving) {
			return;
		}
		saving = true;
		status = Text.translatable("screen.craftstudio.settings.saving");
		statusColor = CraftStudioTheme.INFORMATION;
		clearAndInit();
		context.saveReloadAndEditorSettings(preferredImageEditor, autoReload)
			.whenCompleteAsync((saved, failure) -> {
				saving = false;
				if (failure == null) {
					status = Text.translatable("screen.craftstudio.settings.saved");
					statusColor = CraftStudioTheme.SUCCESS;
				} else {
					status = Text.literal(CraftStudioClientContext.userMessage(failure));
					statusColor = CraftStudioTheme.ERROR;
				}
				if (client.currentScreen == this) {
					clearAndInit();
				}
			}, client);
	}

	private Text autoReloadLabel() {
		return Text.translatable(
			autoReload
				? "screen.craftstudio.settings.auto_reload_on"
				: "screen.craftstudio.settings.auto_reload_off"
		);
	}

	private int panelWidth() {
		return Math.max(1, Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2));
	}

	private int panelHeight() {
		return Math.max(1, Math.min(PANEL_MAX_HEIGHT, height - CraftStudioTheme.SPACE_2 * 2));
	}
}
