package dev.arcn.craftstudio.ui.widget;

import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class DependencyTreeWidget extends ScrollableWidget {
	private static final int PADDING = 4;
	private static final int ROW_HEIGHT = 30;
	private static final int INDENT_WIDTH = 12;
	private static final int GUIDE_COLOR = 0x6645C9A5;
	private static final int ROW_BACKGROUND = 0xFF20262E;
	private static final int ROW_BACKGROUND_ALTERNATE = 0xFF1D2229;
	private static final int ROW_HOVER = 0xFF29323C;
	private static final int BADGE_BACKGROUND = 0xFF303943;

	private final TextRenderer textRenderer;
	private final List<Row> rows;

	public DependencyTreeWidget(
		int x,
		int y,
		int width,
		int height,
		Text message,
		TextRenderer textRenderer,
		List<Row> rows
	) {
		super(x, y, width, Math.max(1, height), message);
		this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
		this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(getX(), getY(), getRight(), getBottom(), CraftStudioTheme.PANEL);
		context.enableScissor(getX(), getY(), getRight(), getBottom());
		int firstIndex = Math.max(0, (int) getScrollY() / ROW_HEIGHT);
		int lastIndex = Math.min(
			rows.size(),
			(int) Math.ceil((getScrollY() + getHeight()) / ROW_HEIGHT) + 1
		);
		int contentRight = getRight() - PADDING - (overflows() ? SCROLLBAR_WIDTH + 2 : 0);
		int hoveredIndex = -1;
		for (int index = firstIndex; index < lastIndex; index++) {
			Row row = rows.get(index);
			int rowY = getY() + PADDING + index * ROW_HEIGHT - (int) getScrollY();
			boolean hovered = mouseX >= getX() + PADDING
				&& mouseX < contentRight
				&& mouseY >= rowY
				&& mouseY < rowY + ROW_HEIGHT - 2;
			if (hovered) {
				hoveredIndex = index;
			}
			context.fill(
				getX() + PADDING,
				rowY,
				contentRight,
				rowY + ROW_HEIGHT - 2,
				hovered
					? ROW_HOVER
					: index % 2 == 0 ? ROW_BACKGROUND : ROW_BACKGROUND_ALTERNATE
			);
			if (row.notice()) {
				renderNotice(context, row, rowY, contentRight);
			} else {
				renderDependency(context, row, rowY, contentRight);
			}
		}
		context.disableScissor();
		if (overflows()) {
			drawScrollbar(context, mouseX, mouseY);
		}
		if (hoveredIndex >= 0) {
			Row hoveredRow = rows.get(hoveredIndex);
			if (!hoveredRow.path().isEmpty()
				&& textRenderer.getWidth(hoveredRow.path()) > availablePathWidth(hoveredRow, contentRight)) {
				context.drawTooltip(textRenderer, Text.literal(hoveredRow.path()), mouseX, mouseY);
			}
		}
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_UP) {
			setScrollY(getScrollY() - ROW_HEIGHT);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_DOWN) {
			setScrollY(getScrollY() + ROW_HEIGHT);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_PAGE_UP) {
			setScrollY(getScrollY() - getHeight());
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
			setScrollY(getScrollY() + getHeight());
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_HOME) {
			setScrollY(0);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_END) {
			setScrollY(getMaxScrollY());
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	protected int getContentsHeightWithPadding() {
		return PADDING * 2 + rows.size() * ROW_HEIGHT;
	}

	@Override
	protected double getDeltaYPerScroll() {
		return ROW_HEIGHT * 1.5;
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}

	private void renderDependency(DrawContext context, Row row, int rowY, int contentRight) {
		int depth = Math.min(row.depth(), 8);
		int treeX = getX() + PADDING + 6;
		for (int level = 0; level < depth; level++) {
			int guideX = treeX + level * INDENT_WIDTH;
			context.fill(guideX, rowY, guideX + 1, rowY + ROW_HEIGHT - 2, GUIDE_COLOR);
		}
		int branchX = treeX + depth * INDENT_WIDTH;
		context.fill(branchX, rowY, branchX + 1, rowY + ROW_HEIGHT / 2, GUIDE_COLOR);
		context.fill(branchX, rowY + ROW_HEIGHT / 2, branchX + 7, rowY + ROW_HEIGHT / 2 + 1, GUIDE_COLOR);

		int textX = branchX + 10;
		int badgeWidth = textRenderer.getWidth(row.kind()) + 8;
		context.fill(textX, rowY + 3, textX + badgeWidth, rowY + 14, BADGE_BACKGROUND);
		context.drawTextWithShadow(
			textRenderer,
			row.kind(),
			textX + 4,
			rowY + 4,
			CraftStudioTheme.ACCENT
		);

		int sourceWidth = textRenderer.getWidth(row.source()) + 8;
		int sourceX = contentRight - sourceWidth - 4;
		context.fill(sourceX, rowY + 3, contentRight - 4, rowY + 14, BADGE_BACKGROUND);
		context.drawTextWithShadow(
			textRenderer,
			row.source(),
			sourceX + 4,
			rowY + 4,
			row.color()
		);

		int relationX = textX + badgeWidth + 6;
		int relationWidth = Math.max(1, sourceX - relationX - 6);
		String relation = middleTruncate(row.relationship().getString(), relationWidth);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(relation),
			relationX,
			rowY + 4,
			CraftStudioTheme.TEXT_PRIMARY
		);

		String suffix = row.repeated() ? "  ↩ linked above" : "";
		int pathWidth = Math.max(1, contentRight - textX - 6);
		String path = middleTruncate(row.path() + suffix, pathWidth);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(path),
			textX,
			rowY + 17,
			row.repeated() ? CraftStudioTheme.INFORMATION : CraftStudioTheme.TEXT_MUTED
		);
	}

	private void renderNotice(DrawContext context, Row row, int rowY, int contentRight) {
		String visible = middleTruncate(
			row.relationship().getString(),
			contentRight - getX() - PADDING * 3
		);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(visible),
			getX() + PADDING * 2,
			rowY + 10,
			row.color()
		);
	}

	private int availablePathWidth(Row row, int contentRight) {
		int depth = Math.min(row.depth(), 8);
		int textX = getX() + PADDING + 16 + depth * INDENT_WIDTH;
		return Math.max(1, contentRight - textX - 6);
	}

	private String middleTruncate(String value, int availableWidth) {
		if (textRenderer.getWidth(value) <= availableWidth) {
			return value;
		}
		String ellipsis = "...";
		if (availableWidth <= textRenderer.getWidth(ellipsis)) {
			return textRenderer.trimToWidth(value, Math.max(1, availableWidth));
		}
		int sideWidth = Math.max(12, (availableWidth - textRenderer.getWidth(ellipsis)) / 2);
		String start = textRenderer.trimToWidth(value, sideWidth);
		String reversedEnd = textRenderer.trimToWidth(
			new StringBuilder(value).reverse().toString(),
			sideWidth
		);
		return start + ellipsis + new StringBuilder(reversedEnd).reverse();
	}

	public record Row(
		int depth,
		Text kind,
		Text relationship,
		String path,
		Text source,
		int color,
		boolean repeated,
		boolean notice
	) {
		public Row {
			if (depth < 0) {
				throw new IllegalArgumentException("depth cannot be negative.");
			}
			kind = Objects.requireNonNull(kind, "kind");
			relationship = Objects.requireNonNull(relationship, "relationship");
			path = Objects.requireNonNull(path, "path");
			source = Objects.requireNonNull(source, "source");
		}

		public static Row dependency(
			int depth,
			Text kind,
			Text relationship,
			String path,
			Text source,
			int color,
			boolean repeated
		) {
			return new Row(depth, kind, relationship, path, source, color, repeated, false);
		}

		public static Row notice(Text message, int color) {
			return new Row(0, Text.empty(), message, "", Text.empty(), color, false, true);
		}
	}
}
