package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** One placed station, with future spawn defaults and immediately applied area/behavior settings. */
public final class StationScreen extends BlockSettingsScreen {
    private JsonObject draft, saved;
    private String areaWidth, areaHeight;
    private JsonObject catalog=new JsonObject();
    private String catalogKey=null,effectiveProvider="",inheritedModel="";
    private boolean loadingCatalog;
    private String liveLinks="";
    private int ticks;
    private final AgentModels models=new AgentModels();
    private static final String[] FIELDS={"name","projectId","providerId","model","reasoningLevel","serviceTier","permissionMode","instructions","behaviors","radius","height","body"};
    public StationScreen(AgentUiAccess access,Screen parent,JsonObject station) {
        super("Station",access,parent,position(station),AgentModels.text(station,"dimension"),AgentModels.text(station,"id"));
        draft=station.deepCopy();saved=fields();
        areaWidth=Integer.toString(draft.get("radius").getAsInt()*2+1);areaHeight=draft.get("height").getAsString();
    }
    private static BlockPos position(JsonObject station) { var p=station.getAsJsonArray("pos");return new BlockPos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt()); }
    public static JsonObject find(AgentUiAccess access,String id) {
        for(var item:AgentModels.array(AgentModels.object(access.projects(),"world"),"stations")) {
            var row=item.getAsJsonObject();if(AgentModels.text(row,"id").equals(id)||AgentModels.text(row,"name").equalsIgnoreCase(id))return row.deepCopy();
        }
        throw new IllegalArgumentException("Station not found: "+id);
    }
    private String text(String key) { return AgentModels.text(draft,key); }
    private JsonObject fields() { var fields=new JsonObject();for(String key:FIELDS)if(draft.has(key))fields.add(key,draft.get(key).deepCopy());return fields; }
    private void change(String key,String value) { draft.addProperty(key,value); }
    private String occupant() {
        for(var item:AgentModels.array(AgentModels.object(access.projects(),"world"),"stations"))
            if(AgentModels.text(item.getAsJsonObject(),"id").equals(id))return AgentModels.text(item.getAsJsonObject(),"agentId");
        return text("agentId");
    }
    @Override protected void init() {
        requestCatalog();
        liveLinks=live();
        begin();boolean enabled=!busy,occupied=!occupant().isBlank();
        section("Station");
        input("Name",text("name"),32,v->change("name",v),enabled);
        choice("Project",text("projectId"),projects(),v->{change("projectId",v);rebuildForm();},enabled&&!occupied);
        if(occupied) {
            String agent=occupant(),name=AgentModels.text(access.snapshot(agent),"name");
            action("Occupant",name.isBlank()?agent:name,()->saveThen(()->minecraft.setScreen(new AgentChatScreen(access,agent))),enabled);
            note("Project stays fixed while occupied. Archive the agent to free this station.");
        } else action("Agent","Spawn agent here",()->saveThen(this::spawn),enabled&&!text("projectId").isBlank());
        section("Area");
        input("Width (odd, 1–99)",areaWidth,2,v->areaWidth=v,enabled).setHint(Component.literal("3 = 3×3"));
        input("Height (1–99)",areaHeight,2,v->areaHeight=v,enabled);
        section("Behaviors");
        for(String[] state:new String[][]{{"working","While working"},{"wants_you","Wants you"},{"idle","When idle"}}) {
            String kind=AgentModels.text(AgentModels.object(AgentModels.object(draft,"behaviors"),state[0]),"type");
            choice(state[1],kind.isBlank()?"stand":kind,options("stand","Stand","wander","Wander","follow","Follow me","jump","Jump","spin","Spin","look","Look at me"),v->{
                var all=AgentModels.object(draft,"behaviors").deepCopy();var behavior=new JsonObject();behavior.addProperty("type",v);
                if(v.equals("look"))behavior.addProperty("target","player");all.add(state[0],behavior);draft.add("behaviors",all);rebuildForm();
            },enabled);
        }
        section(occupied?"Execution · next spawn":"Execution");
        note("Leave blank to use BB’s project defaults. Filled fields are pinned.");
        executionRows(enabled);
        var bodies=defaults("Random");
        for(var body:AgentModels.array(catalog,"bodies"))bodies.add(new Choice(body.getAsString(),body.getAsString().replace("minecraft:","")));
        pinnedChoice(occupied?"Body · next spawn":"Body","body",bodies,enabled&&!loadingCatalog);
        section(occupied?"Instructions · next spawn":"Instructions");
        var instructions=new MultiLineEditBox(font,left,0,contentWidth,84,Component.literal("Prepended to the first task"),Component.literal("Instructions"));
        instructions.setCharacterLimit(64000);instructions.setValue(text("instructions"));instructions.setValueListener(v->change("instructions",v));instructions.active=enabled;
        place(instructions,rowY);rowY+=90;
        section("Linked screens");
        boolean linked=false;
        for(var item:AgentModels.array(AgentModels.object(access.projects(),"world"),"screenLinks")) {
            var link=item.getAsJsonObject();if(!AgentModels.text(link,"stationId").equals(id))continue;
            value(AgentModels.text(link,"screenName"),AgentModels.text(link,"screenId"));linked=true;
        }
        if(!linked)note("No linked screens. Place a screen inside the area to link it.");
        section("Copy settings");
        action("Station item","Copy station item…",()->saveThen(()->run("items",null,r->{})),enabled);
        note("One station item in, one copy out. No identity or occupant is copied.");
        action("","Apply",()->saveThen(()->feedback="Saved"),enabled);
        action("","Close without saving",this::discard,enabled);
        done();
    }
    private java.util.ArrayList<Choice> defaults(String label) {
        var choices=new java.util.ArrayList<Choice>();choices.add(new Choice("",label));return choices;
    }
    private void pinnedChoice(String label,String field,java.util.List<Choice> choices,boolean enabled) {
        if(!text(field).isBlank()&&choices.stream().noneMatch(c->c.id().equals(text(field))))choices.add(new Choice(text(field),text(field)));
        choice(label,text(field),choices,value->{
            change(field,value);
            if(field.equals("providerId"))for(String dependent:new String[]{"model","reasoningLevel","serviceTier","permissionMode"})change(dependent,"");
            if(field.equals("model")){change("reasoningLevel","");change("serviceTier","");}
            rebuildForm();
        },enabled);
    }
    private void executionRows(boolean enabled) {
        var providers=defaults("BB default");
        for(var item:AgentModels.array(catalog,"providers")) {
            var provider=item.getAsJsonObject();
            providers.add(new Choice(AgentModels.text(provider,"id"),AgentModels.text(provider,"displayName"),provider.has("available")&&provider.get("available").getAsBoolean()));
        }
        pinnedChoice("Provider","providerId",providers,enabled&&!loadingCatalog);
        // A blank model is the one BB would pick, so its reasoning and tier choices apply.
        models.model=text("model").isBlank()?inheritedModel:text("model");models.load(catalog);
        var modelChoices=defaults("BB default");
        for(var model:models.choices()) {
            String id=AgentModels.text(model,"model"),name=AgentModels.text(model,"displayName");
            modelChoices.add(new Choice(id,name.isBlank()?id:name));
        }
        pinnedChoice("Model","model",modelChoices,enabled&&!loadingCatalog);
        var reasoning=defaults("BB default");for(String effort:models.efforts())reasoning.add(new Choice(effort,effort));
        pinnedChoice("Reasoning","reasoningLevel",reasoning,enabled&&!loadingCatalog);
        var tiers=defaults("BB default");for(var tier:models.serviceTiers())tiers.add(new Choice(AgentModels.text(tier,"id"),AgentModels.text(tier,"label")));
        pinnedChoice("Service tier","serviceTier",tiers,enabled&&!loadingCatalog);
        var approvals=defaults("BB default");
        for(var item:AgentModels.array(catalog,"providers")) {
            var provider=item.getAsJsonObject();if(!AgentModels.text(provider,"id").equals(effectiveProvider))continue;
            for(String mode:AgentModels.permissionModes(provider))approvals.add(new Choice(mode,AgentModels.permissionLabel(mode)));
        }
        pinnedChoice("Approvals","permissionMode",approvals,enabled&&!loadingCatalog);
    }
    private void requestCatalog() {
        String key=text("providerId")+":"+text("projectId");if(key.equals(catalogKey))return;
        catalogKey=key;loadingCatalog=true;String pinned=text("providerId"),project=text("projectId");
        var defaults=project.isBlank()?java.util.concurrent.CompletableFuture.completedFuture(new JsonObject()):access.projectExecutionOptions(project,true);
        defaults.thenCompose(options->{
            String provider=pinned.isBlank()?AgentModels.text(options,"providerId"):pinned;
            // As at spawn: the project's model is inherited only on the project's own provider.
            String model=provider.equals(AgentModels.text(options,"providerId"))?AgentModels.text(options,"model"):"";
            return access.catalog(provider).thenApply(result->{var copy=result.deepCopy();copy.addProperty("stationCatalogProvider",provider);
                copy.addProperty("stationInheritedModel",model);return copy;});
        }).whenComplete((result,error)->net.minecraft.client.Minecraft.getInstance().execute(()->{
            if(!key.equals(catalogKey))return;loadingCatalog=false;
            if(error==null){catalog=result;effectiveProvider=AgentModels.text(result,"stationCatalogProvider");inheritedModel=AgentModels.text(result,"stationInheritedModel");}
            else feedback="Choices unavailable: "+AgentModels.error(error);
            if(minecraft!=null&&minecraft.screen==this)rebuildForm();
        }));
    }

    private boolean readArea() {
        try {
            int width=Integer.parseInt(areaWidth),height=Integer.parseInt(areaHeight);
            if(width<1||width>99||width%2==0||height<1||height>99)throw new NumberFormatException();
            draft.addProperty("radius",(width-1)/2);draft.addProperty("height",height);return true;
        } catch(NumberFormatException error) {feedback="Width must be odd, 1–99; height must be 1–99.";cancelClose();return false;}
    }
    private void saveThen(Runnable done) {
        if(busy||!readArea())return;
        var fields=fields();
        if(fields.equals(saved)){done.run();return;}
        run("save",fields,result->{
            for(String key:FIELDS)if(result.has(key))draft.add(key,result.get(key).deepCopy());saved=fields();done.run();
        });
    }
    private void spawn() {
        var settings=new JsonObject();settings.addProperty("stationId",id);settings.addProperty("projectId",text("projectId"));
        for(String key:new String[]{"providerId","model","reasoningLevel","serviceTier","permissionMode","body"})if(!text(key).isBlank())settings.addProperty(key,text(key));
        settings.add("behaviors",AgentModels.object(draft,"behaviors").deepCopy());
        minecraft.setScreen(new AgentChatScreen(access,settings,agent->{}));
    }
    @Override public void tick() {
        if(++ticks%10!=0||busy)return;
        String current=live();
        if(!current.equals(liveLinks)){liveLinks=current;rebuildForm();}
    }
    /** The occupant and this station's linked screens; only their changes redraw the form. */
    private String live() {
        var live=new StringBuilder(occupant());
        for(var item:AgentModels.array(AgentModels.object(access.projects(),"world"),"screenLinks"))
            if(AgentModels.text(item.getAsJsonObject(),"stationId").equals(id))live.append('\n').append(item);
        return live.toString();
    }
    /** The editor previews its own area without changing the shared linking decision. */
    public JsonObject outline() {
        var row=draft.deepCopy();int radius=draft.get("radius").getAsInt(),height=draft.get("height").getAsInt();
        try {int width=Integer.parseInt(areaWidth),h=Integer.parseInt(areaHeight);if(width>=1&&width<=99&&width%2==1)radius=(width-1)/2;if(h>=1&&h<=99)height=h;}catch(NumberFormatException ignored){}
        var min=new JsonArray();min.add(pos.getX()-radius);min.add(pos.getY());min.add(pos.getZ()-radius);
        var max=new JsonArray();max.add(pos.getX()+radius);max.add(pos.getY()+height-1);max.add(pos.getZ()+radius);
        row.add("min",min);row.add("max",max);return row;
    }
    @Override public void onClose() { saveThen(()->leave(parent)); }
}
