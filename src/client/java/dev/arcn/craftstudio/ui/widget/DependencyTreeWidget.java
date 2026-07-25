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
	private static final int ROW_HEIGHT = 42;
	private static final int SECTION_BACKGROUND = 0xFF151A20;
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
			boolean hovered = row.type() == RowType.DEPENDENCY
				&& mouseX >= getX() + PADDING
				&& mouseX < contentRight
				&& mouseY >= rowY
				&& mouseY < rowY + ROW_HEIGHT - 4;
			if (hovered) {
				hoveredIndex = index;
			}
			switch (row.type()) {
				case SECTION -> renderSection(context, row, rowY, contentRight);
				case DEPENDENCY -> renderDependency(context, row, rowY, contentRight, index, hovered);
				case NOTICE -> renderNotice(context, row, rowY, contentRight);
			}
		}
		context.disableScissor();
		if (overflows()) {
			drawScrollbar(context, mouseX, mouseY);
		}
		if (hoveredIndex >= 0) {
			Row hoveredRow = rows.get(hoveredIndex);
			if (!hoveredRow.path().isEmpty()) {
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
		return ROW_HEIGHT;
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}

	private void renderSection(DrawContext context, Row row, int rowY, int contentRight) {
		context.fill(
			getX() + PADDING,
			rowY + 6,
			contentRight,
			rowY + ROW_HEIGHT - 6,
			SECTION_BACKGROUND
		);
		context.fill(
			getX() + PADDING,
			rowY + 6,
			getX() + PADDING + 3,
			rowY + ROW_HEIGHT - 6,
			CraftStudioTheme.ACCENT
		);
		context.drawTextWithShadow(
			textRenderer,
			row.title(),
			getX() + PADDING + 11,
			rowY + 12,
			CraftStudioTheme.TEXT_PRIMARY
		);
		String count = row.reason().getString();
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(count),
			contentRight - textRenderer.getWidth(count) - 8,
			rowY + 12,
			CraftStudioTheme.TEXT_MUTED
		);
	}

	private void renderDependency(
		DrawContext context,
		Row row,
		int rowY,
		int contentRight,
		int index,
		boolean hovered
	) {
		int left = getX() + PADDING;
		context.fill(
			left,
			rowY,
			contentRight,
			rowY + ROW_HEIGHT - 4,
			hovered
				? ROW_HOVER
				: index % 2 == 0 ? ROW_BACKGROUND : ROW_BACKGROUND_ALTERNATE
		);

		int textX = left + 8;
		int badgeWidth = textRenderer.getWidth(row.badge()) + 8;
		context.fill(textX, rowY + 4, textX + badgeWidth, rowY + 15, BADGE_BACKGROUND);
		context.drawTextWithShadow(
			textRenderer,
			row.badge(),
			textX + 4,
			rowY + 5,
			CraftStudioTheme.ACCENT
		);

		int sourceWidth = textRenderer.getWidth(row.source()) + 8;
		int sourceX = contentRight - sourceWidth - 6;
		context.fill(sourceX, rowY + 4, contentRight - 6, rowY + 15, BADGE_BACKGROUND);
		context.drawTextWithShadow(
			textRenderer,
			row.source(),
			sourceX + 4,
			rowY + 5,
			row.color()
		);

		int titleX = textX + badgeWidth + 7;
		String title = middleTruncate(
			row.title().getString(),
			Math.max(1, sourceX - titleX - 7)
		);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(title),
			titleX,
			rowY + 5,
			CraftStudioTheme.TEXT_PRIMARY
		);

		String path = middleTruncate(row.path(), contentRight - textX - 8);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(path),
			textX,
			rowY + 17,
			CraftStudioTheme.TEXT_MUTED
		);

		String reason = "Needed for: " + row.reason().getString();
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(middleTruncate(reason, contentRight - textX - 8)),
			textX,
			rowY + 29,
			CraftStudioTheme.INFORMATION
		);
	}

	private void renderNotice(DrawContext context, Row row, int rowY, int contentRight) {
		context.fill(
			getX() + PADDING,
			rowY + 2,
			contentRight,
			rowY + ROW_HEIGHT - 4,
			ROW_BACKGROUND
		);
		String visible = middleTruncate(
			row.title().getString(),
			contentRight - getX() - PADDING * 4
		);
		context.drawTextWithShadow(
			textRenderer,
			Text.literal(visible),
			getX() + PADDING * 2,
			rowY + 15,
			row.color()
		);
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

	private enum RowType {
		SECTION,
		DEPENDENCY,
		NOTICE
	}

	public record Row(
		RowType type,
		Text title,
		Text badge,
		String path,
		Text reason,
		Text source,
		int color
	) {
		public Row {
			type = Objects.requireNonNull(type, "type");
			title = Objects.requireNonNull(title, "title");
			badge = Objects.requireNonNull(badge, "badge");
			path = Objects.requireNonNull(path, "path");
			reason = Objects.requireNonNull(reason, "reason");
			source = Objects.requireNonNull(source, "source");
		}

		public static Row section(Text title, int count) {
			String countLabel = count == 1 ? "1 entry" : count + " entries";
			return new Row(
				RowType.SECTION,
				title,
				Text.empty(),
				"",
				Text.literal(countLabel),
				Text.empty(),
				CraftStudioTheme.TEXT_PRIMARY
			);
		}

		public static Row dependency(
			Text title,
			Text badge,
			String path,
			Text reason,
			Text source,
			int color
		) {
			return new Row(RowType.DEPENDENCY, title, badge, path, reason, source, color);
		}

		public static Row notice(Text message, int color) {
			return new Row(
				RowType.NOTICE,
				message,
				Text.empty(),
				"",
				Text.empty(),
				Text.empty(),
				color
			);
		}
	}
}
