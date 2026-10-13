package toomanyagents.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

/** A marker's project binding, project settings and one-for-one station assignment. */
public final class ProjectBlockScreen extends BlockSettingsScreen {
    private String projectId,saved;
    public ProjectBlockScreen(AgentUiAccess access,Screen parent,BlockPos pos,String dimension,String projectId) {
        super("Project block",access,parent,pos,dimension,"");this.projectId=projectId;saved=projectId;
    }
    @Override protected void init() {
        begin();section("Project");
        choice("Project",projectId,projects(),v->{projectId=v;rebuildForm();},!busy);
        action("","Create project…",()->saveThen(()->minecraft.setScreen(ProjectScreen.create(access,this,this::created))),!busy);
        action("","Project settings…",()->saveThen(()->minecraft.setScreen(ProjectScreen.edit(access,this,projectId))),!busy&&!projectId.isBlank());
        section("Station items");
        note("Give a station item this project, keeping all its other settings.");
        action("Station item","Assign station item…",()->saveThen(()->run("items",null,r->{})),!busy&&!projectId.isBlank());
        action("","Apply",()->saveThen(()->feedback="Saved"),!busy);
        action("","Close without saving",this::discard,!busy);done();
    }
    private void created(String id) {
        projectId=id;
        // Show the binding result, including any error, on the block screen. Never leave a successful create form open.
        minecraft.setScreen(this);
        saveThen(()->feedback="Project created and assigned");
    }
    private void saveThen(Runnable done) {
        if(busy)return;
        if(projectId.equals(saved)){done.run();return;}
        var fields=new JsonObject();fields.addProperty("projectId",projectId);
        run("save",fields,result->{saved=AgentModels.text(result,"projectId");projectId=saved;done.run();});
    }
    @Override public void onClose() { saveThen(()->leave(parent)); }
}
