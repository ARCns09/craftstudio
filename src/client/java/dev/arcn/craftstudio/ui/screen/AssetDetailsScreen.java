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
import java.util.Comparator;
import java.util.LinkedHashSet;
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
		if (!resolutionRequested) {
			resolutionRequested = true;
			MinecraftClient clientReference = client;
			var resolutionFuture = asset.kind() == AssetKind.BLOCK
				? context.resolveBlock(asset)
				: context.resolveItem(asset);
			resolutionFuture.whenComplete((result, error) -> clientReference.execute(() -> {
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

		drawContext.drawTextWithShadow(
			textRenderer,
			Text.translatable("screen.craftstudio.asset_details.dependencies"),
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
		for (DependencyGroup group : DependencyGroup.values()) {
			List<AssetGraphNode> groupNodes = result.graph().nodes().values().stream()
				.filter(node -> !node.id().equals(result.graph().rootNodeId()))
				.filter(node -> group.types().contains(node.type()))
				.sorted(Comparator.comparing(this::nodeSortKey))
				.toList();
			if (groupNodes.isEmpty()) {
				continue;
			}
			resultRows.add(DependencyTreeWidget.Row.section(
				Text.literal(group.title()),
				groupNodes.size()
			));
			for (AssetGraphNode node : groupNodes) {
				resultRows.add(DependencyTreeWidget.Row.dependency(
					Text.literal(nodeTitle(node)),
					Text.literal(nodeTypeLabel(node.type())),
					nodePath(node),
					Text.literal(dependencyReason(result, node)),
					Text.literal(sourceLabel(node.sourceLayer())),
					node.sourceLayer() == SourceLayer.MISSING
						? CraftStudioTheme.ERROR
						: sourceColor(node.sourceLayer())
				));
			}
		}
		if (!result.issues().isEmpty()) {
			resultRows.add(DependencyTreeWidget.Row.section(
				Text.translatable("screen.craftstudio.asset_details.issues"),
				result.issues().size()
			));
			for (ResolutionIssue issue : result.issues()) {
				int color = issue.severity() == ResolutionIssueSeverity.ERROR
					? CraftStudioTheme.ERROR
					: issue.severity() == ResolutionIssueSeverity.WARNING
						? CraftStudioTheme.WARNING
						: CraftStudioTheme.TEXT_MUTED;
				resultRows.add(DependencyTreeWidget.Row.notice(
					Text.literal("[" + issue.severity() + " " + issue.code() + "] " + issue.message()),
					color
				));
			}
		}
		return List.copyOf(resultRows);
	}

	private String dependencyReason(AssetResolutionResult result, AssetGraphNode node) {
		List<AssetGraphEdge> incoming = result.graph().edges().stream()
			.filter(edge -> edge.toNodeId().equals(node.id()))
			.toList();
		boolean hasResolvedTexture = incoming.stream()
			.anyMatch(edge -> edge.type() == GraphEdgeType.RESOLVES_TEXTURE);
		LinkedHashSet<String> reasons = new LinkedHashSet<>();
		for (AssetGraphEdge edge : incoming) {
			if (edge.type() == GraphEdgeType.USES_TEXTURE_VARIABLE && hasResolvedTexture) {
				continue;
			}
			AssetGraphNode sourceNode = result.graph().nodes().get(edge.fromNodeId());
			String reason = edgeReason(edge, sourceNode);
			if (!reason.isBlank()) {
				reasons.add(reason);
			}
		}
		if (reasons.isEmpty()) {
			return "Resolved dependency";
		}
		List<String> visibleReasons = List.copyOf(reasons);
		if (visibleReasons.size() <= 2) {
			return String.join(" • ", visibleReasons);
		}
		return visibleReasons.get(0)
			+ " • "
			+ visibleReasons.get(1)
			+ " • +"
			+ (visibleReasons.size() - 2)
			+ " more links";
	}

	private Text statusText() {
		if (resolutionError != null) {
			return Text.translatable("screen.craftstudio.asset_details.failed");
		}
		if (resolution == null) {
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
		return Text.translatable("screen.craftstudio.asset_details.resolving");
	}

	private List<DependencyTreeWidget.Row> displayRows() {
		if (resolutionError != null) {
			return List.of(DependencyTreeWidget.Row.notice(
				Text.literal(resolutionError),
				CraftStudioTheme.ERROR
			));
		}
		if (resolution == null) {
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

	private String edgeReason(AssetGraphEdge edge, AssetGraphNode sourceNode) {
		String sourceName = sourceNode == null ? "another dependency" : nodeTitle(sourceNode);
		return switch (edge.type()) {
			case HAS_BLOCKSTATE -> "Block appearance definition";
			case HAS_CLIENT_ITEM -> "Inventory appearance definition";
			case SELECTS_MODEL -> "Item branch: " + edge.label();
			case USES_MODEL -> edge.label();
			case INHERITS_MODEL -> "Parent of " + sourceName;
			case USES_TEXTURE_VARIABLE -> "Texture variable in " + sourceName;
			case RESOLVES_TEXTURE -> "Texture used by " + sourceName;
			case USES_METADATA -> "Animation metadata for " + sourceName;
			case REQUIRES_ATLAS -> "Atlas containing " + sourceName;
			case SHARED_BY -> "Shared by " + sourceName;
			case HAS_VARIANT -> "Block variant: " + edge.label();
			case HAS_MULTIPART_CASE -> "Multipart branch: " + edge.label();
			case USES_SPECIAL_RENDERER -> edge.label();
		};
	}

	private String nodeTitle(AssetGraphNode node) {
		if (node.type() == GraphNodeType.SPECIAL_RENDERER) {
			return node.attributes().getOrDefault("reason", "Special renderer");
		}
		if (node.type() == GraphNodeType.UNKNOWN_RESOURCE) {
			return node.attributes().getOrDefault("definition_type", "Unsupported resource");
		}
		if (node.packPath().isEmpty()) {
			return node.namespace() + ":" + node.logicalPath();
		}
		int separator = node.packPath().lastIndexOf('/');
		return separator < 0 ? node.packPath() : node.packPath().substring(separator + 1);
	}

	private String nodePath(AssetGraphNode node) {
		return node.packPath().isEmpty()
			? node.namespace() + ":" + node.logicalPath()
			: node.packPath();
	}

	private String nodeSortKey(AssetGraphNode node) {
		return nodePath(node).toLowerCase();
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

	private enum DependencyGroup {
		DEFINITIONS(
			"Definitions",
			Set.of(GraphNodeType.BLOCKSTATE_FILE, GraphNodeType.CLIENT_ITEM_FILE)
		),
		MODELS(
			"Models and branches",
			Set.of(GraphNodeType.MODEL_FILE, GraphNodeType.BUILTIN_MODEL)
		),
		TEXTURES(
			"Textures",
			Set.of(GraphNodeType.TEXTURE_FILE)
		),
		METADATA(
			"Metadata and atlases",
			Set.of(GraphNodeType.TEXTURE_METADATA_FILE, GraphNodeType.ATLAS_FILE)
		),
		SPECIAL(
			"Special or unsupported",
			Set.of(GraphNodeType.SPECIAL_RENDERER, GraphNodeType.UNKNOWN_RESOURCE)
		);

		private final String title;
		private final Set<GraphNodeType> types;

		DependencyGroup(String title, Set<GraphNodeType> types) {
			this.title = title;
			this.types = types;
		}

		private String title() {
			return title;
		}

		private Set<GraphNodeType> types() {
			return types;
		}
	}
}
