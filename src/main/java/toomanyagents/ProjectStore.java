package toomanyagents;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Provider-independent projects and checkouts. Mutations run on the service's disk executor. */
final class ProjectStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory, file;
    private final LinkedHashMap<String, JsonObject> projects = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> checkouts = new LinkedHashMap<>();

    ProjectStore(Path directory) { this.directory = directory; file = directory.resolve("projects-v2.json"); }

    synchronized void load() throws IOException {
        if (!Files.exists(file)) return;
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (saved.get("version").getAsInt() != 2) throw new IOException("Unsupported project registry version; preserved " + file);
        for (var value : saved.getAsJsonArray("projects")) {
            var row = value.getAsJsonObject(); projects.put(text(row,"id"), row);
        }
        for (var value : saved.getAsJsonArray("checkouts")) {
            var row = value.getAsJsonObject();
            if (!projects.containsKey(text(row,"projectId"))) throw new IOException("Checkout has no project: " + text(row,"id"));
            checkouts.put(text(row,"id"), row);
        }
    }

    synchronized JsonObject snapshot() {
        var rows = new JsonArray();
        for(var checkout:checkouts.values()) {
            var row=checkout.deepCopy();row.addProperty("available",Files.isDirectory(Path.of(text(row,"directory"))));
            if(Files.isDirectory(Path.of(text(row,"directory")))) row.addProperty("branch",branch(Path.of(text(row,"directory"))));
            rows.add(row);
        }
        var projectRows=new JsonArray();
        for(var project:projects.values()) projectRows.add(project(text(project,"id")));
        return object("projects", projectRows, "checkouts", rows);
    }
    synchronized JsonObject project(String id) {
        var result=require(projects,id,"project").deepCopy();
        Path folder=Path.of(text(result,"primaryDirectory"));
        result.addProperty("available",Files.isDirectory(folder));
        result.addProperty("isGitRepository",gitDirectory(folder)!=null);
        result.addProperty("branch",branch(folder));
        return result;
    }
    private static Path gitDirectory(Path folder) {
        if(!Files.isDirectory(folder)) return null;
        for(Path current=folder;current!=null;current=current.getParent()) {
            Path dotGit=current.resolve(".git");
            if(Files.isDirectory(dotGit)) return dotGit;
            if(Files.isRegularFile(dotGit)) try {
                String pointer=Files.readString(dotGit).strip();
                if(pointer.startsWith("gitdir: ")) {
                    Path target=current.resolve(pointer.substring(8)).normalize();
                    if(Files.isDirectory(target)) return target;
                }
            } catch(IOException ignored) { return null; }
        }
        return null;
    }
    private static String branch(Path folder) {
        Path gitDir=gitDirectory(folder);
        if(gitDir!=null) try {
            String head=Files.readString(gitDir.resolve("HEAD")).strip();
            if(head.startsWith("ref: refs/heads/")) return head.substring(16);
        } catch(IOException ignored) { }
        return "";
    }
    synchronized JsonObject checkout(String projectId, String id) {
        var project=require(projects,projectId,"project");
        if(id.isBlank()) {
            var result=object("id","","projectId",projectId,"directory",text(project,"primaryDirectory"),"kind","primary","managed",project.has("managed") && project.get("managed").getAsBoolean(),"state","active","error","");
            result.addProperty("branch",branch(Path.of(text(result,"directory"))));
            return result;
        }
        var row=require(checkouts,id,"checkout");
        if(!text(row,"projectId").equals(projectId)) throw new IllegalArgumentException("Checkout belongs to another project.");
        var result=row.deepCopy();
        if(Files.isDirectory(Path.of(text(result,"directory")))) result.addProperty("branch",branch(Path.of(text(result,"directory"))));
        return result;
    }
    synchronized JsonObject checkout(String id) { return checkout(text(require(checkouts,id,"checkout"),"projectId"),id); }

    synchronized JsonObject importDirectory(String path, String projectId) throws IOException {
        String canonical = canonicalDirectory(path);
        for (var project : projects.values()) if (text(project,"primaryDirectory").equals(canonical) && (projectId.isBlank() || text(project,"id").equals(projectId))) return checkout(text(project,"id"),"");
        for (var row : checkouts.values()) if (text(row,"directory").equals(canonical) && (projectId.isBlank() || text(row,"projectId").equals(projectId))) return row.deepCopy();
        if (projectId.isBlank()) {
            projectId=UUID.randomUUID().toString();
            Path root=Path.of(canonical);
            projects.put(projectId,object("id",projectId,"name",root.getFileName()==null?canonical:root.getFileName().toString(),"primaryDirectory",canonical,"additionalDirectories",new JsonArray(),"defaults",new JsonObject()));
            save(); return checkout(projectId,"");
        }
        require(projects,projectId,"project");
        String id=UUID.randomUUID().toString();
        var row=object("id",id,"projectId",projectId,"directory",canonical,"kind","local","managed",false,"state","active","error","");
        checkouts.put(id,row); save(); return row.deepCopy();
    }
    private static String canonicalDirectory(String path) throws IOException {
        if(path.isBlank()) throw new IllegalArgumentException("Choose an absolute project directory.");
        Path root=Path.of(path);
        if(!root.isAbsolute()) throw new IllegalArgumentException("Choose an absolute project directory.");
        if(!Files.isDirectory(root)) throw new IllegalArgumentException("Project directory is unavailable: " + root);
        return root.toRealPath().toString();
    }
    synchronized JsonObject minecraft(String worldId) throws IOException {
        if (worldId == null || worldId.isBlank()) throw new IllegalStateException("Enter a world before choosing Minecraft.");
        for (var project : projects.values()) if (text(project,"minecraftWorldId").equals(worldId)) return checkout(text(project,"id"),"");
        Path folder=ManagedStorage.world(worldId); Files.createDirectories(folder);
        var row=importDirectory(folder.toString(),"");
        var project=projects.get(text(row,"projectId"));
        project.addProperty("name","Minecraft"); project.addProperty("minecraftWorldId",worldId); project.addProperty("managed",true);
        save(); return checkout(text(project,"id"),"");
    }
    synchronized JsonObject select(JsonObject spec, String worldId, String parentProject) throws IOException {
        String id = text(spec,"checkoutId"), projectId = text(spec,"projectId");
        if (!parentProject.isBlank()) {
            if (!projectId.isBlank() && !projectId.equals(parentProject)) throw new IllegalArgumentException("Children must stay in their parent's project.");
            projectId = parentProject;
        }
        JsonObject result;
        String path=text(spec,"directory");
        if(!id.isBlank()) {
            result=checkout(id);
        } else if (!path.isBlank()) result=importDirectory(path,projectId);
        else if (!projectId.isBlank()) result=checkout(projectId,id);
        else result=minecraft(worldId);
        if (!projectId.isBlank() && !projectId.equals(text(result,"projectId"))) throw new IllegalArgumentException("Checkout belongs to another project.");
        if (!Files.isDirectory(Path.of(text(result,"directory")))) throw new IllegalArgumentException("Checkout directory is unavailable.");
        return result;
    }
    synchronized JsonObject removeProject(String id) throws IOException {
        var result=project(id);
        projects.remove(id); checkouts.values().removeIf(r -> text(r,"projectId").equals(id)); save(); return result;
    }
    synchronized JsonObject configure(JsonObject request) throws IOException {
        var project = require(projects,text(request,"projectId"),"project").deepCopy();
        if (request.has("primaryDirectory")) {
            if(!text(project,"minecraftWorldId").isBlank()) throw new IllegalArgumentException("Minecraft working folders are managed automatically.");
            project.addProperty("primaryDirectory",canonicalDirectory(text(request,"primaryDirectory")));
        }
        if (request.has("additionalDirectories")) {
            var folders=new LinkedHashSet<String>();
            for(var value:request.getAsJsonArray("additionalDirectories")) folders.add(canonicalDirectory(value.getAsString()));
            folders.remove(text(project,"primaryDirectory")); project.add("additionalDirectories",JSON.toJsonTree(folders));
        }
        if (request.has("name")) {
            String name = text(request,"name").strip();
            if (name.isEmpty() || name.length()>80) throw new IllegalArgumentException("Project name must contain 1–80 characters.");
            project.addProperty("name",name);
        }
        if (request.has("defaults")) {
            var defaults = request.getAsJsonObject("defaults");
            for(var entry : defaults.entrySet()) {
                if(!Set.of("providerId","model","effort","serviceTier").contains(entry.getKey()) || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Execution defaults support providerId, model, effort and serviceTier strings.");
            }
            project.add("defaults",defaults.deepCopy());
        }
        projects.put(text(project,"id"),project);
        save(); return project.deepCopy();
    }

    JsonObject createWorktree(JsonObject request) throws Exception {
        JsonObject source, project, row;
        synchronized(this) {
            project = project(text(request,"projectId"));
            source = checkout(text(project,"id"),text(request,"sourceCheckoutId"));
        }
        Path sourceDirectory=Path.of(text(source,"directory")).toRealPath();
        Path root=Path.of(git(sourceDirectory,"rev-parse","--show-toplevel").strip()).toRealPath();
        Path relativeDirectory=root.relativize(sourceDirectory);
        // Like Claude Code and Codex: start from the current HEAD unless a ref is given.
        String start=text(request,"baseRef");
        if(start.isBlank()) start="HEAD";
        String commit=git(sourceDirectory,"rev-parse","--verify","--end-of-options",start+"^{commit}").strip();
        String id=UUID.randomUUID().toString();
        Path common=Path.of(git(root,"rev-parse","--path-format=absolute","--git-common-dir").strip());
        Path repository=common.getParent();
        String repoName=repository.getFileName()==null?"repository":repository.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]","-");
        if(repoName.isBlank() || repoName.equals(".") || repoName.equals("..")) repoName="repository";
        // Like Claude Code: a short slug of the task plus a few hex characters, e.g. fix-login-redirect-3f2a.
        String name=slug(text(request,"task"))+"-"+id.substring(0,4);
        String branch=text(request,"branch").isBlank()?name:text(request,"branch");
        git(root,"check-ref-format","--branch",branch);
        Path destination=ManagedStorage.root().resolve("worktrees").resolve(name).resolve(repoName);
        Files.createDirectories(destination.getParent());
        try { git(root,"worktree","add","-b",branch,destination.toString(),commit); }
        catch(Exception failure) {
            throw new IOException("Worktree creation failed: " + failure.getMessage() + " Directory: " + destination + "; branch: " + branch + ".",failure);
        }
        synchronized(this) {
            row = object("id",id,"projectId",text(project,"id"),"directory",destination.resolve(relativeDirectory).toString(),"checkoutDirectory",destination.toString(),"kind","worktree","managed",true,
                "sourceCheckoutId",text(source,"id"),"sourceDirectory",sourceDirectory.toString(),"baseRef",start,"baseCommit",commit,"branch",branch,"state","active","error","");
            checkouts.put(id,row); save(); return row.deepCopy();
        }
    }
    private static final Set<String> FILLER = Set.of("a","an","the","to","and","of","for","in","on","with","please","can","could","you","i","we","me","my","our","it","this","that","is","be","some");
    /** Up to five meaningful words of the task, e.g. "Fix the login redirect" -> fix-login-redirect. */
    private static String slug(String task) {
        var words=new ArrayList<String>();
        for(String word:task.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if(!word.isEmpty() && !FILLER.contains(word) && words.size()<5) words.add(word);
        String result=String.join("-",words);
        if(result.length()>40) result=result.substring(0,40).replaceAll("-+$","");
        return result.isEmpty() ? "agent" : result;
    }

    private void save() throws IOException { write(file,object("version",2,"projects",projects.values(),"checkouts",checkouts.values())); }
    static void write(Path file, JsonObject data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(),"state-",".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(JSON.toJson(data).getBytes(StandardCharsets.UTF_8));
                while(bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static String git(Path cwd, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git","-C",cwd.toString())); command.addAll(List.of(args));
        return run(cwd,command);
    }
    private static String run(Path cwd, List<String> command) throws Exception {
        Path output = Files.createTempFile("tma-project-",".log");
        try {
            Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(90,TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroy); process.destroyForcibly();
                throw new IOException("Command timed out; inspect the checkout before retrying.");
            }
            String result = Files.readString(output);
            if (process.exitValue()!=0) throw new IOException(result.length()>4000 ? result.substring(result.length()-4000) : result);
            return result;
        } finally { Files.deleteIfExists(output); }
    }
    private static JsonObject require(Map<String,JsonObject> values,String id,String kind) {
        var value = values.get(id); if (value==null) throw new IllegalArgumentException("Unknown " + kind + ": " + id); return value;
    }
    static String text(JsonObject row,String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : ""; }
    static JsonObject object(Object... pairs) {
        var result = new JsonObject();
        for(int i=0;i<pairs.length;i+=2) result.add((String)pairs[i],JSON.toJsonTree(pairs[i+1]));
        return result;
    }
}
