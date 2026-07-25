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
import dev.arcn.craftstudio.project.domain.BundleOperationResult;
import dev.arcn.craftstudio.project.domain.CopyPlan;
import dev.arcn.craftstudio.project.domain.RemovalPlan;
import dev.arcn.craftstudio.project.domain.SelectionMode;
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
	private boolean operationBusy;
	private Text operationMessage;
	private boolean operationFailed;

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
		boolean showProjectActions = context.activeProject() != null;
		int firstActionY = footerY - 48;
		int secondActionY = footerY - 24;
		int statusY = showProjectActions ? firstActionY - 12 : secondActionY - 12;
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

		int gap = CraftStudioTheme.SPACE_2;
		if (showProjectActions) {
			int thirdWidth = (contentWidth - gap * 2) / 3;
			ButtonWidget complete = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.bundle.add_complete"),
				button -> beginPlan(SelectionMode.COMPLETE)
			).dimensions(margin, firstActionY, thirdWidth, BUTTON_HEIGHT).build();
			complete.active = canMaterialize();
			addDrawableChild(complete);
			ButtonWidget unique = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.bundle.add_unique"),
				button -> beginPlan(SelectionMode.UNIQUE_ONLY)
			).dimensions(
				margin + thirdWidth + gap,
				firstActionY,
				thirdWidth,
				BUTTON_HEIGHT
			).build();
			unique.active = canMaterialize();
			addDrawableChild(unique);
			ButtonWidget custom = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.bundle.choose_files"),
				button -> beginPlan(SelectionMode.CUSTOM)
			).dimensions(
				margin + (thirdWidth + gap) * 2,
				firstActionY,
				contentWidth - thirdWidth * 2 - gap * 2,
				BUTTON_HEIGHT
			).build();
			custom.active = canMaterialize();
			addDrawableChild(custom);

			ButtonWidget preview = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.preview.open"),
				button -> beginPreview()
			).dimensions(margin, secondActionY, thirdWidth, BUTTON_HEIGHT).build();
			preview.active = canPreview();
			addDrawableChild(preview);
			ButtonWidget restore = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.bundle.restore"),
				button -> confirmRestore()
			).dimensions(
				margin + thirdWidth + gap,
				secondActionY,
				thirdWidth,
				BUTTON_HEIGHT
			).build();
			restore.active = canChangeSelectedRoot();
			addDrawableChild(restore);
			ButtonWidget remove = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.bundle.remove"),
				button -> beginRemovalPlan()
			).dimensions(
				margin + (thirdWidth + gap) * 2,
				secondActionY,
				contentWidth - thirdWidth * 2 - gap * 2,
				BUTTON_HEIGHT
			).build();
			remove.active = canChangeSelectedRoot();
			addDrawableChild(remove);
		} else {
			ButtonWidget preview = ButtonWidget.builder(
				Text.translatable("screen.craftstudio.preview.open"),
				button -> beginPreview()
			).dimensions(margin, secondActionY, contentWidth, BUTTON_HEIGHT).build();
			preview.active = canPreview();
			addDrawableChild(preview);
		}
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
			height - margin - BUTTON_HEIGHT - (context.activeProject() == null ? 36 : 60),
			operationFailed || resolutionError != null
				? CraftStudioTheme.ERROR
				: operationBusy ? CraftStudioTheme.INFORMATION : CraftStudioTheme.TEXT_MUTED
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
		if (operationMessage != null) {
			return operationMessage;
		}
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

	void reviewPlan(CopyPlan plan) {
		operationBusy = false;
		if (!plan.conflicts().isEmpty()) {
			client.setScreen(new BundleConflictScreen(context, this, plan));
			return;
		}
		applyPlan(plan, Set.of());
	}

	void applyPlan(CopyPlan plan, Set<String> replacements) {
		client.setScreen(this);
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.bundle.copying");
		clearAndInit();
		context.addToProject(plan, replacements).whenCompleteAsync((result, failure) -> {
			operationBusy = false;
			if (failure == null) {
				showSuccess(Text.translatable(
					"screen.craftstudio.bundle.added",
					result.copiedFiles(),
					result.keptFiles()
				));
			} else {
				showFailure(failure);
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private void beginPlan(SelectionMode mode) {
		if (!canMaterialize()) {
			return;
		}
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.bundle.planning");
		clearAndInit();
		Set<String> customPaths = mode == SelectionMode.CUSTOM
			? resolution.graph().nodes().values().stream()
				.map(AssetGraphNode::packPath)
				.filter(path -> !path.isEmpty())
				.collect(java.util.stream.Collectors.toSet())
			: Set.of();
		context.createCopyPlan(resolution, mode, customPaths)
			.whenCompleteAsync((plan, failure) -> {
				operationBusy = false;
				if (failure != null) {
					showFailure(failure);
					if (client.currentScreen == this) {
						clearAndInit();
					}
				} else if (mode == SelectionMode.CUSTOM) {
					operationMessage = null;
					client.setScreen(new BundleSelectionScreen(context, this, resolution, plan));
				} else {
					operationMessage = null;
					reviewPlan(plan);
				}
			}, client);
	}

	private void beginPreview() {
		if (!canPreview()) {
			return;
		}
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.preview.preparing");
		clearAndInit();
		context.createPreview(resolution).whenCompleteAsync((scene, failure) -> {
			operationBusy = false;
			if (failure == null) {
				operationMessage = null;
				client.setScreen(new PreviewScreen(context, this, resolution, scene));
			} else {
				showFailure(failure);
				if (client.currentScreen == this) {
					clearAndInit();
				}
			}
		}, client);
	}

	private void confirmRestore() {
		List<String> files = resolvedPackPaths();
		client.setScreen(new BundleConfirmationScreen(
			this,
			Text.translatable("screen.craftstudio.bundle.restore_title"),
			Text.translatable("screen.craftstudio.bundle.restore_warning", files.size()),
			files,
			this::restoreVanilla
		));
	}

	private void restoreVanilla() {
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.bundle.restoring");
		clearAndInit();
		context.restoreVanilla(resolution).whenCompleteAsync((result, failure) -> {
			operationBusy = false;
			if (failure == null) {
				showSuccess(Text.translatable(
					"screen.craftstudio.bundle.restored",
					result.copiedFiles()
				));
			} else {
				showFailure(failure);
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private void beginRemovalPlan() {
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.bundle.planning_removal");
		clearAndInit();
		context.createRemovalPlan(resolution).whenCompleteAsync((plan, failure) -> {
			operationBusy = false;
			if (failure != null) {
				showFailure(failure);
				clearAndInit();
				return;
			}
			operationMessage = null;
			List<String> summary = new ArrayList<>();
			summary.add("Remove " + plan.removableFiles().size() + " exclusive vanilla files");
			summary.add("Keep " + plan.sharedFiles().size() + " files shared with other roots");
			summary.add("Keep " + plan.modifiedFiles().size() + " edited or custom files");
			summary.addAll(plan.removableFiles().stream()
				.map(path -> "REMOVE  " + path.packPath())
				.toList());
			summary.addAll(plan.sharedFiles().stream()
				.map(path -> "KEEP SHARED  " + path.packPath())
				.toList());
			summary.addAll(plan.modifiedFiles().stream()
				.map(path -> "KEEP EDITED  " + path.packPath())
				.toList());
			client.setScreen(new BundleConfirmationScreen(
				this,
				Text.translatable("screen.craftstudio.bundle.remove_title"),
				Text.translatable("screen.craftstudio.bundle.remove_warning"),
				summary,
				() -> removeRoot(plan)
			));
		}, client);
	}

	private void removeRoot(RemovalPlan plan) {
		operationBusy = true;
		operationFailed = false;
		operationMessage = Text.translatable("screen.craftstudio.bundle.removing");
		clearAndInit();
		context.removeRoot(plan).whenCompleteAsync((result, failure) -> {
			operationBusy = false;
			if (failure == null) {
				showSuccess(Text.translatable(
					"screen.craftstudio.bundle.removed",
					result.removedFiles(),
					result.keptFiles()
				));
			} else {
				showFailure(failure);
			}
			if (client.currentScreen == this) {
				clearAndInit();
			}
		}, client);
	}

	private boolean canMaterialize() {
		return !operationBusy
			&& resolution != null
			&& !resolution.hasErrors();
	}

	private boolean canPreview() {
		return !operationBusy && resolution != null;
	}

	private boolean canChangeSelectedRoot() {
		return canMaterialize() && context.isSelectedRoot(asset);
	}

	private List<String> resolvedPackPaths() {
		return resolution.graph().nodes().values().stream()
			.map(AssetGraphNode::packPath)
			.filter(path -> !path.isEmpty())
			.distinct()
			.sorted()
			.toList();
	}

	private void showSuccess(Text message) {
		operationFailed = false;
		operationMessage = message;
	}

	private void showFailure(Throwable failure) {
		operationFailed = true;
		operationMessage = Text.literal(CraftStudioClientContext.userMessage(failure));
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
