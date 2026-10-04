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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import toomanyagents.AgentColor;
import toomanyagents.BodySettings;

/** One scrolling draft of an agent's identity, world behavior, access and profiles. Project settings live elsewhere. */
public final class AgentSettingsScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId;
    private final Function<JsonObject,CompletableFuture<Void>> apply;
    private JsonObject draft, saved;
    private boolean busy, followEdited;
    private boolean bodiesRequested;
    private final List<String> bodies = new ArrayList<>();
    private String profileName="", selectedProfile="", projectId="", stationId="";
    private static final String[][] STATES={{"working","While working"},{"needs_input","Needing input"},{"done","When done"},{"idle","When idle"}};
    // The block under the crosshair when settings opened; offered as a look/swing target.
    private final BlockPos aimed;
    private final Map<String,String> targetText=new HashMap<>();
    private final Map<String,Integer> behaviorRow=new HashMap<>();

    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply) {
        this(access,parent,settings,apply,null);
    }
    public AgentSettingsScreen(AgentUiAccess access,Screen parent,JsonObject settings,Function<JsonObject,CompletableFuture<Void>> apply,String agentId) {
        super(Component.literal(agentId==null?"New agent":"Agent settings"));
        this.access=access;this.parent=parent;this.agentId=agentId;this.apply=apply;draft=settings.deepCopy();
        if(!draft.has("minecraftAccess"))draft.addProperty("minecraftAccess",true);
        if(!AgentColor.valid(value("color")))draft.addProperty("color",AgentColor.forId(value("name")));
        var source=agentId==null?draft:access.snapshot(agentId);projectId=AgentModels.text(source,"projectId");
        var hit=Minecraft.getInstance().hitResult;
        aimed=hit instanceof BlockHitResult block&&hit.getType()==HitResult.Type.BLOCK?block.getBlockPos().immutable():null;
        if(agentId!=null)stationId=assignedStation();
        saved=draft.deepCopy();
    }

    public static JsonObject settings(JsonObject snapshot) {
        var result = BodySettings.copy(AgentModels.object(snapshot, "settings"));
        result.add("provider", AgentModels.provider(snapshot).deepCopy());
        for (String key : new String[]{"name", "projectId", "following", "mode", "cheats", "minecraftAccess", "communication"}) {
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
    private boolean valid(){return editable()&&!value("name").isBlank()&&!value("body").isBlank()&&AgentColor.valid(value("color"));}
    private void change(String key,String value){draft.addProperty(key,value);rebuildForm();}
    private void change(String key,boolean value){draft.addProperty(key,value);rebuildForm();}

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
        boolean enabled=editable(), world=flag("minecraftAccess");
        section("Identity");
        input("Name",value("name"),80,v->draft.addProperty("name",v),enabled);
        // What the agent is working on, like a chat title; it also names a new worktree.
        input("Title",value("title"),80,v->draft.addProperty("title",v),enabled).setHint(Component.literal("From the first message"));
        var bodyChoices = new ArrayList<Choice>();
        if (!bodies.contains(value("body"))) bodyChoices.add(new Choice(value("body"), bodyLabel(value("body"))));
        for (String body : bodies) bodyChoices.add(new Choice(body, bodyLabel(body)));
        choice("Body", value("body"), bodyChoices, v -> change("body", v), enabled);
        colorRow(enabled);
        section("In the world");
        if(agentId==null) {
            toggle("Minecraft access",world,v->change("minecraftAccess",v),enabled);
        } else value("Minecraft access",world?"On - fixed at spawn":"Off - fixed at spawn");
        if(agentId!=null&&!stations().isEmpty())stationRow(enabled);
        toggle("Follow me",flag("following"),v->{followEdited=true;change("following",v);},enabled);
        if(AgentModels.text(agent(),"followPauseReason").equals("work"))action("Paused while working","Resume following",this::resumeFollow,enabled);
        if(flag("following"))choice("After world work",value("followReturn").isBlank()?"previous":value("followReturn"),
            options("previous","Restore follow state","always","Always follow"),v->change("followReturn",v),enabled&&world);
        behaviorRows(enabled);
        choice("Game mode",value("mode").isBlank()?"survival":value("mode"),options("survival","Survival","creative","Creative"),v->change("mode",v),enabled&&world);
        toggle("World commands",flag("cheats"),v->change("cheats",v),enabled&&world)
            .setTooltip(Tooltip.create(Component.literal("Allow command-based edits in any game mode")));
        section("Access");
        if(agentId==null){
            var permissionChoices = new ArrayList<Choice>();
            permissionChoices.add(new Choice("", "BB default"));
            for (String mode : AgentModels.permissionModes(AgentModels.provider(draft)))
                permissionChoices.add(new Choice(mode, AgentModels.permissionLabel(mode)));
            choice("Approvals", value("permissionMode"), permissionChoices, v -> change("permissionMode", v), enabled && permissionChoices.size()>1);
        }
        // Left unset for new agents so the service default applies.
        choice("Communication",value("communication").isBlank()?"project":value("communication"),
            options("none","Off","children","Parent & children","project","This project","any","All projects"),v->change("communication",v),enabled);
        section("Body profiles");
        var profiles=profiles();var choices=new ArrayList<Choice>();
        for(var profile:profiles)choices.add(new Choice(AgentModels.text(profile,"name"),AgentModels.text(profile,"name")));
        choice("Load profile",selectedProfile,choices,this::loadProfile,enabled&&profiles.size()>0);
        int y=row("Save as profile");
        var nameField=new EditBox(font,controlX,0,controlWidth-66,20,Component.literal("Profile name"));
        nameField.setMaxLength(80);nameField.setValue(profileName);nameField.setResponder(v->profileName=v);nameField.setEditable(enabled);
        nameField.setHint(Component.literal("Profile name"));place(nameField,y);
        var save=Button.builder(Component.literal("Save"),b->saveProfile()).bounds(controlX+controlWidth-60,0,60,20).build();
        save.active=enabled;place(save,y);
        done();
    }
    private void colorRow(boolean enabled){
        var palette=List.of("#F2627D","#E6AC62","#E1D48B","#91C99B","#77BFC7","#80A5E0","#B99ADC","#D1D1D1");
        int y=row("Color"), step=Math.min(24,(controlWidth-60)/palette.size()), size=step-4;
        for(int i=0;i<palette.size();i++){
            String hex=palette.get(i);
            var swatch=new Button(controlX+i*step,0,size,20,Component.literal(hex),b->change("color",hex),n->n.get()){
                @Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta){
                    boolean chosen=value("color").equalsIgnoreCase(hex);
                    if(chosen||isHoveredOrFocused())g.fill(getX(),getY(),getX()+getWidth(),getY()+20,chosen?0xFFFFFFFF:0xFF777777);
                    g.fill(getX()+2,getY()+2,getX()+getWidth()-2,getY()+18,0xFF000000|AgentColor.rgb(hex));
                }
            };
            swatch.active=enabled;place(swatch,y);
        }
        int x=controlX+palette.size()*step+2;
        var field=new EditBox(font,x,0,Math.min(64,controlX+controlWidth-x),20,Component.literal("Color"));
        field.setMaxLength(7);field.setValue(value("color"));field.setEditable(enabled);field.setTextColor(AgentColor.rgb(value("color")));
        field.setResponder(v->{draft.addProperty("color",v);field.setTextColor(AgentColor.valid(v)?AgentColor.rgb(v):0xE0E0E0);});
        place(field,y);
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
            choice(state[1],kind,options("stand","Stand","wander","Wander","look-player","Look at me","look","Look at block","swing","Swing at block"),v->setBehavior(state[0],v),enabled);
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
        else if(!kind.equals("wander")){
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
    private List<JsonObject> profiles(){
        var result=new ArrayList<JsonObject>();
        for(var value:access.profiles())result.add(value.getAsJsonObject());
        return result;
    }
    private void loadProfile(String name){
        if(name.isBlank())return;
        var profile=profiles().stream().filter(p->AgentModels.text(p,"name").equals(name)).findFirst().orElseThrow();
        var settings=BodySettings.profile(profile.getAsJsonObject("settings"));
        if(agentId!=null)settings.remove("minecraftAccess");
        if(settings.has("following"))followEdited=true;
        for(var entry:settings.entrySet())draft.add(entry.getKey(),entry.getValue());
        targetText.clear();profileName=selectedProfile=name;feedback="Body profile loaded into draft.";rebuildForm();
    }
    private void saveProfile(){
        if(!valid()||profileName.isBlank()){feedback="Enter a profile name and valid agent settings first.";return;}
        if(!targetsChosen())return;
        run(access.saveProfile(profileName.trim(),BodySettings.profile(draft)),"Body profile saved.");
    }
    private void resumeFollow(){
        followEdited=true;draft.addProperty("following",true);
        run(access.setFollowing(agentId,true),"Following resumed.");
    }
    private void run(CompletableFuture<Void> future,String success){
        busy=true;rebuildForm();future.whenComplete((unused,error)->Minecraft.getInstance().execute(()->{busy=false;feedback=error==null?success:AgentModels.error(error);rebuildForm();}));
    }
    private void apply(){
        if(!valid()){feedback="Enter a name and choose a body.";cancelClose();return;}
        if(!targetsChosen()){cancelClose();return;}
        var changes=agentId==null?draft.deepCopy():BodySettings.copy(draft);
        if(agentId!=null){
            if(!value("title").equals(AgentModels.text(saved,"title")))changes.addProperty("title",value("title"));
            changes.remove("minecraftAccess");if(!followEdited)changes.remove("following");
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
