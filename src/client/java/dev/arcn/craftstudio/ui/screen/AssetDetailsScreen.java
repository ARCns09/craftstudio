package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.graph.domain.AssetGraphEdge;
import dev.arcn.craftstudio.graph.domain.AssetGraphNode;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.graph.domain.GraphEdgeType;
import dev.arcn.craftstudio.graph.domain.GraphNodeType;
import dev.arcn.craftstudio.graph.domain.ResolutionIssue;
import dev.arcn.craftstudio.graph.domain.ResolutionIssueSeverity;
import dev.arcn.craftstudio.resource.domain.SourceLayer;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import dev.arcn.craftstudio.ui.widget.DependencyTreeWidget;
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

	private final CraftStudioClientContext context;
	private final Screen parent;
	private final CatalogAsset asset;

	private AssetResolutionResult resolution;
	private String resolutionError;
	private boolean resolutionRequested;
	private double detailScrollY;
	private List<DependencyTreeWidget.Row> rows = List.of();
	private DependencyTreeWidget detailList;

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
		if (detailList != null) {
			detailScrollY = detailList.getScrollY();
		}
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
				DependencyTreeWidget.Row.notice(
					Text.translatable("screen.craftstudio.asset_details.item_deferred"),
					CraftStudioTheme.INFORMATION
				)
			);
		}

		int margin = getMargin();
		int contentWidth = width - margin * 2;
		int footerY = height - margin - BUTTON_HEIGHT;
		int statusY = footerY - 12;
		int rowsY = getRowsY();
		detailList = new DependencyTreeWidget(
			margin,
			rowsY,
			contentWidth,
			Math.max(1, statusY - CraftStudioTheme.SPACE_2 - rowsY),
			Text.translatable("screen.craftstudio.asset_details.dependencies"),
			textRenderer,
			displayRows()
		);
		detailList.setScrollY(detailScrollY);
		addDrawableChild(detailList);

		int backWidth = Math.min(220, contentWidth);
		addDrawableChild(
			ButtonWidget.builder(
				Text.translatable("screen.craftstudio.catalog.back"),
				button -> close()
			).dimensions(
				(width - backWidth) / 2,
				footerY,
				backWidth,
				BUTTON_HEIGHT
			).build()
		);
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

		int margin = getMargin();
		int textX = margin;
		boolean compactHeight = height < 240;
		int firstMetadataY = compactHeight ? 32 : 36;
		int metadataSpacing = compactHeight ? 11 : 14;
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.id",
			asset.identifier(),
			textX,
			firstMetadataY
		);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.type",
			Text.translatable(
				asset.kind() == AssetKind.BLOCK
					? "screen.craftstudio.catalog.type.block"
					: "screen.craftstudio.catalog.type.item"
			).getString(),
			textX,
			firstMetadataY + metadataSpacing
		);
		drawLine(
			drawContext,
			"screen.craftstudio.asset_details.translation_key",
			asset.translationKey(),
			textX,
			firstMetadataY + metadataSpacing * 2
		);

		Text sectionTitle = asset.kind() == AssetKind.BLOCK
			? Text.translatable("screen.craftstudio.asset_details.dependencies")
			: Text.translatable("screen.craftstudio.asset_details.placeholder");
		drawContext.drawTextWithShadow(
			textRenderer,
			sectionTitle,
			textX,
			compactHeight ? 68 : 86,
			CraftStudioTheme.TEXT_PRIMARY
		);

		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			statusText(),
			width / 2,
			height - margin - BUTTON_HEIGHT - 12,
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

	private List<DependencyTreeWidget.Row> createGraphRows(AssetResolutionResult result) {
		List<DependencyTreeWidget.Row> resultRows = new ArrayList<>();
		Set<String> expandedNodes = new HashSet<>();
		expandedNodes.add(result.graph().rootNodeId());
		appendOutgoingRows(result, result.graph().rootNodeId(), 0, expandedNodes, resultRows);
		if (!result.issues().isEmpty()) {
			resultRows.add(DependencyTreeWidget.Row.notice(
				Text.translatable("screen.craftstudio.asset_details.issues"),
				CraftStudioTheme.TEXT_PRIMARY
			));
			for (ResolutionIssue issue : result.issues()) {
				int color = issue.severity() == ResolutionIssueSeverity.ERROR
					? CraftStudioTheme.ERROR
					: issue.severity() == ResolutionIssueSeverity.WARNING
						? CraftStudioTheme.INFORMATION
						: CraftStudioTheme.TEXT_MUTED;
				resultRows.add(DependencyTreeWidget.Row.notice(
					Text.literal("[" + issue.severity() + " " + issue.code() + "] " + issue.message()),
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
		List<DependencyTreeWidget.Row> destination
	) {
		for (AssetGraphEdge edge : result.graph().outgoing(nodeId)) {
			AssetGraphNode target = result.graph().nodes().get(edge.toNodeId());
			boolean firstVisit = expandedNodes.add(target.id());
			String targetName = target.packPath().isEmpty()
				? target.namespace() + ":" + target.logicalPath()
				: target.packPath();
			destination.add(DependencyTreeWidget.Row.dependency(
				depth,
				Text.literal(nodeTypeLabel(target.type())),
				Text.literal(relationshipLabel(edge.type(), edge.label())),
				targetName,
				Text.literal(sourceLabel(target.sourceLayer())),
				target.sourceLayer() == SourceLayer.MISSING
					? CraftStudioTheme.ERROR
					: sourceColor(target.sourceLayer()),
				!firstVisit
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

	private List<DependencyTreeWidget.Row> displayRows() {
		if (resolutionError != null) {
			return List.of(DependencyTreeWidget.Row.notice(
				Text.literal(resolutionError),
				CraftStudioTheme.ERROR
			));
		}
		if (asset.kind() == AssetKind.BLOCK && resolution == null) {
			return List.of(DependencyTreeWidget.Row.notice(
				Text.translatable("screen.craftstudio.asset_details.resolving"),
				CraftStudioTheme.INFORMATION
			));
		}
		return rows;
	}

	private String nodeTypeLabel(GraphNodeType type) {
		return switch (type) {
			case BLOCK -> "BLOCK";
			case ITEM -> "ITEM";
			case BLOCKSTATE_FILE -> "BLOCKSTATE";
			case CLIENT_ITEM_FILE -> "ITEM DEF";
			case MODEL_FILE -> "MODEL";
			case TEXTURE_FILE -> "TEXTURE";
			case TEXTURE_METADATA_FILE -> "METADATA";
			case ATLAS_FILE -> "ATLAS";
			case BUILTIN_MODEL -> "BUILT-IN";
			case SPECIAL_RENDERER -> "SPECIAL";
			case UNKNOWN_RESOURCE -> "UNKNOWN";
		};
	}

	private String relationshipLabel(GraphEdgeType type, String label) {
		String relationship = switch (type) {
			case HAS_BLOCKSTATE -> "Block appearance";
			case HAS_CLIENT_ITEM -> "Item representation";
			case SELECTS_MODEL -> "Selected model";
			case USES_MODEL -> "Uses model";
			case INHERITS_MODEL -> "Parent model";
			case USES_TEXTURE_VARIABLE -> "Texture variable";
			case RESOLVES_TEXTURE -> "Resolved texture";
			case USES_METADATA -> "Texture metadata";
			case REQUIRES_ATLAS -> "Required atlas";
			case SHARED_BY -> "Shared dependency";
			case HAS_VARIANT -> "Variant";
			case HAS_MULTIPART_CASE -> "Multipart case";
			case USES_SPECIAL_RENDERER -> "Special renderer";
		};
		return label.isBlank() ? relationship : relationship + "  ·  " + label;
	}

	private String sourceLabel(SourceLayer sourceLayer) {
		return switch (sourceLayer) {
			case VANILLA_BASE -> "VANILLA";
			case ACTIVE_PACK_STACK -> "PACK";
			case PROJECT -> "PROJECT";
			case GENERATED -> "GENERATED";
			case MISSING -> "MISSING";
		};
	}

	private int sourceColor(SourceLayer sourceLayer) {
		return switch (sourceLayer) {
			case PROJECT -> CraftStudioTheme.SUCCESS;
			case GENERATED -> CraftStudioTheme.INFORMATION;
			case MISSING -> CraftStudioTheme.ERROR;
			case VANILLA_BASE, ACTIVE_PACK_STACK -> CraftStudioTheme.TEXT_MUTED;
		};
	}

	private void drawLine(
		DrawContext drawContext,
		String labelKey,
		String value,
		int x,
		int y
	) {
		String line = Text.translatable(labelKey, value).getString();
		drawContext.drawTextWithShadow(
			textRenderer,
			Text.literal(middleTruncate(line, width - getMargin() - x)),
			x,
			y,
			CraftStudioTheme.TEXT_MUTED
		);
	}

	private String middleTruncate(String value, int availableWidth) {
		if (textRenderer.getWidth(value) <= availableWidth) {
			return value;
		}
		String ellipsis = "...";
		int sideWidth = Math.max(12, (availableWidth - textRenderer.getWidth(ellipsis)) / 2);
		String start = textRenderer.trimToWidth(value, sideWidth);
		String reversedEnd = textRenderer.trimToWidth(
			new StringBuilder(value).reverse().toString(),
			sideWidth
		);
		return start + ellipsis + new StringBuilder(reversedEnd).reverse();
	}

	private int getRowsY() {
		return height < 240 ? 80 : 104;
	}

	private int getMargin() {
		return width < 340 || height < 220 ? CraftStudioTheme.SPACE_2 : MARGIN;
	}

}
