package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native image viewer: fit, wheel zoom and drag pan. */
public final class ChatImageScreen extends Screen {
    private final Screen parent;
    private final ChatImages images;
    private final String kind, source;
    private double zoom = 1, panX, panY;

    ChatImageScreen(Screen parent, String kind, String source, String title,
                    BiFunction<String,String,CompletableFuture<JsonObject>> load) {
        super(Component.literal(title.isBlank() ? "Image" : title));
        this.parent=parent;this.kind=kind;this.source=source;images=new ChatImages(load);
    }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"),unused->onClose()).bounds(width-64,8,56,20).build());
        addRenderableWidget(Button.builder(Component.literal("Fit"),unused->{zoom=1;panX=panY=0;}).bounds(width-124,8,56,20).build());
    }
    @Override public boolean isPauseScreen() { return false; }
    public JsonObject diagnostics() {
        var result=new JsonObject();result.addProperty("zoom",zoom);result.addProperty("panX",panX);result.addProperty("panY",panY);
        result.addProperty("textureBytes",images.textureBytes());return result;
    }
    @Override public void removed() { images.close(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(0,0,width,height,0xF0101519);
        graphics.drawString(font,font.plainSubstrByWidth(title.getString(),Math.max(20,width-160)),12,14,0xEEEEEE);
        graphics.enableScissor(8,36,width-8,height-26);
        var image=images.get(kind,source);
        if(image.texture!=null) {
            double fit=Math.min((double)(width-24)/image.width,(double)(height-72)/image.height);
            int w=Math.max(1,(int)(image.width*fit*zoom)),h=Math.max(1,(int)(image.height*fit*zoom));
            panX=Math.clamp(panX,-w/2.0,w/2.0);panY=Math.clamp(panY,-h/2.0,h/2.0);
            graphics.blit(image.texture,(width-w)/2+(int)panX,(height-h)/2+(int)panY,w,h,0,0,image.width,image.height,image.width,image.height);
        } else graphics.drawWordWrap(font,Component.literal(image.loading?"Loading…":image.error),20,50,width-40,0xC9C3B8);
        graphics.disableScissor();
        graphics.drawString(font,"Scroll to zoom · Drag to pan · Esc to return",12,height-17,0x99AAB5);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        zoom=Math.clamp(zoom*Math.pow(1.2,vertical),0.25,12);return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(button==0){panX+=dx;panY+=dy;return true;}return super.mouseDragged(x,y,button,dx,dy);
    }
}
