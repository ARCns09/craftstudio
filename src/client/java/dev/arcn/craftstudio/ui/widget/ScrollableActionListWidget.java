package dev.arcn.craftstudio.ui.widget;

import dev.arcn.craftstudio.ui.theme.CraftStudioTheme;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ScrollableActionListWidget<T> extends ScrollableWidget {
	private static final int PADDING = 3;

	private final TextRenderer textRenderer;
	private final int rowHeight;
	private final Consumer<T> action;
	private final List<Row<T>> rows;
	private int selectedIndex = -1;

	public ScrollableActionListWidget(
		int x,
		int y,
		int width,
		int height,
		int rowHeight,
		Text message,
		TextRenderer textRenderer,
		List<Row<T>> rows,
		Consumer<T> action
	) {
		super(x, y, width, Math.max(1, height), message);
		if (rowHeight < 12) {
			throw new IllegalArgumentException("rowHeight must be at least 12.");
		}
		this.rowHeight = rowHeight;
		this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
		this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
		this.action = Objects.requireNonNull(action, "action");
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(getX(), getY(), getRight(), getBottom(), CraftStudioTheme.PANEL);
		context.enableScissor(getX(), getY(), getRight(), getBottom());
		int firstIndex = Math.max(0, (int) getScrollY() / rowHeight);
		int lastIndex = Math.min(
			rows.size(),
			(int) Math.ceil((getScrollY() + getHeight()) / rowHeight) + 1
		);
		int contentWidth = getWidth() - PADDING * 2 - (overflows() ? SCROLLBAR_WIDTH + 2 : 0);
		for (int index = firstIndex; index < lastIndex; index++) {
			Row<T> row = rows.get(index);
			int rowY = getY() + PADDING + index * rowHeight - (int) getScrollY();
			boolean hoveredRow = mouseX >= getX() + PADDING
				&& mouseX < getX() + PADDING + contentWidth
				&& mouseY >= rowY
				&& mouseY < rowY + rowHeight - 2;
			int background = index == selectedIndex
				? CraftStudioTheme.ACCENT
				: hoveredRow && row.active() ? 0xFF343A46 : 0xFF252A33;
			context.fill(
				getX() + PADDING,
				rowY,
				getX() + PADDING + contentWidth,
				rowY + rowHeight - 2,
				background
			);
			String label = middleTruncate(row.label().getString(), contentWidth - 8);
			context.drawTextWithShadow(
				textRenderer,
				Text.literal(label),
				getX() + PADDING + 4,
				rowY + Math.max(2, (rowHeight - textRenderer.fontHeight) / 2 - 1),
				row.active() ? CraftStudioTheme.TEXT_PRIMARY : CraftStudioTheme.TEXT_MUTED
			);
		}
		context.disableScissor();
		if (overflows()) {
			drawScrollbar(context, mouseX, mouseY);
		}
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		if (isInScrollbar(click.x(), click.y())) {
			return;
		}
		int index = rowAt(click.y());
		if (index >= 0 && index < rows.size()) {
			selectedIndex = index;
			Row<T> row = rows.get(index);
			if (row.active()) {
				action.accept(row.value());
			}
		}
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (rows.isEmpty()) {
			return false;
		}
		if (input.key() == GLFW.GLFW_KEY_UP) {
			moveSelection(-1);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_DOWN) {
			moveSelection(1);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_PAGE_UP) {
			moveSelection(-Math.max(1, getHeight() / rowHeight));
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
			moveSelection(Math.max(1, getHeight() / rowHeight));
			return true;
		}
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)
			&& selectedIndex >= 0
			&& rows.get(selectedIndex).active()) {
			action.accept(rows.get(selectedIndex).value());
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	protected int getContentsHeightWithPadding() {
		return PADDING * 2 + rows.size() * rowHeight;
	}

	@Override
	protected double getDeltaYPerScroll() {
		return rowHeight * 2.5;
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}

	private int rowAt(double mouseY) {
		double contentY = mouseY - getY() - PADDING + getScrollY();
		return contentY < 0 ? -1 : (int) (contentY / rowHeight);
	}

	private void moveSelection(int amount) {
		int start = selectedIndex < 0 ? (amount > 0 ? -1 : rows.size()) : selectedIndex;
		int candidate = Math.clamp(start + amount, 0, rows.size() - 1);
		selectedIndex = candidate;
		double rowTop = PADDING + (double) selectedIndex * rowHeight;
		double rowBottom = rowTop + rowHeight;
		if (rowTop < getScrollY()) {
			setScrollY(rowTop);
		} else if (rowBottom > getScrollY() + getHeight()) {
			setScrollY(rowBottom - getHeight());
		}
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

	public record Row<T>(T value, Text label, boolean active) {
		public Row {
			value = Objects.requireNonNull(value, "value");
			label = Objects.requireNonNull(label, "label");
		}
	}
}
