package toomanyagents;

/** Modifier flags for the keystroke currently being dispatched, not GLFW's cached key state. */
public final class KeyboardModifiers {
    public static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private KeyboardModifiers() {}
}
