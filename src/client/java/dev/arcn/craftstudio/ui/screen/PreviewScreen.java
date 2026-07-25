package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.graph.domain.AssetResolutionResult;
import dev.arcn.craftstudio.preview.domain.PreviewMode;
import dev.arcn.craftstudio.preview.domain.PreviewScene;
import dev.arcn.craftstudio.preview.domain.PreviewScene.Variant;
import dev.arcn.craftstudio.preview.minecraft.PreviewTextureLibrary;
import dev.arcn.craftstudio.preview.ui.PreviewViewportWidget;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class PreviewScreen extends Screen {
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;
	private final AssetResolutionResult catalogResolution;
	private final PreviewScene scene;
	private PreviewMode mode;
	private int variantIndex;
	private boolean refreshing;
	private Text refreshError;
	private PreviewTextureLibrary textureLibrary;
	private PreviewViewportWidget viewport;

	public PreviewScreen(
		CraftStudioClientContext context,
		Screen parent,
		AssetResolutionResult catalogResolution,
		PreviewScene scene
	) {
		super(Text.translatable("screen.craftstudio.preview.title"));
		this.context = context;
		this.parent = parent;
		this.catalogResolution = catalogResolution;
		this.scene = scene;
		this.mode = scene.supports(PreviewMode.BLOCK) ? PreviewMode.BLOCK : PreviewMode.ITEM;
	}

	@Override
	protected void init() {
		if (textureLibrary == null) {
			textureLibrary = new PreviewTextureLibrary(client);
		}
		List<Variant> variants = variants();
		variantIndex = variants.isEmpty() ? 0 : Math.floorMod(variantIndex, variants.size());
		int margin = width < 360 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4;
		int contentWidth = width - margin * 2;
		int gap = CraftStudioTheme.SPACE_2;
		int thirdWidth = (contentWidth - gap * 2) / 3;
		int firstRowY = 34;
		int secondRowY = 58;
		int viewportY = 84;
		int footerY = height - margin - BUTTON_HEIGHT;
		int viewportBottom = footerY - 30;

		if (!variants.isEmpty()) {
			viewport = new PreviewViewportWidget(
				margin,
				viewportY,
				contentWidth,
				Math.max(1, viewportBottom - viewportY),
				client,
				textureLibrary,
				currentVariant()
			);
			addDrawableChild(viewport);
		}

		ButtonWidget modeButton = ButtonWidget.builder(
			modeLabel(),
			button -> switchMode()
		).dimensions(margin, firstRowY, thirdWidth, BUTTON_HEIGHT).build();
		modeButton.active = !refreshing
			&& scene.supports(PreviewMode.BLOCK)
			&& scene.supports(PreviewMode.ITEM);
		addDrawableChild(modeButton);
		ButtonWidget previous = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.preview.previous"),
			button -> selectRelative(-1)
		).dimensions(
			margin + thirdWidth + gap,
			firstRowY,
			thirdWidth,
			BUTTON_HEIGHT
		).build();
		previous.active = !refreshing && variants.size() > 1;
		addDrawableChild(previous);
		ButtonWidget next = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.preview.next"),
			button -> selectRelative(1)
		).dimensions(
			margin + (thirdWidth + gap) * 2,
			firstRowY,
			contentWidth - thirdWidth * 2 - gap * 2,
			BUTTON_HEIGHT
		).build();
		next.active = !refreshing && variants.size() > 1;
		addDrawableChild(next);

		List<String> properties = propertyNames();
		if (!properties.isEmpty()) {
			int visiblePropertyCount = Math.min(3, properties.size());
			int propertyWidth = (contentWidth - gap * (visiblePropertyCount - 1))
				/ visiblePropertyCount;
			for (int index = 0; index < visiblePropertyCount; index++) {
				String property = properties.get(index);
				ButtonWidget propertyButton = ButtonWidget.builder(
					propertyLabel(property),
					button -> cycleProperty(property)
				).dimensions(
					margin + index * (propertyWidth + gap),
					secondRowY,
					index == visiblePropertyCount - 1
						? contentWidth - index * (propertyWidth + gap)
						: propertyWidth,
					BUTTON_HEIGHT
				).build();
				propertyButton.active = !refreshing && propertyValues(property).size() > 1;
				addDrawableChild(propertyButton);
			}
		}

		ButtonWidget reset = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.preview.reset_camera"),
			button -> {
				if (viewport != null) {
					viewport.resetCamera();
				}
			}
		).dimensions(margin, footerY, thirdWidth, BUTTON_HEIGHT).build();
		reset.active = viewport != null && !refreshing;
		addDrawableChild(reset);
		ButtonWidget refresh = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.preview.refresh"),
			button -> refresh()
		).dimensions(
			margin + thirdWidth + gap,
			footerY,
			thirdWidth,
			BUTTON_HEIGHT
		).build();
		refresh.active = !refreshing;
		addDrawableChild(refresh);
		addDrawableChild(ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.back"),
			button -> close()
		).dimensions(
			margin + (thirdWidth + gap) * 2,
			footerY,
			contentWidth - thirdWidth * 2 - gap * 2,
			BUTTON_HEIGHT
		).build());

		if (viewport != null) {
			setInitialFocus(viewport);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		context.fill(0, 0, width, 28, CraftStudioTheme.PANEL);
		context.fill(0, 26, width, 28, CraftStudioTheme.ACCENT);
		String heading = scene.root().identifier() + " · " + currentLabel();
		context.drawCenteredTextWithShadow(
			textRenderer,
			Text.literal(textRenderer.trimToWidth(heading, Math.max(1, width - 32))),
			width / 2,
			CraftStudioTheme.SPACE_2,
			CraftStudioTheme.TEXT_PRIMARY
		);
		if (propertyNames().isEmpty()) {
			context.drawCenteredTextWithShadow(
				textRenderer,
				Text.literal(currentLabel()),
				width / 2,
				65,
				CraftStudioTheme.TEXT_MUTED
			);
		}
		Text status = statusText();
		context.drawCenteredTextWithShadow(
			textRenderer,
			status,
			width / 2,
			height - (width < 360 ? CraftStudioTheme.SPACE_2 : CraftStudioTheme.SPACE_4)
				- BUTTON_HEIGHT - 17,
			statusColor()
		);
		super.render(context, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (!refreshing && client != null) {
			client.setScreen(parent);
		}
	}

	@Override
	public void removed() {
		if (textureLibrary != null) {
			textureLibrary.close();
			textureLibrary = null;
		}
	}

	private List<Variant> variants() {
		return scene.variants(mode);
	}

	private Variant currentVariant() {
		return variants().get(Math.floorMod(variantIndex, variants().size()));
	}

	private String currentLabel() {
		return variants().isEmpty()
			? "Preview unavailable"
			: currentVariant().label();
	}

	private Text modeLabel() {
		return Text.translatable(
			mode == PreviewMode.BLOCK
				? "screen.craftstudio.preview.mode_block"
				: "screen.craftstudio.preview.mode_item"
		);
	}

	private void switchMode() {
		mode = mode == PreviewMode.BLOCK ? PreviewMode.ITEM : PreviewMode.BLOCK;
		variantIndex = 0;
		clearAndInit();
	}

	private void selectRelative(int amount) {
		if (variants().isEmpty()) {
			return;
		}
		variantIndex = Math.floorMod(variantIndex + amount, variants().size());
		clearAndInit();
	}

	private List<String> propertyNames() {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (Variant variant : variants()) {
			names.addAll(variant.properties().keySet());
		}
		return List.copyOf(names);
	}

	private List<String> propertyValues(String property) {
		LinkedHashSet<String> values = new LinkedHashSet<>();
		for (Variant variant : variants()) {
			String value = variant.properties().get(property);
			if (value != null) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

	private Text propertyLabel(String property) {
		String value = variants().isEmpty()
			? "-"
			: currentVariant().properties().getOrDefault(property, "-");
		return Text.literal(friendly(property) + ": " + value);
	}

	private void cycleProperty(String property) {
		List<String> values = propertyValues(property);
		if (values.size() < 2 || variants().isEmpty()) {
			return;
		}
		Map<String, String> desired = new LinkedHashMap<>(currentVariant().properties());
		String current = desired.get(property);
		int next = Math.floorMod(values.indexOf(current) + 1, values.size());
		desired.put(property, values.get(next));
		for (int index = 0; index < variants().size(); index++) {
			Variant candidate = variants().get(index);
			boolean matches = desired.entrySet().stream()
				.allMatch(entry -> entry.getValue().equals(candidate.properties().get(entry.getKey())));
			if (matches) {
				variantIndex = index;
				clearAndInit();
				return;
			}
		}
	}

	private void refresh() {
		refreshing = true;
		refreshError = null;
		clearAndInit();
		context.refreshPreview(catalogResolution).whenCompleteAsync((refreshed, failure) -> {
			refreshing = false;
			if (failure == null) {
				client.setScreen(new PreviewScreen(
					context,
					parent,
					catalogResolution,
					refreshed
				));
			} else {
				refreshError = Text.literal(CraftStudioClientContext.userMessage(failure));
				clearAndInit();
			}
		}, client);
	}

	private Text statusText() {
		if (refreshing) {
			return Text.translatable("screen.craftstudio.preview.refreshing");
		}
		if (refreshError != null) {
			return refreshError;
		}
		if (variants().isEmpty()) {
			String diagnostic = scene.diagnostics().isEmpty()
				? "No supported standard JSON model."
				: scene.diagnostics().getFirst();
			return Text.literal(shorten(diagnostic));
		}
		Variant variant = currentVariant();
		long missing = variant.missingFaceCount();
		if (missing > 0) {
			return Text.translatable("screen.craftstudio.preview.missing_faces", missing);
		}
		List<String> diagnostics = new ArrayList<>(scene.diagnostics());
		diagnostics.addAll(variant.diagnostics());
		if (!diagnostics.isEmpty()) {
			return Text.literal(shorten(diagnostics.getFirst()));
		}
		long projectTextures = variant.textures().values().stream()
			.filter(texture -> texture.sourceLayer()
				== dev.arcn.craftstudio.resource.domain.SourceLayer.PROJECT)
			.count();
		return Text.translatable(
			"screen.craftstudio.preview.ready",
			variant.faces().size(),
			projectTextures
		);
	}

	private int statusColor() {
		if (refreshError != null || variants().isEmpty()) {
			return CraftStudioTheme.ERROR;
		}
		if (refreshing) {
			return CraftStudioTheme.INFORMATION;
		}
		return currentVariant().missingFaceCount() > 0
			? CraftStudioTheme.WARNING
			: CraftStudioTheme.SUCCESS;
	}

	private String friendly(String value) {
		if (value.isEmpty()) {
			return value;
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
	}

	private String shorten(String value) {
		return value.length() <= 100 ? value : value.substring(0, 97) + "...";
	}
}
