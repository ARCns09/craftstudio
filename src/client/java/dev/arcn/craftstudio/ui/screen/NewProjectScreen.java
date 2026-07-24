package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.project.application.ProjectService;
import dev.arcn.craftstudio.project.domain.ProjectCreationRequest;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class NewProjectScreen extends Screen {
	private static final Text TITLE = Text.translatable("screen.craftstudio.new_project.title");
	private static final int PANEL_MAX_WIDTH = 440;
	private static final int PANEL_MAX_HEIGHT = 248;
	private static final int FIELD_HEIGHT = 18;
	private static final int ROW_HEIGHT = 28;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;

	private String projectName = "";
	private String slug = "";
	private String description = "";
	private String author = "";
	private String workspace;
	private boolean updatingSlug;
	private boolean slugCustomized;
	private boolean busy;
	private Text statusMessage;
	private int statusColor = CraftStudioTheme.TEXT_MUTED;

	private TextFieldWidget nameField;
	private TextFieldWidget slugField;
	private TextFieldWidget descriptionField;
	private TextFieldWidget authorField;
	private TextFieldWidget workspaceField;
	private ButtonWidget createButton;

	public NewProjectScreen(CraftStudioClientContext context, Screen parent) {
		super(TITLE);
		this.context = context;
		this.parent = parent;
		this.workspace = context.defaultWorkspaceRoot().toString();
	}

	@Override
	protected void init() {
		int fieldX = getPanelX() + CraftStudioTheme.SPACE_4;
		int fieldWidth = getPanelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int firstFieldY = getPanelY() + 38;

		nameField = createField(
			fieldX,
			firstFieldY,
			fieldWidth,
			80,
			"screen.craftstudio.new_project.name",
			projectName
		);
		nameField.setChangedListener(value -> {
			projectName = value;
			if (!slugCustomized) {
				updatingSlug = true;
				slug = ProjectService.slugify(value);
				slugField.setText(slug);
				updatingSlug = false;
			}
			updateCreateButton();
		});

		slugField = createField(
			fieldX,
			firstFieldY + ROW_HEIGHT,
			fieldWidth,
			64,
			"screen.craftstudio.new_project.slug",
			slug
		);
		slugField.setChangedListener(value -> {
			slug = value;
			if (!updatingSlug) {
				slugCustomized = true;
			}
			updateCreateButton();
		});

		descriptionField = createField(
			fieldX,
			firstFieldY + ROW_HEIGHT * 2,
			fieldWidth,
			256,
			"screen.craftstudio.new_project.description",
			description
		);
		descriptionField.setChangedListener(value -> description = value);

		authorField = createField(
			fieldX,
			firstFieldY + ROW_HEIGHT * 3,
			fieldWidth,
			80,
			"screen.craftstudio.new_project.author",
			author
		);
		authorField.setChangedListener(value -> author = value);

		workspaceField = createField(
			fieldX,
			firstFieldY + ROW_HEIGHT * 4,
			fieldWidth,
			1024,
			"screen.craftstudio.new_project.workspace",
			workspace
		);
		workspaceField.setChangedListener(value -> {
			workspace = value;
			updateCreateButton();
		});

		int buttonY = getPanelY() + getPanelHeight() - BUTTON_HEIGHT - CraftStudioTheme.SPACE_2;
		int buttonWidth = (fieldWidth - CraftStudioTheme.SPACE_2) / 2;
		createButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.new_project.create"),
			button -> createProject()
		).dimensions(fieldX, buttonY, buttonWidth, BUTTON_HEIGHT).build();
		addDrawableChild(createButton);

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

		setFieldsEditable(!busy);
		updateCreateButton();
		setInitialFocus(nameField);
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int firstFieldY = panelY + 38;

		drawPanel(drawContext);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			panelY + CraftStudioTheme.SPACE_3,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawFieldLabel(drawContext, "screen.craftstudio.new_project.name", firstFieldY);
		drawFieldLabel(drawContext, "screen.craftstudio.new_project.slug", firstFieldY + ROW_HEIGHT);
		drawFieldLabel(
			drawContext,
			"screen.craftstudio.new_project.description",
			firstFieldY + ROW_HEIGHT * 2
		);
		drawFieldLabel(drawContext, "screen.craftstudio.new_project.author", firstFieldY + ROW_HEIGHT * 3);
		drawFieldLabel(
			drawContext,
			"screen.craftstudio.new_project.workspace",
			firstFieldY + ROW_HEIGHT * 4
		);

		int buttonY = panelY + getPanelHeight() - BUTTON_HEIGHT - CraftStudioTheme.SPACE_2;
		Text footer = statusMessage != null
			? statusMessage
			: Text.translatable("screen.craftstudio.new_project.target", "1.21.11");
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			footer,
			width / 2,
			buttonY - 12,
			statusMessage == null ? CraftStudioTheme.TEXT_MUTED : statusColor
		);

		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!busy && client != null) {
			client.setScreen(parent);
		}
	}

	private TextFieldWidget createField(
		int x,
		int y,
		int fieldWidth,
		int maxLength,
		String labelKey,
		String value
	) {
		TextFieldWidget field = new TextFieldWidget(
			textRenderer,
			x,
			y,
			fieldWidth,
			FIELD_HEIGHT,
			Text.translatable(labelKey)
		);
		field.setMaxLength(maxLength);
		field.setText(value);
		addDrawableChild(field);
		return field;
	}

	private void createProject() {
		if (busy) {
			return;
		}

		Path workspacePath;
		try {
			workspacePath = Path.of(workspace.strip());
		} catch (InvalidPathException exception) {
			setError("Workspace path is invalid.");
			return;
		}

		busy = true;
		statusMessage = Text.translatable("screen.craftstudio.project.creating");
		statusColor = CraftStudioTheme.INFORMATION;
		setFieldsEditable(false);
		updateCreateButton();

		ProjectCreationRequest request = new ProjectCreationRequest(
			projectName,
			slug,
			description,
			author,
			workspacePath
		);
		context.createProject(request).whenCompleteAsync((project, error) -> {
			busy = false;
			if (error == null) {
				if (client.currentScreen == this) {
					client.setScreen(parent);
				}
			} else {
				setError(CraftStudioClientContext.userMessage(error));
				setFieldsEditable(true);
				updateCreateButton();
			}
		}, client);
	}

	private void setError(String message) {
		statusMessage = Text.literal(shortMessage(message));
		statusColor = CraftStudioTheme.ERROR;
	}

	private void updateCreateButton() {
		if (createButton != null) {
			createButton.active = !busy
				&& !projectName.isBlank()
				&& !slug.isBlank()
				&& !workspace.isBlank();
		}
	}

	private void setFieldsEditable(boolean editable) {
		if (nameField != null) {
			nameField.setEditable(editable);
			slugField.setEditable(editable);
			descriptionField.setEditable(editable);
			authorField.setEditable(editable);
			workspaceField.setEditable(editable);
		}
	}

	private void drawPanel(DrawContext drawContext) {
		int panelX = getPanelX();
		int panelY = getPanelY();
		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(
			panelX,
			panelY,
			panelX + getPanelWidth(),
			panelY + getPanelHeight(),
			CraftStudioTheme.PANEL
		);
		drawContext.fill(
			panelX,
			panelY,
			panelX + getPanelWidth(),
			panelY + CraftStudioTheme.SPACE_1,
			CraftStudioTheme.ACCENT
		);
	}

	private void drawFieldLabel(DrawContext drawContext, String labelKey, int fieldY) {
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable(labelKey),
			getPanelX() + CraftStudioTheme.SPACE_4,
			fieldY - 10,
			CraftStudioTheme.TEXT_MUTED
		);
	}

	private String shortMessage(String message) {
		return message.length() <= 84 ? message : message.substring(0, 81) + "...";
	}

	private int getPanelWidth() {
		return Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2);
	}

	private int getPanelHeight() {
		return Math.min(PANEL_MAX_HEIGHT, height - CraftStudioTheme.SPACE_2 * 2);
	}

	private int getPanelX() {
		return (width - getPanelWidth()) / 2;
	}

	private int getPanelY() {
		return (height - getPanelHeight()) / 2;
	}
}
