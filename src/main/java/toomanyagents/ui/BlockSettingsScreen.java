package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Shared player block action envelope and project choices. The server validates session, distance and identity. */
abstract class BlockSettingsScreen extends SettingsFormScreen {
    protected final AgentUiAccess access;
    protected final Screen parent;
    protected final BlockPos pos;
    protected final String dimension, id;
    protected boolean busy;
    BlockSettingsScreen(String title,AgentUiAccess access,Screen parent,BlockPos pos,String dimension,String id) {
        super(Component.literal(title));this.access=access;this.parent=parent;this.pos=pos;this.dimension=dimension;this.id=id;
    }
    protected List<Choice> projects() {
        var choices=new ArrayList<Choice>();choices.add(new Choice("","Unassigned"));
        for(var item:AgentModels.array(access.projects(),"projects")) {
            var project=item.getAsJsonObject();
            if(!AgentModels.text(project,"kind").equals("world"))choices.add(new Choice(AgentModels.text(project,"id"),AgentModels.text(project,"name")));
        }
        return choices;
    }
    /** Leaves without saving, so an invalid draft or a block that can't be saved never traps the player. */
    protected void discard() { if(!busy)leave(parent); }
    protected void run(String operation,JsonObject fields,java.util.function.Consumer<JsonObject> done) {
        if(busy)return;
        var request=new JsonObject();request.addProperty("operation",operation);request.addProperty("dimension",dimension);request.addProperty("id",id);
        var xyz=new JsonArray();xyz.add(pos.getX());xyz.add(pos.getY());xyz.add(pos.getZ());request.add("pos",xyz);
        if(fields!=null)request.add("fields",fields);
        busy=true;feedback="Saving…";rebuildForm();
        access.blockAction(request).whenComplete((result,error)->Minecraft.getInstance().execute(()->{
            busy=false;feedback=error==null?"":AgentModels.error(error);
            if(error==null)done.accept(result);else cancelClose();
            if(minecraft.screen==this)rebuildForm();
        }));
    }
}
