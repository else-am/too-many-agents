package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import toomanyagents.BodySettings;

/**
 * One scrolling draft of an agent's identity and world behavior. Minecraft access follows the project: world agents
 * have a game mode, project agents don't. Project settings live elsewhere.
 */
public final class AgentSettingsScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId;
    private final Function<JsonObject,CompletableFuture<Void>> apply;
    private JsonObject draft, saved;
    private boolean busy, bodiesRequested;
    private final List<String> bodies = new ArrayList<>();
    private String projectId="";
    private static final String[][] STATES={{"working","While working"},{"wants_you","Wants you"},{"idle","When idle"}};

    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply) {
        this(access,parent,settings,apply,null);
    }
    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply,String agentId) {
        super(Component.literal(agentId==null?"New agent":"Agent settings"));
        this.access=access;this.parent=parent;this.agentId=agentId;this.apply=apply;draft=settings.deepCopy();
        draft.remove("color");
        var source=agentId==null?draft:access.snapshot(agentId);projectId=AgentModels.text(source,"projectId");
        saved=draft.deepCopy();
    }

    public static JsonObject settings(JsonObject snapshot) {
        var result = BodySettings.copy(AgentModels.object(snapshot, "settings"));
        result.add("provider", AgentModels.provider(snapshot).deepCopy());
        for (String key : new String[]{"name", "projectId", "mode"}) {
            if (!result.has(key) && snapshot.has(key)) result.add(key, snapshot.get(key).deepCopy());
        }
        var execution = AgentModels.execution(snapshot);
        for (String key : new String[]{"model", "reasoningLevel", "serviceTier", "permissionMode"})
            if (execution.has(key)) result.add(key, execution.get(key).deepCopy());
        if (snapshot.has("providerId")) result.add("providerId",snapshot.get("providerId").deepCopy());
        if (!result.has("title")) result.addProperty("title", AgentModels.text(snapshot, "taskTitle"));
        if (!result.has("body")) {
            String body = AgentModels.text(snapshot, "bodyType");
            if (body.isBlank() && snapshot.has("body") && snapshot.get("body").isJsonObject()) body = AgentModels.text(snapshot.getAsJsonObject("body"), "type");
            result.addProperty("body", body);
        }
        return result;
    }

    private String value(String key){return AgentModels.text(draft,key);}
    private static String bodyLabel(String body) { return body.startsWith("minecraft:") ? body.substring(10) : body; }
    private JsonObject agent(){return agentId==null?new JsonObject():access.snapshot(agentId);}
    private boolean editable(){
        var state=agent();String lifecycle=AgentModels.text(state,"lifecycle");
        return !busy && (lifecycle.isBlank()||lifecycle.equals("active"))
            && !(state.has("conversationArchived")&&state.get("conversationArchived").getAsBoolean())
            && !(state.has("bodyRemoved")&&state.get("bodyRemoved").getAsBoolean());
    }
    private boolean valid(){return editable()&&!value("name").isBlank()&&!value("body").isBlank();}
    private void change(String key,String value){draft.addProperty(key,value);rebuildForm();}

    @Override protected void init(){
        if (!bodiesRequested) {
            bodiesRequested = true;
            String provider = AgentModels.text(agent(), "providerId");
            if (provider.isBlank()) provider = value("providerId");
            access.catalog(provider,AgentModels.text(AgentModels.object(agent(),"thread"),"environmentId")).whenComplete((catalog, failure) ->
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        bodies.clear();
                        for (var body : AgentModels.array(catalog, "bodies")) bodies.add(body.getAsString());
                        bodies.sort(String::compareTo);
                        if (minecraft != null && (docked() || minecraft.screen == this)) rebuildForm();
                    }
                }));
        }
        begin();
        boolean enabled=editable(), world=AgentModels.worldProject(access.projects(), projectId);
        section("Identity");
        input("Name",value("name"),80,v->draft.addProperty("name",v),enabled);
        // What the agent is working on, like a chat title; it also names a new worktree.
        input("Title",value("title"),80,v->draft.addProperty("title",v),enabled).setHint(Component.literal("From the first message"));
        var bodyChoices = new ArrayList<Choice>();
        if (!bodies.contains(value("body"))) bodyChoices.add(new Choice(value("body"), bodyLabel(value("body"))));
        for (String body : bodies) bodyChoices.add(new Choice(body, bodyLabel(body)));
        choice("Body", value("body"), bodyChoices, v -> change("body", v), enabled);
        section("In the world");
        String station=stationName();
        if(!station.isBlank())value("Station","At "+station);
        if(world) choice("Behavior",value("behavior").isBlank()?"stand":value("behavior"),
            options("stand","Stand","wander","Wander","follow","Follow me"),v->change("behavior",v),enabled);
        else if(!station.isBlank()) for(String[] state:STATES) value(state[1],"Set by "+station);
        else behaviorRows(enabled);
        if(world)choice("Game mode",value("mode").isBlank()?"survival":value("mode"),java.util.Arrays.stream(BodySettings.Mode.values()).map(m -> new Choice(m.id,m.label)).toList(),v->change("mode",v),enabled);
        if(agentId==null){
            section("Access");
            var permissionChoices = new ArrayList<Choice>();
            permissionChoices.add(new Choice("", "BB default"));
            for (String mode : AgentModels.permissionModes(AgentModels.provider(draft)))
                permissionChoices.add(new Choice(mode, AgentModels.permissionLabel(mode)));
            choice("Approvals", value("permissionMode"), permissionChoices, v -> change("permissionMode", v), enabled && permissionChoices.size()>1);
        }
        done();
    }
    private String stationName(){
        String id=AgentModels.text(agentId==null?draft:agent(),"stationId");
        if(id.isBlank())return "";
        for(var item:AgentModels.array(access.projects().getAsJsonObject("world"),"stations"))
            if(AgentModels.text(item.getAsJsonObject(),"id").equals(id))return AgentModels.text(item.getAsJsonObject(),"name");
        return "";
    }
    private void behaviorRows(boolean enabled){
        for(String[] state:STATES){
            var behavior=behavior(state[0]);String type=AgentModels.text(behavior,"type");
            String kind=type.isBlank()?"stand":type.equals("look")?"look-player":type;
            choice(state[1],kind,options("stand","Stand","wander","Wander","follow","Follow me","jump","Jump","spin","Spin","look-player","Look at me"),v->setBehavior(state[0],v),enabled);
        }
    }
    private JsonObject behavior(String state){
        var all=draft.get("behaviors");
        return all!=null&&all.isJsonObject()&&all.getAsJsonObject().get(state) instanceof JsonObject b?b:new JsonObject();
    }
    private void setBehavior(String state,String kind){
        if(!draft.has("behaviors")||!draft.get("behaviors").isJsonObject())draft.add("behaviors",new JsonObject());
        var all=draft.getAsJsonObject("behaviors");
        if(kind.equals("stand")){all.remove(state);rebuildForm();return;}
        var behavior=new JsonObject();behavior.addProperty("type",kind.equals("look-player")?"look":kind);
        if(kind.equals("look-player"))behavior.addProperty("target","player");
        all.add(state,behavior);rebuildForm();
    }
    private void apply(){
        if(!valid()){feedback="Enter a name and choose a body.";cancelClose();return;}
        var changes=agentId==null?draft.deepCopy():BodySettings.copy(draft);
        BodySettings.forProject(changes,AgentModels.worldProject(access.projects(),projectId));
        if(agentId!=null){
            for(String key:List.of("model","reasoningLevel"))
                if(!value(key).equals(AgentModels.text(saved,key)))changes.addProperty(key,value(key));
            if(!value("title").equals(AgentModels.text(saved,"title")))changes.addProperty("title",value("title"));
        }
        busy=true;rebuildForm();apply.apply(changes).whenComplete((unused,error)->net.minecraft.client.Minecraft.getInstance().execute(()->{
            busy=false;if(error==null)leave(parent);else{cancelClose();feedback=AgentModels.error(error);rebuildForm();}
        }));
    }
    // Changes save on the way out.
    @Override public void onClose(){
        if(busy)return;
        if(!editable()||draft.equals(saved))leave(parent);
        else apply();
    }
}
