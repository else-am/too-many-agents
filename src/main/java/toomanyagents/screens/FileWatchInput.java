package toomanyagents.screens;

import java.nio.file.Path;

/** A watch hint carries an existing grant, never permission to grant an event's path. */
record FileWatchInput(ConfinedFiles.Root root, String path, boolean directory, Kind kind) {
    enum Kind { CONTENT, STRUCTURE, GIT, RULES }
    FileWatchInput {
        Path base = Path.of(root.path());
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("Watch input outside its grant");
        path = base.relativize(target).toString();
        if (java.io.File.separatorChar == '\\') path = path.replace('\\', '/');
    }
    String key() { return root.path() + "\0" + path + "\0" + directory + "\0" + kind; }
}
