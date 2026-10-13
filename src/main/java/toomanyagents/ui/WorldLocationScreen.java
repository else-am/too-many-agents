package toomanyagents.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Resolves moved/copied saves without exposing settings for a world project. */
public final class WorldLocationScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private boolean busy;
    public WorldLocationScreen(AgentUiAccess access,Screen parent) {
        super(Component.literal("Resolve world location"));this.access=access;this.parent=parent;
    }
    static boolean needed(AgentUiAccess access) {
        var world=AgentModels.object(access.projects(),"world");
        return world.has("needsDecision")&&world.get("needsDecision").getAsBoolean();
    }
    @Override protected void init() {
        begin();section("World location changed");
        if(needed(access)) {
            note("Is this the original world in a new location, or a separate copy?");
            action("Original save","Moved original",()->resolve("move"),!busy);
            action("Copied save","Separate copy",()->resolve("copy"),!busy);
            note("A separate copy gets a new world identity. Original agents and history stay with the original world.");
        } else note("This world's location is resolved.");
        done();
    }
    private void resolve(String choice) {
        if(busy)return;
        var request=new JsonObject();request.addProperty("operation","world-resolve");request.addProperty("choice",choice);
        busy=true;feedback="Resolving…";rebuildForm();
        access.projectCommand(request).whenComplete((result,error)->Minecraft.getInstance().execute(()->{
            busy=false;
            if(error==null)leave(parent);
            else {feedback=AgentModels.error(error);cancelClose();rebuildForm();}
        }));
    }
    @Override public void onClose() {if(!busy)leave(parent);}
}
