package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext.RecentProjectView;
import dev.arcn.craftstudio.project.domain.CraftStudioProject;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.nio.file.Path;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class CraftStudioHomeScreen extends Screen {
	private static final Text TITLE = Text.translatable("screen.craftstudio.home.title");
	private static final int PANEL_MAX_WIDTH = 360;
	private static final int PANEL_MAX_HEIGHT = 300;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;

	private Text statusMessage;
	private int statusColor = CraftStudioTheme.TEXT_MUTED;
	private boolean busy;
	private long recentProjectsRevision;

	public CraftStudioHomeScreen(CraftStudioClientContext context, Screen parent) {
		super(TITLE);
		this.context = context;
		this.parent = parent;
	}

	@Override
	protected void init() {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int contentX = panelX + CraftStudioTheme.SPACE_4;
		int contentWidth = getPanelWidth() - CraftStudioTheme.SPACE_4 * 2;
		int buttonY = panelY + 70;
		int halfButtonWidth = (contentWidth - CraftStudioTheme.SPACE_2) / 2;

		ButtonWidget newProjectButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.new_project"),
			button -> client.setScreen(new NewProjectScreen(context, this))
		).dimensions(contentX, buttonY, halfButtonWidth, BUTTON_HEIGHT).build();
		newProjectButton.active = !busy;
		addDrawableChild(newProjectButton);

		ButtonWidget openProjectButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.open_project"),
			button -> client.setScreen(new OpenProjectScreen(context, this))
		).dimensions(
			contentX + halfButtonWidth + CraftStudioTheme.SPACE_2,
			buttonY,
			halfButtonWidth,
			BUTTON_HEIGHT
		).build();
		openProjectButton.active = !busy;
		addDrawableChild(openProjectButton);

		ButtonWidget browseAssetsButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.browse_assets"),
			button -> client.setScreen(new AssetBrowserScreen(context, this))
		).dimensions(
			contentX,
			buttonY + BUTTON_HEIGHT + CraftStudioTheme.SPACE_2,
			contentWidth,
			BUTTON_HEIGHT
		).build();
		browseAssetsButton.active = !busy;
		addDrawableChild(browseAssetsButton);

		ButtonWidget closeButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.home.close"),
			button -> close()
		).dimensions(
			contentX,
			buttonY + (BUTTON_HEIGHT + CraftStudioTheme.SPACE_2) * 2,
			contentWidth,
			BUTTON_HEIGHT
		).build();
		closeButton.active = !busy;
		addDrawableChild(closeButton);

		int recentY = buttonY + (BUTTON_HEIGHT + CraftStudioTheme.SPACE_2) * 3 + 36;
		int availableHeight = panelY + getPanelHeight() - CraftStudioTheme.SPACE_4 - recentY;
		int maximumVisible = Math.min(3, Math.max(0, availableHeight / 24));
		int index = 0;
		for (RecentProjectView recentProject : context.recentProjects()) {
			if (index >= maximumVisible) {
				break;
			}
			ButtonWidget recentButton = ButtonWidget.builder(
				recentProjectLabel(recentProject),
				button -> openRecentProject(recentProject)
			).dimensions(contentX, recentY + index * 24, contentWidth, BUTTON_HEIGHT).build();
			recentButton.active = !busy && recentProject.available();
			addDrawableChild(recentButton);
			index++;
		}
		recentProjectsRevision = context.recentProjectsRevision();
	}

	@Override
	public void tick() {
		if (!busy && recentProjectsRevision != context.recentProjectsRevision()) {
			clearAndInit();
		}
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		int panelX = getPanelX();
		int panelY = getPanelY();
		int panelWidth = getPanelWidth();

		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(
			panelX,
			panelY,
			panelX + panelWidth,
			panelY + getPanelHeight(),
			CraftStudioTheme.PANEL
		);
		drawContext.fill(
			panelX,
			panelY,
			panelX + panelWidth,
			panelY + CraftStudioTheme.SPACE_1,
			CraftStudioTheme.ACCENT
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			panelY + CraftStudioTheme.SPACE_4,
			CraftStudioTheme.TEXT_PRIMARY
		);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.home.subtitle"),
			width / 2,
			panelY + CraftStudioTheme.SPACE_4 + 20,
			CraftStudioTheme.TEXT_MUTED
		);

		CraftStudioProject activeProject = context.activeProject();
		if (activeProject != null) {
			drawContext.drawCenteredTextWithShadow(
				textRenderer,
				Text.translatable(
					"screen.craftstudio.home.active_project",
					activeProject.metadata().name()
				),
				width / 2,
				panelY + CraftStudioTheme.SPACE_4 + 38,
				CraftStudioTheme.SUCCESS
			);
		}

		int recentHeaderY = panelY + 172;
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.home.recent_projects"),
			panelX + CraftStudioTheme.SPACE_4,
			recentHeaderY,
			CraftStudioTheme.TEXT_PRIMARY
		);
		if (context.recentProjects().isEmpty()) {
			drawContext.drawTextWithShadow(
				textRenderer,
				Text.translatable("screen.craftstudio.home.no_recent_projects"),
				panelX + CraftStudioTheme.SPACE_4,
				recentHeaderY + 18,
				CraftStudioTheme.TEXT_MUTED
			);
		}
		if (statusMessage != null) {
			drawContext.drawCenteredTextWithShadow(
				textRenderer,
				statusMessage,
				width / 2,
				panelY + getPanelHeight() - CraftStudioTheme.SPACE_4,
				statusColor
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

	private void openRecentProject(RecentProjectView recentProject) {
		if (busy || !recentProject.available()) {
			return;
		}
		busy = true;
		statusMessage = Text.translatable("screen.craftstudio.project.opening");
		statusColor = CraftStudioTheme.INFORMATION;
		clearAndInit();

		context.openProject(Path.of(recentProject.entry().path())).whenCompleteAsync((project, error) -> {
			busy = false;
			if (error == null) {
				statusMessage = Text.translatable(
					"screen.craftstudio.project.opened",
					project.metadata().name()
				);
				statusColor = CraftStudioTheme.SUCCESS;
			} else {
				statusMessage = Text.literal(shortMessage(CraftStudioClientContext.userMessage(error)));
				statusColor = CraftStudioTheme.ERROR;
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private Text recentProjectLabel(RecentProjectView recentProject) {
		String suffix = recentProject.available()
			? ""
			: " · " + Text.translatable("screen.craftstudio.home.unavailable").getString();
		return Text.literal(
			recentProject.entry().name() + " · " + recentProject.entry().targetVersion() + suffix
		);
	}

	private String shortMessage(String message) {
		return message.length() <= 72 ? message : message.substring(0, 69) + "...";
	}

	private int getPanelWidth() {
		return Math.min(PANEL_MAX_WIDTH, width - CraftStudioTheme.SPACE_4 * 2);
	}

	private int getPanelHeight() {
		return Math.min(PANEL_MAX_HEIGHT, height - CraftStudioTheme.SPACE_4 * 2);
	}

	private int getPanelX() {
		return (width - getPanelWidth()) / 2;
	}

	private int getPanelY() {
		return (height - getPanelHeight()) / 2;
	}
}
