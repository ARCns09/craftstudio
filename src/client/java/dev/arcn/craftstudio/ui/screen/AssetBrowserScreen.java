package dev.arcn.craftstudio.ui.screen;

import dev.arcn.craftstudio.catalog.application.CatalogCategory;
import dev.arcn.craftstudio.catalog.application.CatalogIndex;
import dev.arcn.craftstudio.catalog.application.CatalogQuery;
import dev.arcn.craftstudio.catalog.application.CatalogSearchResult;
import dev.arcn.craftstudio.catalog.application.CatalogSort;
import dev.arcn.craftstudio.catalog.domain.AssetKind;
import dev.arcn.craftstudio.catalog.domain.CatalogAsset;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext;
import dev.arcn.craftstudio.client.bootstrap.CraftStudioClientContext.CatalogState;
import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class AssetBrowserScreen extends Screen {
	private static final Text TITLE = Text.translatable("screen.craftstudio.catalog.title");
	private static final int MARGIN = 16;
	private static final int ROW_HEIGHT = 24;
	private static final int BUTTON_HEIGHT = 20;

	private final CraftStudioClientContext context;
	private final Screen parent;

	private String searchText = "";
	private CatalogCategory category = CatalogCategory.ALL;
	private CatalogSort sort = CatalogSort.NAME;
	private String namespace = CatalogQuery.ALL_NAMESPACES;
	private int offset;
	private int pageSize = 1;
	private long catalogRevision = -1;
	private CatalogSearchResult searchResult = new CatalogSearchResult(List.of(), 0, 0);
	private TextFieldWidget searchField;

	public AssetBrowserScreen(CraftStudioClientContext context, Screen parent) {
		super(TITLE);
		this.context = context;
		this.parent = parent;
	}

	@Override
	protected void init() {
		context.startCatalogIndex();
		CatalogState state = context.catalogState();
		catalogRevision = state.revision();

		int contentX = MARGIN;
		int contentWidth = width - MARGIN * 2;
		int searchY = 34;
		searchField = new TextFieldWidget(
			textRenderer,
			contentX,
			searchY,
			contentWidth,
			20,
			Text.translatable("screen.craftstudio.catalog.search")
		);
		searchField.setMaxLength(128);
		searchField.setPlaceholder(Text.translatable("screen.craftstudio.catalog.search_placeholder"));
		searchField.setText(searchText);
		searchField.setChangedListener(value -> {
			searchText = value;
			offset = 0;
			clearAndInit();
		});
		addDrawableChild(searchField);

		int filterY = searchY + 28;
		int filterGap = CraftStudioTheme.SPACE_2;
		int filterWidth = (contentWidth - filterGap * 2) / 3;
		ButtonWidget categoryButton = ButtonWidget.builder(
			categoryLabel(),
			button -> {
				category = category.next();
				offset = 0;
				clearAndInit();
			}
		).dimensions(contentX, filterY, filterWidth, BUTTON_HEIGHT).build();
		categoryButton.active = state.ready();
		addDrawableChild(categoryButton);

		ButtonWidget namespaceButton = ButtonWidget.builder(
			namespaceLabel(),
			button -> {
				namespace = nextNamespace(state.index());
				offset = 0;
				clearAndInit();
			}
		).dimensions(
			contentX + filterWidth + filterGap,
			filterY,
			filterWidth,
			BUTTON_HEIGHT
		).build();
		namespaceButton.active = state.ready();
		addDrawableChild(namespaceButton);

		ButtonWidget sortButton = ButtonWidget.builder(
			sortLabel(),
			button -> {
				sort = sort.next();
				offset = 0;
				clearAndInit();
			}
		).dimensions(
			contentX + (filterWidth + filterGap) * 2,
			filterY,
			filterWidth,
			BUTTON_HEIGHT
		).build();
		sortButton.active = state.ready();
		addDrawableChild(sortButton);

		int rowsY = filterY + 30;
		int footerY = height - MARGIN - BUTTON_HEIGHT;
		pageSize = Math.max(1, (footerY - rowsY - CraftStudioTheme.SPACE_2) / ROW_HEIGHT);
		if (state.ready()) {
			searchResult = search(state.index());
			if (searchResult.assets().isEmpty() && searchResult.totalCount() > 0 && offset > 0) {
				offset = Math.max(0, ((searchResult.totalCount() - 1) / pageSize) * pageSize);
				searchResult = search(state.index());
			}
			for (int index = 0; index < searchResult.assets().size(); index++) {
				CatalogAsset asset = searchResult.assets().get(index);
				addDrawableChild(
					ButtonWidget.builder(
						assetLabel(asset),
						button -> client.setScreen(new AssetDetailsScreen(this, asset))
					).dimensions(contentX, rowsY + index * ROW_HEIGHT, contentWidth, BUTTON_HEIGHT).build()
				);
			}
		}

		int footerGap = CraftStudioTheme.SPACE_2;
		int footerButtonWidth = (contentWidth - footerGap * 2) / 3;
		ButtonWidget previousButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.previous"),
			button -> {
				offset = Math.max(0, offset - pageSize);
				clearAndInit();
			}
		).dimensions(contentX, footerY, footerButtonWidth, BUTTON_HEIGHT).build();
		previousButton.active = state.ready() && offset > 0;
		addDrawableChild(previousButton);

		addDrawableChild(
			ButtonWidget.builder(
				Text.translatable("screen.craftstudio.catalog.back"),
				button -> close()
			).dimensions(
				contentX + footerButtonWidth + footerGap,
				footerY,
				footerButtonWidth,
				BUTTON_HEIGHT
			).build()
		);

		ButtonWidget nextButton = ButtonWidget.builder(
			Text.translatable("screen.craftstudio.catalog.next"),
			button -> {
				offset += pageSize;
				clearAndInit();
			}
		).dimensions(
			contentX + (footerButtonWidth + footerGap) * 2,
			footerY,
			footerButtonWidth,
			BUTTON_HEIGHT
		).build();
		nextButton.active = state.ready()
			&& offset + searchResult.assets().size() < searchResult.totalCount();
		addDrawableChild(nextButton);

		setInitialFocus(searchField);
	}

	@Override
	public void tick() {
		if (catalogRevision != context.catalogState().revision()) {
			clearAndInit();
		}
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float deltaTicks) {
		CatalogState state = context.catalogState();
		drawContext.fill(0, 0, width, height, CraftStudioTheme.BACKGROUND);
		drawContext.fill(0, 0, width, 28, CraftStudioTheme.PANEL);
		drawContext.fill(0, 26, width, 28, CraftStudioTheme.ACCENT);
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			title,
			width / 2,
			CraftStudioTheme.SPACE_2,
			CraftStudioTheme.TEXT_PRIMARY
		);

		Text status = catalogStatus(state);
		int statusColor = state.error() != null
			? CraftStudioTheme.ERROR
			: state.ready() ? CraftStudioTheme.TEXT_MUTED : CraftStudioTheme.INFORMATION;
		drawContext.drawCenteredTextWithShadow(
			textRenderer,
			status,
			width / 2,
			height - MARGIN - BUTTON_HEIGHT - 12,
			statusColor
		);

		super.render(drawContext, mouseX, mouseY, deltaTicks);
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	private CatalogSearchResult search(CatalogIndex index) {
		return index.search(new CatalogQuery(
			searchText,
			category,
			namespace,
			sort,
			offset,
			pageSize
		));
	}

	private String nextNamespace(CatalogIndex index) {
		List<String> choices = new ArrayList<>(index.namespaces().size() + 1);
		choices.add(CatalogQuery.ALL_NAMESPACES);
		choices.addAll(index.namespaces());
		int currentIndex = choices.indexOf(namespace);
		return choices.get((currentIndex + 1) % choices.size());
	}

	private Text categoryLabel() {
		return Text.translatable(
			"screen.craftstudio.catalog.category",
			Text.translatable("screen.craftstudio.catalog.category." + category.name().toLowerCase())
		);
	}

	private Text namespaceLabel() {
		String value = namespace.equals(CatalogQuery.ALL_NAMESPACES)
			? Text.translatable("screen.craftstudio.catalog.namespace.all").getString()
			: namespace;
		return Text.translatable("screen.craftstudio.catalog.namespace", value);
	}

	private Text sortLabel() {
		return Text.translatable(
			"screen.craftstudio.catalog.sort",
			Text.translatable("screen.craftstudio.catalog.sort." + sort.name().toLowerCase())
		);
	}

	private Text assetLabel(CatalogAsset asset) {
		String type = Text.translatable(
			asset.kind() == AssetKind.BLOCK
				? "screen.craftstudio.catalog.type.block"
				: "screen.craftstudio.catalog.type.item"
		).getString();
		return Text.literal(type + " · " + asset.displayName() + " · " + asset.identifier());
	}

	private Text catalogStatus(CatalogState state) {
		if (state.error() != null) {
			return Text.literal(shortMessage(state.error()));
		}
		if (!state.ready()) {
			return Text.translatable("screen.craftstudio.catalog.indexing");
		}
		if (searchResult.totalCount() == 0) {
			return Text.translatable("screen.craftstudio.catalog.no_results");
		}
		int first = searchResult.offset() + 1;
		int last = searchResult.offset() + searchResult.assets().size();
		return Text.translatable(
			"screen.craftstudio.catalog.results",
			first,
			last,
			searchResult.totalCount(),
			state.index().size()
		);
	}

	private String shortMessage(String message) {
		return message.length() <= 96 ? message : message.substring(0, 93) + "...";
	}
}
