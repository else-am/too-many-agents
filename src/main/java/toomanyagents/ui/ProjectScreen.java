package toomanyagents.ui;

import com.google.gson.*;
import toomanyagents.ProjectColor;
import net.minecraft.client.gui.GuiGraphics;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** One project's folders and place in the world; with no project id it creates one. */
public final class ProjectScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private JsonObject data;
    private String projectId,name="",directory="",color="",saved="";
    private boolean busy,choosing,confirmRemove;
    private final Map<EditBox,JsonObject> distances=new LinkedHashMap<>();
    private Button surveyButton;

    public static ProjectScreen create(AgentUiAccess access,Screen parent){return new ProjectScreen(access,parent,"");}
    public static ProjectScreen edit(AgentUiAccess access,Screen parent,String projectId){return new ProjectScreen(access,parent,projectId);}
    private ProjectScreen(AgentUiAccess access,Screen parent,String projectId){
        super(Component.literal(projectId.isBlank()?"New project":"Project settings"));
        this.access=access;this.parent=parent;this.projectId=projectId;data=access.projects();
        var p=project();name=text(p,"name");
        directory=text(p,"folder");
        color=projectId.isBlank()?ProjectColor.forId(UUID.randomUUID().toString()):ProjectColor.of(p);
        saved=fields();
    }
    private String fields(){return name.strip()+"\n"+directory.strip()+"\n"+color;}
    private static JsonArray array(JsonObject o,String key){return AgentModels.array(o,key);}
    private static String text(JsonObject o,String key){return AgentModels.text(o,key);}
    private static JsonObject request(String operation){var o=new JsonObject();o.addProperty("operation",operation);return o;}
    private JsonObject world(){return data.has("world")?data.getAsJsonObject("world"):new JsonObject();}
    private JsonObject project(){
        for(var item:array(data,"projects"))if(text(item.getAsJsonObject(),"id").equals(projectId))return item.getAsJsonObject();
        return new JsonObject();
    }
    // The world's own project keeps its managed folder.
    @Override protected String heading(){return projectId.isBlank()?"New project":name.isBlank()?"Project settings":name;}
    public String projectId(){return projectId;}
    private boolean automatic(){return Set.of("personal","world").contains(text(project(),"kind"));}

    @Override protected void init(){
        distances.clear();surveyButton=null;
        begin();
        var world=world();
        if(world.has("needsDecision")&&world.get("needsDecision").getAsBoolean()){
            section("World location changed");note("Is this the original world in a new location, or a separate copy?");
            action("Original save","Moved original",()->resolve("move"),!busy);
            action("Copied save","Separate copy",()->resolve("copy"),!busy);
            note("A separate copy gets a new world identity. Original agents and history stay with the original world.");
            done();return;
        }
        boolean creating=projectId.isBlank();
        section("Project");
        if(automatic())value("Name",name);
        else {var nameField=input("Name",name,80,v->name=v,!busy);if(creating)setInitialFocus(nameField);}
        colorRow(!busy);
        if(creating)folderRow("Project folder",directory,4096,"/absolute/path",v->directory=v);
        else {
            for(var item:array(project(),"folders")) {
                var source=item.getAsJsonObject();
                value(text(source,"label"),text(source,"path"));
            }
            placeRows(world);
            if(!automatic()){rowY+=12;action("",confirmRemove?"Confirm removal":"Remove project",this::remove,!busy);}
        }
        if(creating)submit("Create project",this::save,!busy);else done();
    }
    private void colorRow(boolean enabled){
        var palette=List.of("#F2627D","#E6AC62","#E1D48B","#91C99B","#77BFC7","#80A5E0","#B99ADC","#D1D1D1");
        int y=row("Color"), step=Math.min(24,(controlWidth-60)/palette.size()), size=step-4;
        for(int i=0;i<palette.size();i++){
            String hex=palette.get(i);
            var swatch=new Button(controlX+i*step,0,size,20,Component.literal(hex),b->{color=hex;rebuildForm();},n->n.get()){
                @Override protected void renderWidget(GuiGraphics g,int mx,int my,float delta){
                    boolean chosen=color.equalsIgnoreCase(hex);
                    if(chosen||isHoveredOrFocused())g.fill(getX(),getY(),getX()+getWidth(),getY()+20,chosen?0xFFFFFFFF:0xFF777777);
                    g.fill(getX()+2,getY()+2,getX()+getWidth()-2,getY()+18,0xFF000000|ProjectColor.rgb(hex));
                }
            };
            swatch.active=enabled;place(swatch,y);
        }
        int x=controlX+palette.size()*step+2;
        var field=new EditBox(font,x,0,Math.min(64,controlX+controlWidth-x),20,Component.literal("Color"));
        field.setMaxLength(7);field.setValue(color);field.setEditable(enabled);field.setTextColor(ProjectColor.rgb(color));
        field.setResponder(v->{color=v;field.setTextColor(ProjectColor.valid(v)?ProjectColor.rgb(v):0xE0E0E0);});
        place(field,y);
    }
    private void folderRow(String label,String value,int limit,String hint,Consumer<String> change){
        int y=row(label);
        var field=new EditBox(font,controlX,0,controlWidth-66,20,Component.literal(label));
        field.setMaxLength(limit);field.setValue(value);field.setResponder(change);field.setEditable(!busy);field.setHint(Component.literal(hint));
        place(field,y);
        var choose=Button.builder(Component.literal("Choose…"),b->chooseFolder()).bounds(controlX+controlWidth-60,0,60,20).build();
        choose.active=!busy&&!choosing;place(choose,y);
    }
    // The native dialog blocks until closed, so it runs off the render thread.
    private void chooseFolder(){
        choosing=true;rebuildForm();
        String start=!directory.isBlank()?directory:System.getProperty("user.home");
        CompletableFuture.supplyAsync(()->TinyFileDialogs.tinyfd_selectFolderDialog("Project folder",start+"/"))
            .whenComplete((path,error)->net.minecraft.client.Minecraft.getInstance().execute(()->{
                choosing=false;
                String folder=path==null?"":path.replaceAll("/+$","");
                if(!folder.isBlank())directory=folder;
                defaultName();
                rebuildForm();
            }));
    }
    private void defaultName(){
        if(!name.isBlank()||directory.isBlank())return;
        try{var leaf=Path.of(directory.strip()).getFileName();if(leaf!=null)name=leaf.toString();}catch(Exception ignored){}
    }
    private void placeRows(JsonObject world){
        section("In-world");
        JsonObject box=null;
        for(var item:array(world,"bounds"))if(text(item.getAsJsonObject(),"projectId").equals(projectId))box=item.getAsJsonObject();
        var boundary=value("Project boundary",box==null?"Not set":distance(box),"Agents in this project will stay inside this box");
        if(box!=null)distances.put(boundary,box);
        rowY+=4;row("Stations");
        boolean any=false;
        for(var item:array(world,"stations")){
            var station=item.getAsJsonObject();if(!text(station,"projectId").equals(projectId))continue;
            distances.put(value("  "+text(station,"label"),distance(station)),station);any=true;
        }
        if(!any)note("No stations yet");
        rowY+=8;
        surveyButton=place(Button.builder(Component.literal(surveyCaption()),b->SurveyMode.start(projectId))
            .bounds(left,0,contentWidth,20).build(),rowY);
        surveyButton.active=!busy&&minecraft.level!=null;rowY+=24;
    }
    private static String surveyCaption(){return "Show/edit project boxes (press "+SurveyMode.keyLabel()+")";}
    private String distance(JsonObject box){
        if(minecraft.player==null||minecraft.level==null)return "Not in world";
        String dimension=text(box,"dimension");
        if(!dimension.equals(minecraft.level.dimension().location().toString()))
            return "In "+dimension.substring(dimension.indexOf(':')+1).replace('_',' ');
        var min=array(box,"min");var max=array(box,"max");var at=minecraft.player.position();
        double dx=Math.max(0,Math.max(min.get(0).getAsDouble()-at.x,at.x-max.get(0).getAsDouble()-1));
        double dy=Math.max(0,Math.max(min.get(1).getAsDouble()-at.y,at.y-max.get(1).getAsDouble()-1));
        double dz=Math.max(0,Math.max(min.get(2).getAsDouble()-at.z,at.z-max.get(2).getAsDouble()-1));
        long blocks=Math.round(Math.sqrt(dx*dx+dy*dy+dz*dz));
        return blocks+" block"+(blocks==1?"":"s")+" away";
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta){
        distances.forEach((field,box)->{String next=distance(box);if(!field.getValue().equals(next))field.setValue(next);});
        if(surveyButton!=null)surveyButton.setMessage(Component.literal(surveyCaption()));
        super.render(g,x,y,delta);
    }
    private void save(){
        if(projectId.isBlank())defaultName();
        if(name.isBlank()||projectId.isBlank()&&directory.isBlank()){feedback="Enter a name and primary folder.";cancelClose();return;}
        if(!ProjectColor.valid(color)){feedback="Enter a color as #RRGGBB.";cancelClose();return;}
        var req=request(projectId.isBlank()?"create":"configure");req.addProperty("projectId",projectId);
        if(!automatic())req.addProperty("name",name.strip());
        req.addProperty("color",color);
        if(projectId.isBlank())req.addProperty("folder",directory.strip());
        boolean creating=projectId.isBlank();
        // A new project stays open so its place can be set up next.
        run(req,r->{
            if(!creating){leave(parent);return;}
            String id=r.has("projectId")?text(r,"projectId"):text(r,"id");
            if(!docked()){minecraft.setScreen(edit(access,parent,id));return;}
            projectId=id;saved=fields();
        });
    }
    // Removes the registration only; folders stay on disk.
    private void remove(){
        if(!confirmRemove){confirmRemove=true;rebuildForm();return;}
        var req=request("remove");req.addProperty("projectId",projectId);run(req,r->leave(parent));
    }
    private void resolve(String choice){var req=request("world-resolve");req.addProperty("choice",choice);run(req,r->{});}
    private void run(JsonObject req,Consumer<JsonObject> done){
        busy=true;feedback="Working…";rebuildForm();
        access.projectCommand(req).whenComplete((result,error)->net.minecraft.client.Minecraft.getInstance().execute(()->{
            busy=false;data=access.projects();feedback=error==null?"":AgentModels.error(error);
            if(error!=null)cancelClose();
            if(error==null)done.accept(result);
            if(docked()||minecraft.screen==this)rebuildForm();
        }));
    }
    // Edits save on the way out; leaving a new project unsubmitted discards it.
    @Override public void onClose(){
        if(busy)return;
        if(projectId.isBlank()||world().has("needsDecision")&&world().get("needsDecision").getAsBoolean()||fields().equals(saved))leave(parent);
        else save();
    }
}
