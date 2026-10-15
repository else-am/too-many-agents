package toomanyagents.screens;

import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;

/** Chromium input avoids JCEF's incomplete AWT translation (notably modifiers on macOS). */
final class ScreenKeys {
    private ScreenKeys() {}

    static void send(ScreenView view, int key, int scanCode, int action, int mods) {
        String code, value;
        int virtualKey = key, location = 0;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) code = "Key" + (char) key;
        else if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) code = "Digit" + (char) key;
        else if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F24) {
            code = "F" + (key - GLFW.GLFW_KEY_F1 + 1);
            virtualKey = 112 + key - GLFW.GLFW_KEY_F1;
        } else {
            switch (key) {
                case GLFW.GLFW_KEY_SPACE -> { code = "Space"; virtualKey = 32; }
                case GLFW.GLFW_KEY_ESCAPE -> { code = "Escape"; virtualKey = 27; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { code = key == GLFW.GLFW_KEY_ENTER ? "Enter" : "NumpadEnter"; virtualKey = 13; }
                case GLFW.GLFW_KEY_TAB -> { code = "Tab"; virtualKey = 9; }
                case GLFW.GLFW_KEY_BACKSPACE -> { code = "Backspace"; virtualKey = 8; }
                case GLFW.GLFW_KEY_DELETE -> { code = "Delete"; virtualKey = 46; }
                case GLFW.GLFW_KEY_INSERT -> { code = "Insert"; virtualKey = 45; }
                case GLFW.GLFW_KEY_HOME -> { code = "Home"; virtualKey = 36; }
                case GLFW.GLFW_KEY_END -> { code = "End"; virtualKey = 35; }
                case GLFW.GLFW_KEY_PAGE_UP -> { code = "PageUp"; virtualKey = 33; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { code = "PageDown"; virtualKey = 34; }
                case GLFW.GLFW_KEY_LEFT -> { code = "ArrowLeft"; virtualKey = 37; }
                case GLFW.GLFW_KEY_UP -> { code = "ArrowUp"; virtualKey = 38; }
                case GLFW.GLFW_KEY_RIGHT -> { code = "ArrowRight"; virtualKey = 39; }
                case GLFW.GLFW_KEY_DOWN -> { code = "ArrowDown"; virtualKey = 40; }
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> { code = key == GLFW.GLFW_KEY_LEFT_SHIFT ? "ShiftLeft" : "ShiftRight"; virtualKey = 16; }
                case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> { code = key == GLFW.GLFW_KEY_LEFT_CONTROL ? "ControlLeft" : "ControlRight"; virtualKey = 17; }
                case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> { code = key == GLFW.GLFW_KEY_LEFT_ALT ? "AltLeft" : "AltRight"; virtualKey = 18; }
                case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> { code = key == GLFW.GLFW_KEY_LEFT_SUPER ? "MetaLeft" : "MetaRight"; virtualKey = 91; }
                case GLFW.GLFW_KEY_CAPS_LOCK -> { code = "CapsLock"; virtualKey = 20; }
                case GLFW.GLFW_KEY_NUM_LOCK -> { code = "NumLock"; virtualKey = 144; }
                case GLFW.GLFW_KEY_SCROLL_LOCK -> { code = "ScrollLock"; virtualKey = 145; }
                case GLFW.GLFW_KEY_PAUSE -> { code = "Pause"; virtualKey = 19; }
                case GLFW.GLFW_KEY_PRINT_SCREEN -> { code = "PrintScreen"; virtualKey = 44; }
                case GLFW.GLFW_KEY_MENU -> { code = "ContextMenu"; virtualKey = 93; }
                case GLFW.GLFW_KEY_SEMICOLON -> { code = "Semicolon"; virtualKey = 186; }
                case GLFW.GLFW_KEY_EQUAL -> { code = "Equal"; virtualKey = 187; }
                case GLFW.GLFW_KEY_COMMA -> { code = "Comma"; virtualKey = 188; }
                case GLFW.GLFW_KEY_MINUS -> { code = "Minus"; virtualKey = 189; }
                case GLFW.GLFW_KEY_PERIOD -> { code = "Period"; virtualKey = 190; }
                case GLFW.GLFW_KEY_SLASH -> { code = "Slash"; virtualKey = 191; }
                case GLFW.GLFW_KEY_GRAVE_ACCENT -> { code = "Backquote"; virtualKey = 192; }
                case GLFW.GLFW_KEY_LEFT_BRACKET -> { code = "BracketLeft"; virtualKey = 219; }
                case GLFW.GLFW_KEY_BACKSLASH -> { code = "Backslash"; virtualKey = 220; }
                case GLFW.GLFW_KEY_RIGHT_BRACKET -> { code = "BracketRight"; virtualKey = 221; }
                case GLFW.GLFW_KEY_APOSTROPHE -> { code = "Quote"; virtualKey = 222; }
                case GLFW.GLFW_KEY_KP_DECIMAL -> { code = "NumpadDecimal"; virtualKey = 110; }
                case GLFW.GLFW_KEY_KP_DIVIDE -> { code = "NumpadDivide"; virtualKey = 111; }
                case GLFW.GLFW_KEY_KP_MULTIPLY -> { code = "NumpadMultiply"; virtualKey = 106; }
                case GLFW.GLFW_KEY_KP_SUBTRACT -> { code = "NumpadSubtract"; virtualKey = 109; }
                case GLFW.GLFW_KEY_KP_ADD -> { code = "NumpadAdd"; virtualKey = 107; }
                case GLFW.GLFW_KEY_KP_EQUAL -> { code = "NumpadEqual"; virtualKey = 187; }
                default -> {
                    if (key < GLFW.GLFW_KEY_KP_0 || key > GLFW.GLFW_KEY_KP_9) return;
                    code = "Numpad" + (key - GLFW.GLFW_KEY_KP_0);
                    virtualKey = 96 + key - GLFW.GLFW_KEY_KP_0;
                }
            }
        }
        value = switch (virtualKey) {
            case 16 -> "Shift";
            case 17 -> "Control";
            case 18 -> "Alt";
            case 91 -> "Meta";
            case 32 -> " ";
            default -> code;
        };
        String name = GLFW.glfwGetKeyName(key, scanCode);
        if (name != null) value = (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? name.toUpperCase(java.util.Locale.ROOT) : name;
        if (key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_LEFT_SUPER) location = 1;
        if (key >= GLFW.GLFW_KEY_RIGHT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER) location = 2;
        if (code.startsWith("Numpad")) location = 3;
        int modifiers = ((mods & GLFW.GLFW_MOD_ALT) != 0 ? 1 : 0) | ((mods & GLFW.GLFW_MOD_CONTROL) != 0 ? 2 : 0)
            | ((mods & GLFW.GLFW_MOD_SUPER) != 0 ? 4 : 0) | ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 8 : 0);
        var params = new JsonObject();
        params.addProperty("type", action == GLFW.GLFW_RELEASE ? "keyUp" : virtualKey == 13 ? "keyDown" : "rawKeyDown");
        if (virtualKey == 13 && action != GLFW.GLFW_RELEASE) params.addProperty("text", "\r");
        params.addProperty("code", code);
        params.addProperty("key", value);
        params.addProperty("windowsVirtualKeyCode", virtualKey);
        params.addProperty("modifiers", modifiers);
        params.addProperty("location", location == 3 ? 0 : location);
        params.addProperty("isKeypad", location == 3);
        params.addProperty("autoRepeat", action == GLFW.GLFW_REPEAT);
        view.playerInput("Input.dispatchKeyEvent", params);
    }

    static void text(ScreenView view, String text) {
        var params = new JsonObject();
        // Chromium's char event rejects supplementary Unicode; IME-style insertion handles those code points.
        if (text.length() > 1) {
            params.addProperty("text", text);
            view.playerInput("Input.insertText", params);
            return;
        }
        params.addProperty("type", "char");
        params.addProperty("key", text);
        params.addProperty("text", text);
        params.addProperty("unmodifiedText", text);
        view.playerInput("Input.dispatchKeyEvent", params);
    }
}
