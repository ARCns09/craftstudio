package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.ResolutionIssue;
import dev.arcn.craftstudio.graph.domain.ResolutionIssueSeverity;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class AssetDetailsScreen extends Screen {
	private static final int MARGIN = 16;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_HEIGHT = 12;

	private final CraftStudioClientContext context;
	private final Screen parent;
	private final CatalogAsset asset;

	private AssetResolutionResult resolution;
	private String resolutionError;
	private boolean resolutionRequested;
	private int offset;
	private int pageSize = 1;
	private List<DetailRow> rows = List.of();

	public AssetDetailsScreen(
		CraftStudioClientContext context,
		Screen parent,
		CatalogAsset asset
	) {
		super(Text.translatable("screen.craftstudio.asset_details.title"));
		this.context = context;
		this.parent = parent;
		this.asset = asset;
	}

	@Override
	protected void init() {
		if (asset.kind() == AssetKind.BLOCK && !resolutionRequested) {
			resolutionRequested = true;
			MinecraftClient clientReference = client;
			context.resolveBlock(asset).whenComplete((result, error) -> clientReference.execute(() -> {
				if (error == null) {
					resolution = result;
					rows = createGraphRows(result);
				} else {
					resolutionError = CraftStudioClientContext.userMessage(error);
				}
				if (clientReference.currentScreen == this) {
					clearAndInit();
				}
			}));
		}
		if (asset.kind() == AssetKind.ITEM) {
			rows = List.of(
				new DetailRow(
					Text.translatable("screen.craftstudio.asset_details.item_deferred").getString(),
					CraftStudioTheme.INFORMATION
				)
			);
		}

		int contentWidth = width - MARGIN * 2;
		int footerY = height - MARGIN - BUTTON_HEIGHT;
		int rowsY = 104;
		pageSize = Math.max(1, (footerY - rowsY - 18) / ROW_HEIGHT);
		if (offset >= rows.size() && offset > 0) {
			offset = Math.max(0, ((rows.size() - 1) / pageSize) * pageSize);
		}

		int gap = CraftStudioTheme.SPACE_2;
		int buttonWidth = (contentWidth - gap * 2) / 3;
		ButtonWidget previous = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.previous"),
			button -> {
				offset = Math.max(0, offset - pageSize);
				clearAndInit();
			}
		).dimensions(MARGIN, footerY, buttonWidth, BUTTON_HEIGHT).build();
		previous.active = offset > 0;
		addDrawableChild(previous);

		addDrawableChild(
			ButtonWidget.builder(
				Text.translatable("screen.craftstudio.catalog.back"),
				button -> close()
			).dimensions(
				MARGIN + buttonWidth + gap,
				footerY,
				buttonWidth,
				BUTTON_HEIGHT
			).build()
		);

		ButtonWidget next = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.next"),
			button -> {
				offset += pageSize;
				clearAndInit();
			}
		).dimensions(
			MARGIN + (buttonWidth + gap) * 2,
			footerY,
			buttonWidth,
			BUTTON_HEIGHT
		).build();
		next.active = offset + pageSize < rows.size();
		addDrawableChild(next);
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(0, 0, width, 28, CraftStudioTheme.PANEL);
		drawContext.fill(0, 26, width, 28, CraftStudioTheme.ACCENT);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			Text.literal(asset.displayName()),
			width / 2,
			CraftStudioTheme.SPACE_2,
			CraftStudioTheme.TEXT_PRIMARY
		);

		int textX = MARGIN;
		drawLine(drawContext, "screen.craftstudio.asset_details.id", asset.identifier(), textX, 36);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.type",
			Text.translatable(
				asset.kind() == AssetKind.BLOCK
					? "screen.craftstudio.catalog.type.block"
					: "screen.craftstudio.catalog.type.item"
			).getString(),
			textX,
			50
		);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.translation_key",
			asset.translationKey(),
			textX,
			64
		);

		Text sectionTitle = asset.kind() == AssetKind.BLOCK
			? Text.translatable("screen.craftstudio.asset_details.dependencies")
			: Text.translatable("screen.craftstudio.asset_details.placeholder");
		drawContext.drawTextWithShadow(
			textRenderer,
			sectionTitle,
			textX,
			86,
			CraftStudioTheme.TEXT_PRIMARY
		);

		if (resolutionError != null) {
			drawContext.drawWrappedTextWithShadow(
				textRenderer,
				Text.literal(resolutionError),
				textX,
				104,
				width - MARGIN * 2,
				CraftStudioTheme.ERROR
			);
		} else if (asset.kind() == AssetKind.BLOCK && resolution == null) {
			drawContext.drawTextWithShadow(
				textRenderer,
				Text.translatable("screen.craftstudio.asset_details.resolving"),
				textX,
				104,
				CraftStudioTheme.INFORMATION
			);
		} else {
			int end = Math.min(rows.size(), offset + pageSize);
			for (int index = offset; index < end; index++) {
				DetailRow row = rows.get(index);
				String visible = textRenderer.trimToWidth(row.text(), width - MARGIN * 2);
				drawContext.drawTextWithShadow(
					textRenderer,
					Text.literal(visible),
					textX,
					104 + (index - offset) * ROW_HEIGHT,
					row.color()
				);
			}
		}

		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			statusText(),
			width / 2,
			height - MARGIN - BUTTON_HEIGHT - 12,
			resolutionError == null ? CraftStudioTheme.TEXT_MUTED : CraftStudioTheme.ERROR
		);
		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private List<DetailRow> createGraphRows(AssetResolutionResult result) {
		List<DetailRow> resultRows = new ArrayList<>();
		Set<String> expandedNodes = new HashSet<>();
		expandedNodes.add(result.graph().rootNodeId());
		appendOutgoingRows(result, result.graph().rootNodeId(), 0, expandedNodes, resultRows);
		if (!result.issues().isEmpty()) {
			resultRows.add(new DetailRow(
				Text.translatable("screen.craftstudio.asset_details.issues").getString(),
				CraftStudioTheme.TEXT_PRIMARY
			));
			for (ResolutionIssue issue : result.issues()) {
				int color = issue.severity() == ResolutionIssueSeverity.ERROR
					? CraftStudioTheme.ERROR
					: issue.severity() == ResolutionIssueSeverity.WARNING
						? CraftStudioTheme.INFORMATION
						: CraftStudioTheme.TEXT_MUTED;
				resultRows.add(new DetailRow(
					"[" + issue.severity() + " " + issue.code() + "] " + issue.message(),
					color
				));
			}
		}
		return List.copyOf(resultRows);
	}

	private void appendOutgoingRows(
		AssetResolutionResult result,
		String nodeId,
		int depth,
		Set<String> expandedNodes,
		List<DetailRow> destination
	) {
		for (AssetGraphEdge edge : result.graph().outgoing(nodeId)) {
			AssetGraphNode target = result.graph().nodes().get(edge.toNodeId());
			boolean firstVisit = expandedNodes.add(target.id());
			String indentation = "  ".repeat(Math.min(depth, 8));
			String relation = edge.type().name().toLowerCase().replace('_', ' ');
			String targetName = target.packPath().isEmpty()
				? target.namespace() + ":" + target.logicalPath()
				: target.packPath();
			String shared = firstVisit ? "" : " (already shown)";
			destination.add(new DetailRow(
				indentation + "• " + relation + " [" + edge.label() + "] → "
					+ targetName + " [" + target.sourceLayer() + "]" + shared,
				target.sourceLayer() == dev.arcn.craftstudio.resource.domain.SourceLayer.MISSING
					? CraftStudioTheme.ERROR
					: CraftStudioTheme.TEXT_MUTED
			));
			if (firstVisit && depth < 12) {
				appendOutgoingRows(result, target.id(), depth + 1, expandedNodes, destination);
			}
		}
	}

	private Text statusText() {
		if (resolutionError != null) {
			return Text.translatable("screen.craftstudio.asset_details.failed");
		}
		if (asset.kind() == AssetKind.BLOCK && resolution == null) {
			return Text.translatable("screen.craftstudio.asset_details.resolving");
		}
		if (resolution != null) {
			return Text.translatable(
				"screen.craftstudio.asset_details.resolved",
				resolution.stats().nodeCount(),
				resolution.stats().edgeCount(),
				resolution.stats().issueCount()
			);
		}
		return Text.translatable("screen.craftstudio.asset_details.item_deferred");
	}

	private void drawLine(
		DrawContext drawContext,
		String labelKey,
		String value,
		int x,
		int y
	) {
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable(labelKey, value),
			x,
			y,
			CraftStudioTheme.TEXT_MUTED
		);
	}

	private record DetailRow(String text, int color) {
	}
}
