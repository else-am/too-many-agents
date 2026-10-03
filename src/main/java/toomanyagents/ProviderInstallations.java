package toomanyagents;

import com.google.gson.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import net.neoforged.fml.loading.FMLPaths;

/** Local discovery and launch-wide selections. Never downloads or updates a harness. */
public final class ProviderInstallations {
    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)");
    private static final List<String> IDS = List.of("codex", "claude");
    private static ProviderInstallations instance;
    public static synchronized ProviderInstallations get() {
        if (instance == null) instance = new ProviderInstallations();
        return instance;
    }
    private record Installation(String path, String version, String source, String error) {
        boolean available() { return error.isBlank(); }
        JsonObject json() { return ProjectStore.object("path",path,"version",version,"source",source,"error",error,"available",available()); }
    }
    private final Path file = FMLPaths.CONFIGDIR.get().resolve("too-many-agents-providers.json");
    private final Map<String,String> configured = new LinkedHashMap<>();
    private final Map<String,List<Installation>> found = new LinkedHashMap<>();
    private final Map<String,Installation> current = new LinkedHashMap<>(), next = new LinkedHashMap<>();
    private String error = "";
    private final CompletableFuture<Void> ready;

    private ProviderInstallations() {
        ready = CompletableFuture.runAsync(() -> {
            if (Files.exists(file)) try {
                var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (String id : IDS) configured.put(id,ProjectStore.text(saved,id));
            } catch (Exception failure) { error = "Could not read provider settings; saving will replace the unreadable file."; }
            for (String id : IDS) {
                var candidates = candidates(id);
                var jobs = candidates.entrySet().stream().map(entry -> CompletableFuture.supplyAsync(
                    () -> probe(id,entry.getKey().toString(),entry.getValue()))).toList();
                var installations = jobs.stream().map(CompletableFuture::join).sorted((a,b) -> compareVersions(b.version(),a.version())).toList();
                found.put(id,installations);
                var selected = choose(id,configured.getOrDefault(id,""));
                current.put(id,selected); next.put(id,selected);
            }
        });
    }

    public Path executable(String id) {
        ready.join();
        var choice = current.get(id);
        if (choice == null) throw new IllegalArgumentException("Unknown provider");
        // Preserve provider availability reporting even when none is installed.
        return choice.available() ? Path.of(choice.path()) : FMLPaths.GAMEDIR.get().resolve("too-many-agents/unavailable-" + id).toAbsolutePath();
    }
    public CompletableFuture<JsonObject> snapshot() { return ready.thenApply(unused -> view()); }
    private synchronized JsonObject view() {
        var rows = new JsonArray();
        for (String id : IDS) rows.add(ProjectStore.object("id",id,"name",id.equals("codex") ? "Codex" : "Claude Code",
            "configuredPath",configured.getOrDefault(id,""),"environmentPath",environment(id),
            "current",current.get(id).json(),"next",next.get(id).json(),
            "restartRequired",!current.get(id).equals(next.get(id)),
            "installations",found.get(id).stream().map(Installation::json).toList()));
        return ProjectStore.object("providers",rows,"error",error);
    }
    public CompletableFuture<JsonObject> configure(String id, String path) {
        if (!IDS.contains(id)) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown provider"));
        String chosen = path.strip();
        return ready.thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            if (!chosen.isBlank() && !Path.of(chosen).isAbsolute()) throw new IllegalArgumentException("Choose an absolute executable path.");
            var selection = choose(id,chosen);
            if (!chosen.isBlank() && !selection.available()) throw new IllegalArgumentException(selection.error());
            synchronized(this) {
                var saved = new JsonObject();
                for (String key : IDS) saved.addProperty(key,key.equals(id) ? chosen : configured.getOrDefault(key,""));
                try { ProjectStore.write(file,saved); }
                catch (java.io.IOException failure) { throw new CompletionException(failure); }
                configured.put(id,chosen); next.put(id,selection); error="";
                return view();
            }
        }));
    }
    private Installation choose(String id,String path) {
        if (!path.isBlank()) return probe(id,path,"Custom path");
        String env = environment(id);
        if (!env.isBlank()) return probe(id,env,"Environment override");
        return found.get(id).stream().filter(Installation::available).findFirst()
            .orElse(new Installation("","","Automatic","No working " + id + " installation found. Choose its executable path."));
    }
    private static String environment(String id) { return System.getenv().getOrDefault("TOO_MANY_AGENTS_" + id.toUpperCase(Locale.ROOT),"").strip(); }

    private static Installation probe(String id,String supplied,String source) {
        Process process = null;
        try {
            Path path = Path.of(supplied);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path))
                return new Installation(supplied,"",source,"Not an executable file: " + supplied);
            path = path.toRealPath();
            process = new ProcessBuilder(path.toString(),"--version").redirectErrorStream(true).start();
            Process running = process;
            var output = CompletableFuture.supplyAsync(() -> {
                try (var stream = running.getInputStream()) { return new String(stream.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8); }
                catch (java.io.IOException failure) { throw new CompletionException(failure); }
            });
            if (!process.waitFor(4,TimeUnit.SECONDS)) throw new IllegalArgumentException("Version check timed out: " + supplied);
            String text = output.get(1,TimeUnit.SECONDS).strip();
            var version = VERSION.matcher(text);
            boolean correct = text.toLowerCase(Locale.ROOT).contains(id.equals("codex") ? "codex" : "claude code");
            if (process.exitValue()!=0 || !correct || !version.find()) throw new IllegalArgumentException("Could not identify a " + id + " version at " + supplied);
            return new Installation(path.toString(),version.group(1),source,"");
        } catch (Exception failure) {
            return new Installation(supplied,"",source,failure.getMessage()==null ? "Version check failed" : failure.getMessage());
        } finally {
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }

    private static Map<Path,String> candidates(String id) {
        var result = new LinkedHashMap<Path,String>();
        Path home = Path.of(System.getProperty("user.home"));
        // App bundles are candidates, not a preference over a newer standalone install.
        if (id.equals("codex")) for (Path apps : List.of(Path.of("/Applications"),home.resolve("Applications"))) {
            for (String app : List.of("ChatGPT.app","Codex.app")) {
                Path resources = apps.resolve(app + "/Contents/Resources");
                add(result,resources.resolve("codex-cli/CodexCLI.app/Contents/MacOS/codex"),"Desktop bundle");
                add(result,resources.resolve("codex"),"Desktop bundle");
                add(result,resources.resolve("codex-cli/bin/codex"),"Desktop bundle");
            }
        }
        for (String directory : System.getenv().getOrDefault("PATH","").split(File.pathSeparator)) if (!directory.isBlank()) {
            Path folder = Path.of(directory);
            if (folder.isAbsolute()) { add(result,folder.resolve(id),"PATH"); add(result,folder.resolve(id+".exe"),"PATH"); }
        }
        for (Path folder : List.of(home.resolve(".local/bin"),Path.of("/opt/homebrew/bin"),Path.of("/usr/local/bin"),Path.of("/usr/bin"))) add(result,folder.resolve(id),"Local installation");
        if (id.equals("codex")) {
            add(result,home.resolve(".codex/packages/standalone/current/bin/codex"),"Standalone installation");
            children(home.resolve(".codex/packages/standalone/releases")).forEach(p -> add(result,p.resolve("bin/codex"),"Standalone installation"));
        } else {
            children(home.resolve(".local/share/claude/versions")).forEach(p -> add(result,p,"Claude installation"));
            add(result,home.resolve(".claude/local/claude"),"Claude installation");
            children(home.resolve("Library/Application Support/Claude/claude-code")).forEach(p ->
                add(result,p.resolve("claude.app/Contents/MacOS/claude"),"Desktop installation"));
        }
        // Common npm version-manager installations which might not be on the game's PATH.
        children(home.resolve(".nvm/versions/node")).forEach(p -> add(result,p.resolve("bin/"+id),"Node installation"));
        return result;
    }
    private static List<Path> children(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (var entries = Files.list(folder)) { return entries.sorted().toList(); }
        catch (java.io.IOException ignored) { return List.of(); }
    }
    private static void add(Map<Path,String> candidates,Path path,String source) {
        try { if (Files.isRegularFile(path) && Files.isExecutable(path)) candidates.putIfAbsent(path.toRealPath(),source); }
        catch (java.io.IOException ignored) {}
    }
    private static int compareVersions(String a,String b) {
        if (a.isBlank() || b.isBlank()) return a.isBlank() ? b.isBlank() ? 0 : -1 : 1;
        String[] left=a.split("-",2), right=b.split("-",2);
        String[] x=left[0].split("\\."), y=right[0].split("\\.");
        for (int i=0;i<3;i++) { int c=new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i])); if(c!=0)return c; }
        if(left.length!=right.length)return left.length==1?1:-1;
        if(left.length==1)return 0;
        x=left[1].split("\\."); y=right[1].split("\\.");
        for(int i=0;i<Math.min(x.length,y.length);i++) {
            boolean xn=x[i].matches("\\d+"),yn=y[i].matches("\\d+");
            int c=xn&&yn?new java.math.BigInteger(x[i]).compareTo(new java.math.BigInteger(y[i])):xn!=yn?xn?-1:1:x[i].compareTo(y[i]);
            if(c!=0)return c;
        }
        return Integer.compare(x.length,y.length);
    }
}
