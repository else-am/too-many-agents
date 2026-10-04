package toomanyagents.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The published BB runtime owns provider installation and authentication. */
public final class ProviderSettingsScreen extends SettingsFormScreen {
    private final Screen parent;
    private final AgentUiAccess access;
    private JsonObject status = new JsonObject();
    private boolean busy, requested;

    public ProviderSettingsScreen(Screen parent, AgentUiAccess access) {
        super(Component.literal("Agent providers"));
        this.parent = parent;
        this.access = access;
    }

    @Override protected void init() {
        begin();
        section("BB runtime");
        String runtime = AgentModels.text(status,"runtimeVersion");
        value("Version",runtime.isBlank()?"Unavailable":runtime);
        note("Providers and sign-in are managed by BB.");
        section("Providers");
        for(var value:AgentModels.array(status,"providers")) {
            var provider=value.getAsJsonObject();
            value(AgentModels.text(provider,"displayName"),provider.has("available")&&provider.get("available").getAsBoolean()?"Available":"Unavailable");
        }
        if(access==null)note("Open a singleplayer world to read the backend connection.");
        action("Connection",busy?"Reading…":"Refresh",this::refresh,access!=null&&!busy);
        done();
        if(!requested&&access!=null){requested=true;refresh();}
    }

    private void refresh() {
        if(busy||access==null)return;
        busy=true;feedback="Reading BB…";rebuildForm();
        access.backendStatus().whenComplete((result,error)->screenExecutor.execute(()->{
            busy=false;
            if(error!=null)feedback=AgentModels.error(error);
            else {status=result;feedback=AgentModels.text(result,"error");}
            rebuildForm();
        }));
    }
    @Override public void onClose(){leave(parent);}
}
