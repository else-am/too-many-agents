package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import toomanyagents.BodySettings;

/** One scrolling draft of an agent's identity, world behavior, access and roles. Project settings live elsewhere. */
public final class AgentSettingsScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId;
    private final Function<JsonObject,CompletableFuture<Void>> apply;
    private JsonObject draft, saved;
    // UI-only undo state; never sent to BB or saved with a body.
    private JsonObject roleSelection = new JsonObject();
    private static final List<String> ROLE_FIELDS = List.of("body", "mode", "behaviors", "minecraftAccess", "notifyInChat",
        "providerId", "model", "reasoningLevel", "serviceTier", "permissionMode", "worktree", "roleInstructions");
    private boolean busy;
    private boolean bodiesRequested, rolesRequested;
    private final boolean editingRole;
    private List<JsonObject> roleRows = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private String roleName="", selectedRole="", projectId="", stationId="";
    private static final String[][] STATES={{"working","While working"},{"wants_you","Wants you"},{"idle","When idle"}};
    // The block under the crosshair when settings opened; offered as a look/swing target.
    private final BlockPos aimed;
    private final Map<String,String> targetText=new HashMap<>();
    private final Map<String,Integer> behaviorRow=new HashMap<>();

    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply) {
        this(access,parent,settings,apply,null);
    }
    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply,String agentId) {
        this(access,parent,settings,apply,agentId,false);
    }
    private AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply,String agentId,boolean editingRole) {
        super(Component.literal(editingRole?"Edit role":agentId==null?"New agent":"Agent settings"));
        this.editingRole=editingRole;
        this.access=access;this.parent=parent;this.agentId=agentId;this.apply=apply;draft=settings.deepCopy();
        if(!draft.has("minecraftAccess"))draft.addProperty("minecraftAccess",true);
        if(!draft.has("notifyInChat"))draft.addProperty("notifyInChat",false);
        draft.remove("color");
        var source=agentId==null?draft:access.snapshot(agentId);projectId=AgentModels.text(source,"projectId");
        if (agentId == null && AgentModels.worldProject(access.projects(), projectId)) draft.addProperty("minecraftAccess", true);
        var hit=Minecraft.getInstance().hitResult;
        aimed=hit instanceof BlockHitResult block&&hit.getType()==HitResult.Type.BLOCK?block.getBlockPos().immutable():null;
        if(agentId!=null)stationId=assignedStation();
        saved=draft.deepCopy();
    }

    void rememberRole(JsonObject selection) {
        roleSelection = selection;
        selectedRole = AgentModels.text(selection,"name");
        roleName = selectedRole;
    }

    private void editRole(String name) {
        var role=roleRows.stream().filter(row->AgentModels.text(row,"name").equals(name)).findFirst().orElse(null);
        if(role==null)return;
        var settings=BodySettings.profile(AgentModels.object(role,"body"));
        for(var entry:AgentModels.object(role,"bb").entrySet())settings.add(entry.getKey(),entry.getValue().deepCopy());
        settings.addProperty("roleInstructions",AgentModels.text(role,"instructions"));
        settings.addProperty("name",name);
        var editor=new AgentSettingsScreen(access,docked()?minecraft.screen:this,settings,changes->{
            var record=roleRecord(name,changes);
            return access.saveRole(record,null).thenCompose(done->access.roles()).thenAccept(rows->Minecraft.getInstance().execute(()->{
                roleRows.clear();for(var row:rows)roleRows.add(row.getAsJsonObject());rebuildForm();
            }));
        },null,true);
        minecraft.setScreen(editor);
    }

    private static JsonObject roleRecord(String name,JsonObject settings) {
        var role=new JsonObject();role.addProperty("name",name);
        role.add("body",BodySettings.profile(settings));
        var choices=new JsonObject();
        for(String key:List.of("providerId","model","reasoningLevel","serviceTier","permissionMode","worktree"))
            if(settings.has(key)&&!settings.get(key).isJsonNull()&&!settings.get(key).getAsString().isBlank())choices.add(key,settings.get(key).deepCopy());
        role.add("bb",choices);
        if(settings.has("roleInstructions"))role.add("instructions",settings.get("roleInstructions").deepCopy());
        return role;
    }

    public static JsonObject settings(JsonObject snapshot) {
        var result = BodySettings.copy(AgentModels.object(snapshot, "settings"));
        result.add("provider", AgentModels.provider(snapshot).deepCopy());
        for (String key : new String[]{"name", "projectId", "mode", "minecraftAccess"}) {
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
        if (!result.has("minecraftAccess")) result.addProperty("minecraftAccess", true);
        return result;
    }

    private String value(String key){return AgentModels.text(draft,key);}
    private static String bodyLabel(String body) { return body.startsWith("minecraft:") ? body.substring(10) : body; }
    private boolean flag(String key){return draft.has(key)&&draft.get(key).getAsBoolean();}
    private JsonObject agent(){return agentId==null?new JsonObject():access.snapshot(agentId);}
    private boolean editable(){
        var state=agent();String lifecycle=AgentModels.text(state,"lifecycle");
        return !busy && (lifecycle.isBlank()||lifecycle.equals("active"))
            && !(state.has("conversationArchived")&&state.get("conversationArchived").getAsBoolean())
            && !(state.has("bodyRemoved")&&state.get("bodyRemoved").getAsBoolean());
    }
    private boolean valid(){return editable()&&!value("name").isBlank()&&(editingRole||!value("body").isBlank());}
    private void change(String key,String value){draft.addProperty(key,value);rebuildForm();}
    private void change(String key,boolean value){draft.addProperty(key,value);rebuildForm();}

    @Override protected void init(){
        if(!rolesRequested&&!editingRole){
            rolesRequested=true;
            access.roles().whenComplete((rows,failure)->Minecraft.getInstance().execute(()->{
                if(failure!=null){feedback=AgentModels.error(failure);return;}
                roleRows.clear();for(var row:rows)roleRows.add(row.getAsJsonObject());
                if(minecraft!=null&&(docked()||minecraft.screen==this))rebuildForm();
            }));
        }
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
        if (agentId == null && AgentModels.worldProject(access.projects(), projectId)) draft.addProperty("minecraftAccess", true);
        begin();
        boolean enabled=editable(), world=flag("minecraftAccess");
        if(!editingRole){
            section("Role");
            var choices=new ArrayList<Choice>();
            choices.add(new Choice("", "None"));
            for(var role:roleRows)choices.add(new Choice(AgentModels.text(role,"name"),AgentModels.text(role,"name")));
            choice("Load role",selectedRole,choices,this::loadRole,enabled&&(choices.size()>1||!selectedRole.isBlank()));
            if(!selectedRole.isBlank())action("Role preset","Edit "+selectedRole,()->editRole(selectedRole),enabled);
        }
        section(editingRole?"Role · "+value("name"):"Identity");
        if(!editingRole)input("Name",value("name"),80,v->draft.addProperty("name",v),enabled);
        // What the agent is working on, like a chat title; it also names a new worktree.
        if(!editingRole)input("Title",value("title"),80,v->draft.addProperty("title",v),enabled).setHint(Component.literal("From the first message"));
        var bodyChoices = new ArrayList<Choice>();
        if (!bodies.contains(value("body"))) bodyChoices.add(new Choice(value("body"), bodyLabel(value("body"))));
        for (String body : bodies) bodyChoices.add(new Choice(body, bodyLabel(body)));
        choice("Body", value("body"), bodyChoices, v -> change("body", v), enabled);
        section("In the world");
        toggle("Notify in chat",flag("notifyInChat"),v->change("notifyInChat",v),enabled)
            .setTooltip(Tooltip.create(Component.literal("Show final replies and questions in Minecraft chat. Click a message to open the conversation.")));
        if (editingRole || !AgentModels.worldProject(access.projects(), projectId)) {
            if(agentId==null) toggle("Minecraft access",world,v->change("minecraftAccess",v),enabled);
            else value("Minecraft access",world?"On - fixed at spawn":"Off - fixed at spawn");
        }
        if(agentId!=null&&!stations().isEmpty())stationRow(enabled);
        behaviorRows(enabled);
        choice("Game mode",value("mode").isBlank()?"survival":value("mode"),java.util.Arrays.stream(toomanyagents.BodySettings.Mode.values()).map(m -> new Choice(m.id,m.label)).toList(),v->change("mode",v),enabled&&world);
        if(agentId==null)section(editingRole?"Execution":"Access");
        if(agentId==null&&!editingRole){
            var permissionChoices = new ArrayList<Choice>();
            permissionChoices.add(new Choice("", "BB default"));
            for (String mode : AgentModels.permissionModes(AgentModels.provider(draft)))
                permissionChoices.add(new Choice(mode, AgentModels.permissionLabel(mode)));
            choice("Approvals", value("permissionMode"), permissionChoices, v -> change("permissionMode", v), enabled && permissionChoices.size()>1);
        }
        // Left unset for new agents so the service default applies.

        if(editingRole){
            input("Provider",value("providerId"),80,v->draft.addProperty("providerId",v),enabled);
            input("Model",value("model"),120,v->draft.addProperty("model",v),enabled);
            choice("Reasoning",value("reasoningLevel"),options("","BB default","none","None","low","Low","medium","Medium","high","High","xhigh","Extra high","max","Max","ultra","Ultra","ultracode","Ultracode"),v->change("reasoningLevel",v),enabled);
            toggle("Worktree",flag("worktree"),v->change("worktree",v),enabled);
            section("Instructions");
            var field=new MultiLineEditBox(font,left,0,contentWidth,92,Component.literal("How this agent should work…"),Component.literal("Role instructions")) {
                @Override public boolean mouseClicked(double x,double y,int button) {
                    return visible && y>=formTop && y<formBottom && super.mouseClicked(x,y,button);
                }
            };
            field.setCharacterLimit(64000);field.setValue(value("roleInstructions"));field.setValueListener(v->draft.addProperty("roleInstructions",v));field.active=enabled;
            place(field,rowY);rowY+=100;
        } else {
            section("Save a role");
            int y=row("Role name");
            var nameField=new EditBox(font,controlX,0,controlWidth-66,20,Component.literal("Role name"));
            nameField.setMaxLength(80);nameField.setValue(roleName);nameField.setResponder(v->roleName=v);nameField.setEditable(enabled);
            nameField.setHint(Component.literal("Role name"));place(nameField,y);
            var save=Button.builder(Component.literal("Save"),b->saveRole()).bounds(controlX+controlWidth-60,0,60,20).build();
            save.active=enabled;place(save,y);
        }
        done();
    }
    private List<JsonObject> stations(){
        var result=new ArrayList<JsonObject>();
        for(var item:AgentModels.array(access.projects().getAsJsonObject("world"),"stations"))
            if(AgentModels.text(item.getAsJsonObject(),"projectId").equals(projectId))result.add(item.getAsJsonObject());
        return result;
    }
    private String assignedStation(){
        for(var station:stations())if(AgentModels.text(station,"agentId").equals(agentId))return AgentModels.text(station,"id");
        return "";
    }
    private void stationRow(boolean enabled){
        var names=new HashMap<String,String>();
        for(var item:access.worldAgents())names.put(AgentModels.text(item.getAsJsonObject(),"id"),AgentModels.text(item.getAsJsonObject(),"name"));
        var choices=new ArrayList<Choice>();choices.add(new Choice("","None"));
        for(var station:stations()){
            String occupant=AgentModels.text(station,"agentId");
            boolean free=occupant.isBlank()||occupant.equals(agentId);
            choices.add(new Choice(AgentModels.text(station,"id"),AgentModels.text(station,"label")+" - "+where(station)+(free?"":" - "+names.getOrDefault(occupant,"occupied")),free));
        }
        choice("Station",stationId,choices,id->{stationId=id;rebuildForm();},enabled&&choices.size()>1);
    }
    private static String where(JsonObject station){
        var min=AgentModels.array(station,"min");var max=AgentModels.array(station,"max");
        int w=max.get(0).getAsInt()-min.get(0).getAsInt()+1,d=max.get(2).getAsInt()-min.get(2).getAsInt()+1;
        String at=min.get(0).getAsInt()+" "+min.get(1).getAsInt()+" "+min.get(2).getAsInt();
        String dimension=AgentModels.text(station,"dimension");
        return (w==1&&d==1?at:w+"×"+d+" at "+at)+(dimension.equals("minecraft:overworld")||dimension.isBlank()?"":" - "+dimension.substring(dimension.indexOf(':')+1));
    }
    // Stations are world data rather than agent settings, so the draft's choice is applied after the settings save.
    private CompletableFuture<Void> saveStation(){
        String current=agentId==null?"":assignedStation();
        if(agentId==null||stationId.equals(current))return CompletableFuture.completedFuture(null);
        var request=new JsonObject();request.addProperty("operation","station-assign");
        request.addProperty("stationId",stationId.isBlank()?current:stationId);request.addProperty("agentId",stationId.isBlank()?"":agentId);
        return access.projectCommand(request).thenApply(result->null);
    }
    private void behaviorRows(boolean enabled){
        for(String[] state:STATES){
            var behavior=behavior(state[0]);String type=AgentModels.text(behavior,"type");
            String kind=type.isBlank()?"stand":type.equals("look")&&behavior.get("target") instanceof com.google.gson.JsonPrimitive p&&p.getAsString().equals("player")?"look-player":type;
            behaviorRow.put(state[0],rowY);
            choice(state[1],kind,options("stand","Stand","wander","Wander","follow","Follow me","jump","Jump","spin","Spin","look-player","Look at me","look","Look at block","swing","Swing at block"),v->setBehavior(state[0],v),enabled);
            if(kind.equals("look")||kind.equals("swing"))targetRow(state[0],behavior,enabled);
        }
    }
    private JsonObject behavior(String state){
        var all=draft.get("behaviors");
        return all!=null&&all.isJsonObject()&&all.getAsJsonObject().get(state) instanceof JsonObject b?b:new JsonObject();
    }
    private void setBehavior(String state,String kind){
        if(!draft.has("behaviors")||!draft.get("behaviors").isJsonObject())draft.add("behaviors",new JsonObject());
        var all=draft.getAsJsonObject("behaviors");var previous=behavior(state).get("target");targetText.remove(state);
        if(kind.equals("stand")){all.remove(state);rebuildForm();return;}
        var behavior=new JsonObject();behavior.addProperty("type",kind.equals("look-player")?"look":kind);
        if(kind.equals("look-player"))behavior.addProperty("target","player");
        else if(kind.equals("look")||kind.equals("swing")){
            if(previous!=null&&previous.isJsonObject())behavior.add("target",previous.deepCopy());
            else if(aimed!=null)behavior.add("target",target(aimed));
        }
        all.add(state,behavior);rebuildForm();
    }
    private void targetRow(String state,JsonObject behavior,boolean enabled){
        BlockPos pos=behavior.get("target") instanceof JsonObject t?new BlockPos(t.get("x").getAsInt(),t.get("y").getAsInt(),t.get("z").getAsInt()):null;
        int y=row("   ↳ "+(pos==null?"Choose a block":blockName(pos)));
        var field=new EditBox(font,controlX,0,controlWidth-24,20,Component.literal(state+" block"));
        field.setMaxLength(48);field.setValue(targetText.getOrDefault(state,pos==null?"":coordinates(pos)));field.setHint(Component.literal("x y z"));
        field.setResponder(v->{
            targetText.put(state,v);var target=parseTarget(v);var live=behavior(state);
            if(target==null)live.remove("target");else live.add("target",target);
        });
        field.setEditable(enabled);place(field,y);
        var pick=new Button(controlX+controlWidth-20,0,20,20,Component.literal("Use targeted block"),b->{
            targetText.remove(state);behavior(state).add("target",target(aimed));rebuildForm();
        },n->n.get()){
            @Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta){
                g.fill(getX(),getY(),getX()+20,getY()+20,active&&isHoveredOrFocused()?0xFFE2D4A7:0xFF555555);
                g.fill(getX()+1,getY()+1,getX()+19,getY()+19,0xFF1A1A1A);
                var item=aimed==null||minecraft.level==null?Items.AIR:minecraft.level.getBlockState(aimed).getBlock().asItem();
                if(item!=Items.AIR){g.renderItem(new ItemStack(item),getX()+2,getY()+2);return;}
                int c=active?0xFFE2D4A7:0xFF777777,cx=getX()+10,cy=getY()+10;
                g.fill(cx-6,cy,cx-2,cy+1,c);g.fill(cx+3,cy,cx+7,cy+1,c);g.fill(cx,cy-6,cx+1,cy-2,c);g.fill(cx,cy+3,cx+1,cy+7,c);
            }
        };
        pick.setTooltip(Tooltip.create(Component.literal(aimed==null?"Look at a block, then reopen settings to pick it":"Use "+blockName(aimed)+" at "+coordinates(aimed))));
        pick.active=enabled&&aimed!=null;place(pick,y);
    }
    private String blockName(BlockPos pos){
        var state=minecraft==null||minecraft.level==null?null:minecraft.level.getBlockState(pos);
        return state==null||state.isAir()?"Block":state.getBlock().getName().getString();
    }
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static JsonObject target(BlockPos pos){var o=new JsonObject();o.addProperty("x",pos.getX());o.addProperty("y",pos.getY());o.addProperty("z",pos.getZ());return o;}
    private static JsonObject parseTarget(String text){
        var parts=text.strip().split("[\\s,]+");if(parts.length!=3)return null;
        try{return target(new BlockPos(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])));}
        catch(NumberFormatException e){return null;}
    }
    /** Block behaviors need a target before saving; names the first state missing one. */
    private boolean targetsChosen(){
        for(String[] state:STATES){
            var behavior=behavior(state[0]);String type=AgentModels.text(behavior,"type");
            if((type.equals("look")||type.equals("swing"))&&!behavior.has("target")){feedback="Choose a block for "+state[1].toLowerCase()+".";reveal(behaviorRow.getOrDefault(state[0],0));return false;}
        }
        return true;
    }
    private void loadRole(String name){
        if (!roleSelection.has("before") && !name.isBlank()) roleSelection.add("before",draft.deepCopy());
        if (roleSelection.get("before") instanceof JsonObject before) {
            for (String key : ROLE_FIELDS) {
                draft.remove(key);
                if (before.has(key)) draft.add(key,before.get(key).deepCopy());
            }
        }
        targetText.clear();roleName=selectedRole=name;
        roleSelection.addProperty("name",name);
        if(name.isBlank()) {
            roleSelection.remove("before");
            feedback="";rebuildForm();return;
        }
        var role=roleRows.stream().filter(p->AgentModels.text(p,"name").equals(name)).findFirst().orElseThrow();
        var settings=BodySettings.profile(AgentModels.object(role,"body"));
        if(agentId!=null)settings.remove("minecraftAccess");
        for(var entry:settings.entrySet())draft.add(entry.getKey(),entry.getValue().deepCopy());
        for(var entry:AgentModels.object(role,"bb").entrySet())
            if(agentId==null||List.of("model","reasoningLevel").contains(entry.getKey()))draft.add(entry.getKey(),entry.getValue().deepCopy());
        draft.addProperty("roleInstructions",AgentModels.text(role,"instructions"));
        feedback=agentId==null?"Role loaded.":"Body, model and reasoning loaded. Other role choices apply at spawn.";
        rebuildForm();
    }
    private void saveRole(){
        if(!valid()||roleName.isBlank()){feedback="Enter a role name and valid agent settings first.";return;}
        if(!targetsChosen())return;
        var record=roleRecord(roleName.trim(),draft);
        run(access.saveRole(record,agentId).thenRun(()->Minecraft.getInstance().execute(()->{selectedRole=roleName.trim();roleSelection.addProperty("name",selectedRole);rolesRequested=false;})),"Role saved.");
    }
    private void run(CompletableFuture<Void> future,String success){
        busy=true;rebuildForm();future.whenComplete((unused,error)->Minecraft.getInstance().execute(()->{busy=false;feedback=error==null?success:AgentModels.error(error);rebuildForm();}));
    }
    private void apply(){
        if(!valid()){feedback="Enter a name and choose a body.";cancelClose();return;}
        if(!targetsChosen()){cancelClose();return;}
        var changes=agentId==null?draft.deepCopy():BodySettings.copy(draft);
        if(agentId!=null){
            for(String key:List.of("model","reasoningLevel"))
                if(!value(key).equals(AgentModels.text(saved,key)))changes.addProperty(key,value(key));
            if(!value("title").equals(AgentModels.text(saved,"title")))changes.addProperty("title",value("title"));
            changes.remove("minecraftAccess");
        }
        busy=true;rebuildForm();apply.apply(changes).thenCompose(unused->saveStation()).whenComplete((unused,error)->Minecraft.getInstance().execute(()->{
            busy=false;if(error==null)leave(parent);else{cancelClose();feedback=AgentModels.error(error);rebuildForm();}
        }));
    }
    // Changes save on the way out.
    @Override public void onClose(){
        if(busy)return;
        if(!editable()||draft.equals(saved)&&(agentId==null||stationId.equals(assignedStation())))leave(parent);
        else apply();
    }
}
