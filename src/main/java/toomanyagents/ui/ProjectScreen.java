package toomanyagents.ui;

import com.google.gson.*;
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
    private String projectId,name="",directory="",additional="",saved="";
    private boolean busy,choosing,confirmRemove;

    public static ProjectScreen create(AgentUiAccess access,Screen parent){return new ProjectScreen(access,parent,"");}
    public static ProjectScreen edit(AgentUiAccess access,Screen parent,String projectId){return new ProjectScreen(access,parent,projectId);}
    private ProjectScreen(AgentUiAccess access,Screen parent,String projectId){
        super(Component.literal(projectId.isBlank()?"New project":"Project settings"));
        this.access=access;this.parent=parent;this.projectId=projectId;data=access.projects();
        var p=project();name=text(p,"name");directory=text(p,"primaryDirectory");
        additional=String.join("; ",array(p,"additionalDirectories").asList().stream().map(JsonElement::getAsString).toList());
        saved=fields();
    }
    private String fields(){return name.strip()+"\n"+directory.strip()+"\n"+additional.strip();}
    private static JsonArray array(JsonObject o,String key){return AgentModels.array(o,key);}
    private static String text(JsonObject o,String key){return AgentModels.text(o,key);}
    private static JsonObject request(String operation){var o=new JsonObject();o.addProperty("operation",operation);return o;}
    private JsonObject world(){return data.has("world")?data.getAsJsonObject("world"):new JsonObject();}
    private JsonObject project(){
        for(var item:array(data,"projects"))if(text(item.getAsJsonObject(),"id").equals(projectId))return item.getAsJsonObject();
        return new JsonObject();
    }
    // The world's own project keeps its managed folder.
    private boolean automatic(){return !text(project(),"minecraftWorldId").isBlank();}

    @Override protected void init(){
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
        var nameField=input("Name",name,80,v->name=v,!busy);
        if(creating)setInitialFocus(nameField);
        if(!automatic()){
            folderRow("Primary folder",directory,4096,"/absolute/path",v->directory=v,true);
            folderRow("Additional folders",additional,16384,"Optional - separate with ;",v->additional=v,false);
        }
        if(!creating){placeRows(world);if(!automatic()){rowY+=12;action("",confirmRemove?"Confirm removal":"Remove project",this::remove,!busy);}}
        if(creating)submit("Create project",this::save,!busy);else done();
    }
    private void folderRow(String label,String value,int limit,String hint,Consumer<String> change,boolean primary){
        int y=row(label);
        var field=new EditBox(font,controlX,0,controlWidth-66,20,Component.literal(label));
        field.setMaxLength(limit);field.setValue(value);field.setResponder(change);field.setEditable(!busy);field.setHint(Component.literal(hint));
        place(field,y);
        var choose=Button.builder(Component.literal("Choose…"),b->chooseFolder(primary)).bounds(controlX+controlWidth-60,0,60,20).build();
        choose.active=!busy&&!choosing;place(choose,y);
    }
    // The native dialog blocks until closed, so it runs off the render thread.
    private void chooseFolder(boolean primary){
        choosing=true;rebuildForm();
        String start=primary&&!directory.isBlank()?directory:System.getProperty("user.home");
        CompletableFuture.supplyAsync(()->TinyFileDialogs.tinyfd_selectFolderDialog(primary?"Primary folder":"Additional folder",start+"/"))
            .whenComplete((path,error)->screenExecutor.execute(()->{
                choosing=false;
                String folder=path==null?"":path.replaceAll("/+$","");
                if(!folder.isBlank()&&primary)directory=folder;
                else if(!folder.isBlank())additional=additional.isBlank()?folder:additional.strip()+"; "+folder;
                if(primary)defaultName();
                rebuildForm();
            }));
    }
    private void defaultName(){
        if(!name.isBlank()||directory.isBlank())return;
        try{var leaf=Path.of(directory.strip()).getFileName();if(leaf!=null)name=leaf.toString();}catch(Exception ignored){}
    }
    private void placeRows(JsonObject world){
        section("Place");
        JsonObject box=null;
        for(var item:array(world,"bounds"))if(text(item.getAsJsonObject(),"projectId").equals(projectId))box=item.getAsJsonObject();
        value("Box",box==null?"None":size(box)+" at "+corner(box));
        var names=new HashMap<String,String>();
        for(var item:access.worldAgents())names.put(text(item.getAsJsonObject(),"id"),text(item.getAsJsonObject(),"name"));
        for(var item:array(world,"stations")){
            var station=item.getAsJsonObject();if(!text(station,"projectId").equals(projectId))continue;
            String occupant=text(station,"agentId");
            value(text(station,"label"),(occupant.isBlank()?"Free":names.getOrDefault(occupant,"Occupied"))+" - "+corner(station));
        }
        action("In the world","Edit in world",()->SurveyMode.start(projectId),!busy&&minecraft.level!=null);
    }
    private static String size(JsonObject box){
        var min=array(box,"min");var max=array(box,"max");
        return (max.get(0).getAsInt()-min.get(0).getAsInt()+1)+"×"+(max.get(1).getAsInt()-min.get(1).getAsInt()+1)+"×"+(max.get(2).getAsInt()-min.get(2).getAsInt()+1);
    }
    private static String corner(JsonObject box){
        var min=array(box,"min");String dimension=text(box,"dimension");
        String at=min.get(0).getAsInt()+" "+min.get(1).getAsInt()+" "+min.get(2).getAsInt();
        return dimension.isBlank()||dimension.equals("minecraft:overworld")?at:at+" - "+dimension.substring(dimension.indexOf(':')+1);
    }
    private void save(){
        if(projectId.isBlank())defaultName();
        if(name.isBlank()||!automatic()&&directory.isBlank()){feedback="Enter a name and primary folder.";return;}
        var req=request(projectId.isBlank()?"create":"configure");req.addProperty("projectId",projectId);req.addProperty("name",name.strip());
        if(!automatic()){
            req.addProperty("primaryDirectory",directory.strip());
            var folders=new JsonArray();for(String folder:additional.split(";"))if(!folder.isBlank())folders.add(folder.strip());req.add("additionalDirectories",folders);
        }
        boolean creating=projectId.isBlank();
        // A new project stays open so its place can be set up next.
        run(req,r->{if(creating){projectId=r.has("projectId")?text(r,"projectId"):text(r,"id");minecraft.setScreen(edit(access,parent,projectId));}else minecraft.setScreen(parent);});
    }
    // Removes the registration only; folders stay on disk.
    private void remove(){
        if(!confirmRemove){confirmRemove=true;rebuildForm();return;}
        var req=request("remove");req.addProperty("projectId",projectId);run(req,r->minecraft.setScreen(parent));
    }
    private void resolve(String choice){var req=request("world-resolve");req.addProperty("choice",choice);run(req,r->{});}
    private void run(JsonObject req,Consumer<JsonObject> done){
        busy=true;feedback="Working…";rebuildForm();
        access.projectCommand(req).whenComplete((result,error)->screenExecutor.execute(()->{
            busy=false;data=access.projects();feedback=error==null?"":AgentModels.error(error);
            if(error==null)done.accept(result);
            if(minecraft.screen==this)rebuildForm();
        }));
    }
    // Edits save on the way out; leaving a new project unsubmitted discards it.
    @Override public void onClose(){
        if(busy)return;
        if(projectId.isBlank()||world().has("needsDecision")&&world().get("needsDecision").getAsBoolean()||fields().equals(saved))minecraft.setScreen(parent);
        else save();
    }
}
