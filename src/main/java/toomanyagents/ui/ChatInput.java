package toomanyagents.ui;

import java.util.ArrayDeque;
import java.text.BreakIterator;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Whence;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Native multiline editing with chat's insertion and read-only behavior. */
final class ChatInput extends MultiLineEditBox {
    private record Edit(String text, int cursor, int anchor) {}
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Edit lastEdit;
    private String editKind = "";
    private long editedAt, clickedAt;
    private double clickedX, clickedY;
    private int clicks, unitStart, unitEnd;

    ChatInput(Font font, int x, int y, int width) {
        super(font, x, y, width, 20, Component.literal("Message your agent…"), Component.literal("Message to agent"));
        setCharacterLimit(32768);
    }

    void insertText(String text) {
        if (!active) return;
        var before = snapshot();
        textField.insertText(text.replace("\r\n", "\n").replace('\r', '\n'));
        recordEdit(before, "insert");
        editKind = "";
    }

    @Override public void setHeight(int height) {
        super.setHeight(height);
        setScrollAmount(scrollAmount());
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return isMouseOver(mouseX, mouseY) && super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    static boolean shortcut(int modifiers) {
        return (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
    }

    private Edit snapshot() { return new Edit(getValue(), textField.cursor(), textField.selectCursor); }

    private void select(int anchor, int cursor) {
        textField.setSelecting(false);
        textField.seekCursor(Whence.ABSOLUTE, anchor);
        textField.setSelecting(true);
        textField.seekCursor(Whence.ABSOLUTE, cursor);
        textField.setSelecting(false);
    }

    private void recordEdit(Edit before, String kind) {
        if (before.text().equals(getValue())) return;
        long now = Util.getMillis();
        if (!kind.equals(editKind) || now - editedAt > 750 || !before.equals(lastEdit)) {
            undo.addLast(before);
            if (undo.size() > 100) undo.removeFirst();
        }
        redo.clear();
        editedAt = now;
        editKind = kind;
        lastEdit = snapshot();
    }

    private void restore(Edit edit) {
        super.setValue(edit.text());
        select(edit.anchor(), edit.cursor());
        editKind = "";
    }

    void keepEditingState(ChatInput previous) {
        if (previous == null || !getValue().equals(previous.getValue())) return;
        undo.addAll(previous.undo);
        redo.addAll(previous.redo);
        select(previous.textField.selectCursor, previous.textField.cursor());
    }

    @Override public void setValue(String text) {
        super.setValue(text);
        if (undo != null) { undo.clear(); redo.clear(); }
        editKind = "";
    }

    private int wordBoundary(boolean right) {
        String text = getValue();
        int cursor = textField.cursor();
        if (right) {
            while (cursor < text.length() && !Character.isWhitespace(text.charAt(cursor))) cursor++;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        } else {
            while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) cursor--;
            while (cursor > 0 && !Character.isWhitespace(text.charAt(cursor - 1))) cursor--;
        }
        return cursor;
    }

    private boolean navigate(int key, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean macCommand = Minecraft.ON_OSX && (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        boolean word = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
            || Minecraft.ON_OSX && (modifiers & GLFW.GLFW_MOD_ALT) != 0;
        int cursor = textField.cursor(), target;
        String text = getValue();
        if (macCommand && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            target = key == GLFW.GLFW_KEY_UP ? 0 : text.length();
        } else if (macCommand && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_BACKSPACE)) {
            int end = text.indexOf('\n', cursor);
            target = key == GLFW.GLFW_KEY_RIGHT ? (end < 0 ? text.length() : end) : text.lastIndexOf('\n', cursor - 1) + 1;
        } else if (word && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE)) {
            target = wordBoundary(key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_DELETE);
        } else if (!shift && !word && !macCommand && textField.hasSelection() && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            target = key == GLFW.GLFW_KEY_LEFT ? Math.min(cursor, textField.selectCursor) : Math.max(cursor, textField.selectCursor);
        } else return false;
        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
            if (!textField.hasSelection()) select(cursor, target);
            textField.insertText("");
        } else select(shift ? textField.selectCursor : target, target);
        return true;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!active) return false;
        boolean command = shortcut(modifiers), shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (command && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y)) {
            boolean forward = key == GLFW.GLFW_KEY_Y || shift;
            var source = forward ? redo : undo;
            var destination = forward ? undo : redo;
            if (!source.isEmpty()) { destination.addLast(snapshot()); restore(source.removeLast()); }
            return true;
        }
        var before = snapshot();
        boolean handled;
        if (command && key == GLFW.GLFW_KEY_A) { select(0, getValue().length()); handled = true; }
        else if (command && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X)) {
            if (textField.hasSelection()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(textField.getSelectedText());
                if (key == GLFW.GLFW_KEY_X) textField.insertText("");
            }
            handled = true;
        } else if (command && key == GLFW.GLFW_KEY_V) {
            insertText(Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        } else handled = navigate(key, modifiers) || super.keyPressed(key, scanCode, modifiers);
        boolean deleting = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
        recordEdit(before, deleting ? "delete" + key : "command");
        if (!deleting) editKind = "";
        clicks = 0;
        return handled;
    }

    @Override public boolean charTyped(char character, int modifiers) {
        if (!active) return false;
        var before = snapshot();
        boolean handled = super.charTyped(character, modifiers);
        recordEdit(before, "typing");
        clicks = 0;
        return handled;
    }

    private int[] selectionUnit(int cursor) {
        String text = getValue();
        if (text.isEmpty()) return new int[]{0, 0};
        int position = Math.min(cursor, text.length() - 1);
        if (clicks == 3) {
            int start = text.lastIndexOf('\n', position - 1) + 1;
            int end = text.indexOf('\n', position);
            return new int[]{start, end < 0 ? text.length() : end + 1};
        }
        var words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(text);
        int start = words.preceding(position + 1), end = words.following(position);
        return new int[]{Math.max(0, start), end < 0 ? text.length() : end};
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        editKind = "";
        boolean handled = super.mouseClicked(x, y, button);
        if (!handled || button != 0 || !withinContentAreaPoint(x, y)) return handled;
        long now = Util.getMillis();
        clicks = now - clickedAt <= 350 && Math.abs(x - clickedX) <= 4 && Math.abs(y - clickedY) <= 4 ? clicks % 3 + 1 : 1;
        clickedAt = now; clickedX = x; clickedY = y;
        if (clicks > 1) {
            var unit = selectionUnit(textField.cursor());
            unitStart = unit[0]; unitEnd = unit[1];
            select(unitStart, unitEnd);
        }
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (clicks > 1 && button == 0 && withinContentAreaPoint(x, y)) {
            textField.seekCursorToPoint(x - getX() - innerPadding(), y - getY() - innerPadding() + scrollAmount());
            var unit = selectionUnit(textField.cursor());
            if (unit[0] < unitStart) select(unitEnd, unit[0]);
            else select(unitStart, Math.max(unitEnd, unit[1]));
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override protected void renderDecorations(GuiGraphics graphics) {
        // Keep the scrollbar, without the native editor's character-count footer.
        if (!scrollbarVisible()) return;
        int thumbHeight = Math.clamp(getHeight() * getHeight() / (getInnerHeight() + 4), 8, getHeight());
        int thumbY = getY() + (int) (scrollAmount() * (getHeight() - thumbHeight) / Math.max(1, getMaxScrollAmount()));
        graphics.blitSprite(ResourceLocation.withDefaultNamespace("widget/scroller"), getX() + getWidth(), thumbY, 8, thumbHeight);
    }
}
