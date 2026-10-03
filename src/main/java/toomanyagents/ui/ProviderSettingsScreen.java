package toomanyagents.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import toomanyagents.ProviderInstallations;

/** Provider selection is saved for the next game launch, leaving active sessions alone. */
public final class ProviderSettingsScreen extends Screen {
    private final Screen parent;
    private JsonObject snapshot = new JsonObject();
    private String provider = "codex", path = "", feedback = "Reading local installations…";
    private boolean automatic = true, busy = true, requested;
    private int left, top, contentWidth;
    private EditBox pathField;
    private Button providerButton, modeButton, saveButton;

    public ProviderSettingsScreen(Screen parent) {
        super(Component.literal("Agent providers"));
        this.parent = parent;
    }
    private JsonObject row() {
        for(var value : AgentModels.array(snapshot,"providers")) {
            var row=value.getAsJsonObject();
            if(AgentModels.text(row,"id").equals(provider))return row;
        }
        return new JsonObject();
    }
    private JsonObject current() { return row().has("current") ? row().getAsJsonObject("current") : new JsonObject(); }
    private void loadSelection() {
        path=AgentModels.text(row(),"configuredPath"); automatic=path.isBlank();
        if(pathField!=null)pathField.setValue(path);
        feedback=AgentModels.text(snapshot,"error");
        if(feedback.isBlank()) feedback=row().has("restartRequired") && row().get("restartRequired").getAsBoolean()
            ? "Saved. Restart Minecraft to use the new selection." : "Changes take effect after restarting Minecraft.";
    }
    @Override protected void init() {
        contentWidth=Math.min(500,width-24); left=(width-contentWidth)/2; top=Math.max(4,(height-230)/2);
        providerButton=addRenderableWidget(Button.builder(Component.empty(),button -> {
            provider=provider.equals("codex")?"claude":"codex"; loadSelection(); refresh();
        }).bounds(left,top+27,contentWidth/2-3,20).build());
        modeButton=addRenderableWidget(Button.builder(Component.empty(),button -> {
            automatic=!automatic;
            if(!automatic && path.isBlank()) { path=AgentModels.text(current(),"path"); pathField.setValue(path); }
            refresh();
        }).bounds(left+contentWidth/2+3,top+27,contentWidth/2-3,20).build());
        modeButton.setTooltip(Tooltip.create(Component.literal("Automatic chooses the newest detected installation. An explicit environment override takes precedence. Custom path pins your choice.")));
        pathField=addRenderableWidget(new EditBox(font,left,top+68,contentWidth,20,Component.literal("Provider executable path")));
        pathField.setMaxLength(4096);pathField.setValue(path);
        pathField.setResponder(value -> {path=value;refresh();});
        saveButton=addRenderableWidget(Button.builder(Component.literal("Save provider"),button -> save())
            .bounds(left,top+205,contentWidth/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),button -> onClose())
            .bounds(left+contentWidth/2+3,top+205,contentWidth/2-3,20).build());
        refresh();
        if(!requested) {
            requested=true;
            ProviderInstallations.get().snapshot().whenComplete((result,error) -> screenExecutor.execute(() -> {
                busy=false;
                if(error!=null) feedback=AgentModels.error(error);
                else { snapshot=result; loadSelection(); }
                refresh();
            }));
        }
    }
    private void refresh() {
        if(saveButton==null)return;
        providerButton.setMessage(Component.literal(provider.equals("codex")?"Codex":"Claude Code"));
        modeButton.setMessage(Component.literal(automatic?"Automatic - newest installed":"Custom path"));
        providerButton.active=modeButton.active=!busy;
        pathField.setEditable(!busy&&!automatic);
        pathField.setTooltip(automatic?Tooltip.create(Component.literal("Switch to Custom path to choose an executable.")):font.width(path)>pathField.getInnerWidth()?Tooltip.create(Component.literal(path)):null);
        saveButton.active=!busy&&(automatic||!path.isBlank());
    }
    private void save() {
        busy=true;feedback="Checking executable…";refresh();
        ProviderInstallations.get().configure(provider,automatic?"":path).whenComplete((result,error) -> screenExecutor.execute(() -> {
            busy=false;
            if(error!=null)feedback=AgentModels.error(error);
            else {snapshot=result;loadSelection();}
            refresh();
        }));
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        super.render(graphics,mouseX,mouseY,partialTick);
        graphics.drawString(font,title,left,top+8,0xFFFFFF);
        line(graphics,"Executable override",top+55,0xBDBDBD);
        String version=AgentModels.text(current(),"version"), location=AgentModels.text(current(),"path");
        line(graphics,"This launch: " + (version.isBlank()?"Unavailable":version),top+100,0xFFFFFF);
        line(graphics,location,top+115,0xBDBDBD);
        String error=AgentModels.text(current(),"error");
        line(graphics,error.isBlank()?"Source: " + AgentModels.text(current(),"source"):error,top+130,error.isBlank()?0xBDBDBD:0xFFAAAA);
        if(row().has("next") && row().has("restartRequired") && row().get("restartRequired").getAsBoolean())
            line(graphics,"Next launch: " + AgentModels.text(row().getAsJsonObject("next"),"version"),top+147,0xFFFFAA);
        else line(graphics,"Local installations only - no downloads or updates",top+147,0xBDBDBD);
        graphics.drawWordWrap(font,Component.literal(font.plainSubstrByWidth(feedback,contentWidth*2)),left,top+172,contentWidth,0xBDBDBD);
        if(mouseX>=left && mouseX<=left+contentWidth && mouseY>=top+112 && mouseY<=top+142)
            graphics.renderTooltip(font,font.split(Component.literal(error.isBlank()?location:error),contentWidth),mouseX,mouseY);
    }
    private void line(GuiGraphics graphics,String text,int y,int color) { graphics.drawString(font,font.plainSubstrByWidth(text,contentWidth),left,y,color); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
