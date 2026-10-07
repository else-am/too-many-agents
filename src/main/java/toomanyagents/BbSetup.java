package toomanyagents;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import static toomanyagents.JsonState.*;

/** Installation preferences and the one selected BB. All setup IO runs off the game thread. */
public final class BbSetup {
    public enum Action { WAIT, NONE, ALLOW_UPDATES, RESTORE, RETRY }
    public record Instance(String id, String url, String version, String label) {}
    public record View(String message, String instanceId, String pluginVersion, boolean ready,
                       boolean automatic, boolean busy, String cli, List<Instance> instances, boolean bbNotFound,
                       Action action, boolean needsLocation) {}
    private static final BbSetup INSTANCE = new BbSetup();
    public static BbSetup get() { return INSTANCE; }
    public static String version() {
        return ModList.get().getModContainerById("too_many_agents").orElseThrow().getModInfo().getVersion().toString();
    }
    private final Path root = Path.of(System.getProperty("user.home"), ".too-many-agents");
    private final Path settings = FMLPaths.CONFIGDIR.get().resolve("too-many-agents-bb.json");
    private final String developmentSource = System.getProperty("too_many_agents.devPlugin", "");
    private boolean developmentLoaded;
    private boolean installStartedThisLaunch;
    public boolean development() { return !developmentSource.isBlank(); }
    public String pluginSourceLabel() { return development() ? "development plugin" : "bundled plugin"; }
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("minecraft-bb-setup").factory());
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    private volatile View view = new View("Checking BB…", "", "", false, false, false, "", List.of(), false, Action.WAIT, false);
    private Action action = Action.WAIT;
    private String selected = "", cli = "", selectedUrl = "";
    private String expectedPluginRoot = "", previousGeneration = "";
    private boolean automatic, installPending, settingsUnreadable;
    private boolean bbNotFound;
    private URI readyUrl;
    private String error = "";
    private String notice = "";

    private BbSetup() {
        try {
            if (Files.exists(settings)) {
                var data = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
                selected = text(data,"instanceId"); cli = text(data,"cli"); selectedUrl = text(data,"url");
                automatic = flag(data,"automatic"); installPending = flag(data,"installPending");
                expectedPluginRoot = text(data,"expectedPluginRoot"); previousGeneration = text(data,"previousGeneration");
            }
        } catch (Exception failure) {
            automatic = false; settingsUnreadable = true;
            error = "Could not read BB setup settings. Choose BB again to save a new selection.";
        }
        worker.scheduleWithFixedDelay(() -> check(false), 0, 5, TimeUnit.SECONDS);
    }

    public View view() { return view; }
    public synchronized URI address() {
        if (readyUrl == null) throw new IllegalStateException(view.message() + " Open BB connection at the top of mod settings.");
        return readyUrl;
    }
    public void retry() { worker.execute(() -> { if (!settingsUnreadable) error = ""; notice = ""; check(false); }); }
    public void select(String id) { worker.execute(() -> {
        developmentLoaded = false; installStartedThisLaunch = false;
        selected = id; selectedUrl = ""; automatic = false; installPending = false; settingsUnreadable = false; error = notice = "";
        persist(); check(false);
    }); }
    public void chooseCli(String path) { worker.execute(() -> {
        String previousCli = cli;
        try {
            cli = resolveCli(path).toString();
            var status = command("", "status", "--json");
            selected = digest(text(status,"dataDir")); selectedUrl = "";
            developmentLoaded = false; installStartedThisLaunch = false;
            automatic = false; installPending = false; settingsUnreadable = false; error = notice = ""; persist();
        } catch (Exception failure) { cli = previousCli; error = notice = failure.getMessage(); }
        check(false);
    }); }
    public void automatic(boolean enabled) { worker.execute(() -> {
        if (settingsUnreadable) return;
        automatic = enabled; error = notice = ""; persist(); check(false);
    }); }
    /** Explicit installation also authorizes a downgrade or retry of an uncertain attempt. */
    public void install() { worker.execute(() -> { if (!settingsUnreadable) error = notice = ""; check(true); }); }

    private void persist() {
        try { JsonState.write(settings, object("instanceId",selected,"url",selectedUrl,"cli",cli,"automatic",automatic,"installPending",installPending,
            "expectedPluginRoot",expectedPluginRoot,"previousGeneration",previousGeneration)); }
        catch (Exception failure) { automatic = false; error = notice = "Could not save BB setup: " + failure.getMessage(); }
    }

    private void check(boolean explicit) {
        check(explicit, false);
    }

    private void check(boolean explicit, boolean locked) {
        List<Instance> instances = List.of();
        String installed = "";
        bbNotFound = false;
        action = Action.RETRY;
        try {
            Path developmentDirectory = development() ? developmentDirectory() : null;
            instances = discover();
            if (!cli.isBlank()) {
                try { resolveCli(cli); }
                catch (IOException | InvalidPathException missing) { cli = ""; }
            }
            if (cli.isBlank()) cli = findCli();
            bbNotFound = instances.isEmpty() && cli.isBlank();
            if (settingsUnreadable) { publish(error, "", false, false, instances); return; }
            if (selected.isBlank()) {
                if (instances.size() == 1) { selected = instances.getFirst().id(); persist(); }
                else if (instances.isEmpty() && !cli.isBlank()) {
                    // Ask BB's own CLI to resolve its installation; never guess its data directory.
                    selected = digest(text(command("", "status", "--json"), "dataDir")); persist();
                } else {
                    if (!instances.isEmpty()) action = Action.NONE;
                    publish(instances.isEmpty() ? "Open BB, or choose its installed app or CLI." : "Choose the BB instance to use.", "", false, false, instances); return;
                }
            }
            final String id = selected;
            Instance found = instances.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
            String url = found == null ? selectedUrl : found.url();
            String generation = "";
            if (found != null) {
                var status = request(found.url(), "/api/v1/plugins/minecraft/http/v1/setup", null);
                if (!selected.equals(text(status,"instanceId"))) throw new IOException("BB identity changed. Choose BB again.");
                installed = text(status,"pluginVersion");
                generation = text(status,"generation");
                if (!url.equals(selectedUrl)) { selectedUrl = url; persist(); }
                boolean sourceMatches = !development() || developmentDirectory.toString().equals(text(status,"pluginRoot"));
                if (installPending && sourceMatches && installationVerified(status)) {
                    installPending = false; developmentLoaded = development() && installStartedThisLaunch; error = ""; persist();
                }
                if (version().equals(installed) && !explicit && !installPending && sourceMatches && (!development() || developmentLoaded)) {
                    action = notice.isBlank() ? Action.NONE : Action.RETRY;
                    synchronized (this) { readyUrl = loopback(url); }
                    publish(notice.isBlank() ? "Connected to BB" : notice, installed, true, false, instances); return;
                }
            }
            if (cli.isBlank()) throw new IOException("Choose BB's installed app or CLI to install its Minecraft plugin.");
            if (!locked) {
                Files.createDirectories(root.resolve("locks"));
                try (var channel = FileChannel.open(root.resolve("locks").resolve(selected + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Another Minecraft installation is updating this BB. Retry shortly.");
                    // Re-read discovery and authorization once under the selected instance's
                    // lock. Keep it through installation and verification; never reacquire it.
                    check(explicit, true);
                }
                return;
            }
            if (found == null) {
                // The selected BB may have restarted on another port with its plugin
                // disabled. Only use CLI discovery when it identifies that same BB.
                try {
                    if (selected.equals(digest(text(command("", "status", "--json"), "dataDir")))) url = "";
                } catch (Exception ignored) { /* Try the remembered address below. */ }
            }
            // A saved address is only a hint; CLI status and the stable identity must agree.
            var status = command(url, "status", "--json");
            if (!selected.equals(digest(text(status,"dataDir")))) throw new IOException("This CLI reaches a different BB. Choose the intended BB again.");
            JsonObject plugin = null;
            for (var row : array(command(url, "plugin", "list", "--json"), "plugins")) {
                if (text(row.getAsJsonObject(),"id").equals("minecraft")) plugin = row.getAsJsonObject();
            }
            if (plugin != null) installed = text(plugin,"version");
            if (installPending && !explicit) {
                action = Action.RESTORE;
                throw new IOException("The previous installation was not verified. Check BB before reinstalling the bundled plugin.");
            }
            boolean older = !installed.isBlank() && compareVersions(installed, version()) < 0;
            boolean canAuto = development()
                ? !developmentLoaded && (plugin == null || older || version().equals(installed))
                : automatic && (plugin == null || older);
            if (!explicit && !canAuto) {
                action = !development() && (plugin == null || older) ? Action.ALLOW_UPDATES : Action.RESTORE;
                String message = development() ? "BB is using a different plugin. Reload this checkout's plugin, or restart development."
                    : plugin == null ? "Allow plugin management to install the Minecraft integration."
                    : version().equals(installed) ? "The matching plugin is not running. Enable it in BB, or reinstall the bundled plugin."
                    : older ? "Allow plugin management to update the Minecraft integration."
                    : "BB's Minecraft plugin is newer or from a different release. Update this mod, or explicitly restore its bundled plugin.";
                publish(message, installed, false, false, instances); return;
            }
            publish("Installing Minecraft plugin " + version() + "…", installed, false, true, instances);
            installPlugin(url, plugin, found, developmentDirectory, generation);
            // Verify rather than repeating a mutation whose outcome may be unknown.
            for (int attempt=0; attempt<12; attempt++) {
                Thread.sleep(500);
                for (var candidate : discover()) if (candidate.id().equals(selected) && candidate.version().equals(version())) {
                    var verified = request(candidate.url(), "/api/v1/plugins/minecraft/http/v1/setup", null);
                    if (installationVerified(verified)) {
                        selectedUrl = candidate.url(); installPending = false; persist();
                        developmentLoaded = development();
                        synchronized (this) { readyUrl = loopback(candidate.url()); }
                        action = Action.NONE;
                        publish("Connected to BB", version(), true, false, discover()); return;
                    }
                }
            }
            throw new IOException("Plugin installation could not be verified. Check its status in BB before retrying.");
        } catch (Exception failure) {
            error = failure.getMessage() == null ? "Could not connect to BB." : failure.getMessage();
            if (explicit) notice = error;
            publish(error, installed, false, false, instances);
        }
    }

    private boolean installationVerified(JsonObject status) {
        return selected.equals(text(status,"instanceId")) && version().equals(text(status,"pluginVersion"))
            && !expectedPluginRoot.isBlank() && expectedPluginRoot.equals(text(status,"pluginRoot"))
            && !text(status,"generation").isBlank() && !previousGeneration.equals(text(status,"generation"));
    }

    private Path developmentDirectory() throws IOException {
        Path directory = Path.of(developmentSource).toRealPath();
        var manifest = JsonParser.parseString(Files.readString(directory.resolve("package.json"))).getAsJsonObject();
        if (!text(manifest,"name").equals("bb-plugin-minecraft") || !text(manifest,"version").equals(version())
            || !Files.isRegularFile(directory.resolve("dist/server.js")))
            throw new IOException("Build this checkout's matching Minecraft plugin, then restart development.");
        return directory;
    }

    private void installPlugin(String url, JsonObject plugin, Instance found, Path developmentDirectory, String generation) throws Exception {
        // check holds the selected instance's lock and has refreshed these inputs.
        if (plugin != null) {
            if (!text(plugin,"source").startsWith("path:"))
                throw new IOException("This plugin is managed by another installer. Change its source in BB before enabling mod-managed installation.");
            if (found == null) {
                // An older or broken plugin cannot prove that it is safe to replace.
                if (!text(plugin,"status").equals("disabled"))
                    throw new IOException("This plugin cannot report active games. Close its games and disable Minecraft in BB before replacing it.");
            } else {
                var prepared = request(url, "/api/v1/plugins/minecraft/http/v1/setup/prepare", new JsonObject());
                if (!flag(prepared,"ok")) throw new IOException(text(prepared,"error"));
                if (!selected.equals(text(prepared,"instanceId")) || !generation.equals(text(prepared,"generation")))
                    throw new IOException("BB's Minecraft plugin changed during setup. Retry to check its current version.");
                generation = text(prepared,"generation");
            }
        }
        Path directory = developmentDirectory == null ? extractBundle() : developmentDirectory;
        expectedPluginRoot = directory.toRealPath().toString(); previousGeneration = generation;
        error = ""; installPending = true; persist();
        if (!error.isBlank()) throw new IOException(error);
        installStartedThisLaunch = true;
        if (plugin != null && expectedPluginRoot.equals(text(plugin,"rootDir")))
            command(url, "plugin", "reload", "minecraft", "--json");
        else command(url, "plugin", "install", "path:" + directory, "--yes", "--json");
        // A path move preserves a disabled plugin's state. Enabling is part of the user's install action.
        command(url, "plugin", "enable", "minecraft", "--json");
    }

    private Path extractBundle() throws Exception {
        byte[] bytes;
        try (var stream = BbSetup.class.getResourceAsStream("/too_many_agents/bb-plugin.zip")) {
            if (stream == null) throw new IOException("The mod JAR has no bundled BB plugin. Reinstall the complete mod JAR.");
            bytes = stream.readAllBytes();
        }
        Path releases = root.resolve("plugins"); Files.createDirectories(releases);
        Path target = releases.resolve(version() + "-" + UUID.randomUUID());
        // A reinstall gets a fresh copy even if an earlier extracted package was
        // damaged. Never overwrite the previous release; BB can roll back to it.
        Path temporary = Files.createTempDirectory(releases,"extract-");
        try {
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (var entry=zip.getNextEntry(); entry!=null; entry=zip.getNextEntry()) {
                    Path file = temporary.resolve(entry.getName()).normalize();
                    if (!file.startsWith(temporary)) throw new IOException("Invalid bundled plugin path.");
                    if (entry.isDirectory()) Files.createDirectories(file);
                    else { Files.createDirectories(file.getParent()); Files.copy(zip,file); }
                }
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            if (Files.exists(temporary)) try (var paths=Files.walk(temporary)) {
                for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void publish(String message, String installed, boolean ready, boolean busy, List<Instance> instances) {
        if (!ready) synchronized (this) { readyUrl = null; }
        view = new View(message, selected, installed, ready, automatic, busy, cli, List.copyOf(instances), bbNotFound && !ready,
            busy ? Action.WAIT : action, !ready && (settingsUnreadable || cli.isBlank() && !bbNotFound));
    }
    private List<Instance> discover() throws IOException {
        Map<String,Instance> found = new TreeMap<>();
        Path directory = root.resolve("bb");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files=Files.newDirectoryStream(directory,"*.json")) {
            for (var file:files) try {
                var data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                String id=text(data,"instanceId"), url=text(data,"serverUrl");
                if (data.get("protocol").getAsInt()!=1 || data.get("expiresAt").getAsLong()<=System.currentTimeMillis() || !id.matches("[a-f0-9]{64}")) continue;
                loopback(url);
                String label=text(data,"label");
                var instance=new Instance(id,url,text(data,"pluginVersion"),label.isBlank()?url:label);
                var previous=found.putIfAbsent(id,instance);
                if (previous!=null && !previous.url().equals(url)) throw new IOException("Two BB servers are advertising the same installation. Close the duplicate BB server.");
            } catch (NoSuchFileException | RuntimeException ignored) { /* Reloads can remove advertisements while we read them. */ }
        }
        return List.copyOf(found.values());
    }
    private JsonObject request(String url, String path, JsonObject body) throws Exception {
        var request=HttpRequest.newBuilder(loopback(url).resolve(path)).timeout(Duration.ofSeconds(10));
        if (body!=null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        JsonObject result;
        try { result=JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException failure) { throw new IOException("BB returned an unreadable setup response."); }
        if (response.statusCode()!=200) throw new IOException(result.has("error") && result.get("error").isJsonPrimitive() ? text(result,"error") : "BB setup failed (HTTP " + response.statusCode() + ").");
        return result;
    }
    public static URI loopback(String value) {
        URI url=URI.create(value);
        if (!"http".equals(url.getScheme()) || !Set.of("127.0.0.1","[::1]","::1").contains(url.getHost()) || url.getUserInfo()!=null || url.getQuery()!=null || url.getFragment()!=null)
            throw new IllegalArgumentException("Choose a local BB instance with a loopback HTTP address.");
        return url;
    }
    private static String digest(String value) throws Exception {
        if (value.isBlank()) throw new IOException("BB did not identify its installation.");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static int compareVersions(String a,String b) {
        if (!a.matches("\\d+\\.\\d+\\.\\d+") || !b.matches("\\d+\\.\\d+\\.\\d+")) return 1;
        var left=a.split("\\."); var right=b.split("\\.");
        for(int i=0;i<3;i++) { int n=new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i])); if(n!=0)return n; }
        return 0;
    }
    private static Path resolveCli(String value) throws IOException {
        Path path=Path.of(value).toAbsolutePath();
        if (Files.isDirectory(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".app"))
            path=path.resolve("Contents/Resources/app.asar.unpacked/node_modules/bb-app/host-daemon/dist/bb");
        else if (Files.isRegularFile(path)) {
            // Selecting the desktop executable must run its bundled CLI, never launch another BB.
            Path bundled=path.getParent().resolve("resources/app.asar.unpacked/node_modules/bb-app/host-daemon/dist/bb");
            Path mac=path.getParent().resolve("../Resources/app.asar.unpacked/node_modules/bb-app/host-daemon/dist/bb").normalize();
            if (Files.isRegularFile(bundled)) path=bundled;
            else if (Files.isRegularFile(mac)) path=mac;
            else if (path.toString().endsWith(".cmd") && nodeScript(path.resolveSibling("bb"))) path=path.resolveSibling("bb");
        }
        if (!Files.isRegularFile(path)) throw new IOException("Select the installed BB app or its executable bb CLI.");
        if (System.getProperty("os.name").startsWith("Windows") && !path.toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            if (!nodeScript(path)) path = npmCli(path);
        } else if (!Files.isExecutable(path)) throw new IOException("Select the installed BB app or its executable bb CLI.");
        return path.toRealPath();
    }
    private static boolean nodeScript(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        try (var reader = Files.newBufferedReader(path)) {
            String first = reader.readLine();
            return first != null && first.matches("#!.*[ /]node(?:\\.exe)?(?:\\s.*)?");
        }
    }
    private static Path npmCli(Path wrapper) throws IOException {
        // npm global and local .bin wrappers point at a package's declared bin.
        // Resolve that declaration instead of executing or parsing a shell shim.
        Path parent = wrapper.getParent();
        Path directory = parent.getFileName() != null && parent.getFileName().toString().equals(".bin")
            ? parent.getParent().resolve("bb-app") : parent.resolve("node_modules/bb-app");
        if (Files.isRegularFile(directory.resolve("package.json"))) {
            directory = directory.toRealPath();
            var manifest = JsonParser.parseString(Files.readString(directory.resolve("package.json"))).getAsJsonObject();
            String entry = text(obj(manifest,"bin"),"bb");
            if (text(manifest,"name").equals("bb-app") && !entry.isBlank()) {
                Path script = directory.resolve(entry).toRealPath();
                if (script.startsWith(directory) && nodeScript(script)) return script;
            }
        }
        throw new IOException("Could not resolve this BB command wrapper. Select BB's installed app or its JavaScript CLI entry point.");
    }
    private static Path desktopRuntime(Path script) throws IOException {
        for (Path parent=script.getParent();parent!=null;parent=parent.getParent()) {
            if (parent.toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
                Path binaries=parent.resolve("Contents/MacOS");
                if (Files.isDirectory(binaries)) try(var files=Files.list(binaries)) {
                    var executables=files.filter(Files::isRegularFile).filter(Files::isExecutable).toList();
                    if (executables.size()==1) return executables.getFirst();
                }
            }
            if (parent.getFileName()!=null && parent.getFileName().toString().equalsIgnoreCase("resources")) {
                for(var name:List.of("bb.exe","bb Nightly.exe","bb","bb-nightly")) {
                    Path executable=parent.getParent().resolve(name);
                    if(Files.isRegularFile(executable)&&Files.isExecutable(executable))return executable;
                }
            }
        }
        return null;
    }
    private static String nodeRuntime(Path script) {
        Path sibling = script.resolveSibling("node.exe");
        if (Files.isRegularFile(sibling)) return sibling.toString();
        for (Path parent = script.getParent(); parent != null && parent.getParent() != null; parent = parent.getParent()) {
            if (parent.getFileName().toString().equals("node_modules")) {
                Path localRuntime = parent.resolve(".bin/node.exe");
                if (Files.isRegularFile(localRuntime)) return localRuntime.toString();
                Path runtime = parent.getParent().resolve("node.exe");
                if (Files.isRegularFile(runtime)) return runtime.toString();
            }
        }
        return "node.exe";
    }
    private static String findCli() {
        var candidates=new ArrayList<String>();
        for (var directory:System.getenv().getOrDefault("PATH","").split(java.io.File.pathSeparator)) if (!directory.isBlank()) {
            for (var name : System.getProperty("os.name").startsWith("Windows") ? List.of("bb.exe","bb.cmd","bb") : List.of("bb","bb.exe","bb.cmd"))
                candidates.add(Path.of(directory,name).toString());
        }
        candidates.add(Path.of(System.getProperty("user.home"),".local/bin/bb").toString());
        candidates.add("/Applications/BB.app");
        candidates.add(Path.of(System.getProperty("user.home"),"Applications/BB.app").toString());
        for (var candidate:candidates) try { return resolveCli(candidate).toString(); } catch (IOException ignored) {}
        return "";
    }
    public static String locationLabel(String value) {
        if (value.isBlank()) return "";
        for(Path path=Path.of(value);path!=null;path=path.getParent())
            if(path.toString().toLowerCase(Locale.ROOT).endsWith(".app"))return path.toString();
        return value;
    }
    private JsonObject command(String url,String... args) throws Exception {
        Path script=resolveCli(cli), runtime=desktopRuntime(script);
        var command=new ArrayList<String>();
        if (runtime!=null) command.add(runtime.toString());
        else if (System.getProperty("os.name").startsWith("Windows") && !script.toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
            command.add(nodeRuntime(script)); // npm requires Node; desktop BB uses its own runtime above.
        command.add(script.toString()); command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        // A launcher may itself be a BB child. Its thread and binary overrides must
        // not redirect the user's selected installation or contaminate CLI context.
        builder.environment().keySet().removeIf(key -> key.startsWith("BB_") || key.equals("ELECTRON_RUN_AS_NODE"));
        if (runtime!=null) builder.environment().put("ELECTRON_RUN_AS_NODE","1");
        if (!url.isBlank()) builder.environment().put("BB_SERVER_URL",loopback(url).toString());
        var process=builder.start();
        var output=CompletableFuture.supplyAsync(() -> {
            try(var stream=process.getInputStream()) { return stream.readNBytes(8*1024*1024); }
            catch(IOException failure) { throw new CompletionException(failure); }
        });
        if (!process.waitFor(45,TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("BB did not finish the setup command. Its outcome is unknown; check BB before retrying.");
        }
        if (process.exitValue()!=0) throw new IOException("BB could not complete '" + String.join(" ",Arrays.copyOf(args,Math.min(2,args.length))) + "'. Check that the selected BB is running and inspect its plugin status.");
        var result=JsonParser.parseString(new String(output.get(5,TimeUnit.SECONDS),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (result.has("ok") && !flag(result,"ok")) throw new IOException("BB refused the plugin operation. Inspect Minecraft in BB's plugin settings before retrying.");
        return result;
    }
}
