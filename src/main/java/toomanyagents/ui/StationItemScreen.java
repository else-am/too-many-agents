package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import toomanyagents.stations.StationItemMenu;
import toomanyagents.stations.Stations;

/** Vanilla inventory clicks and packets; the result is only a preview until taken. */
public final class StationItemScreen extends AbstractContainerScreen<StationItemMenu> {
    public StationItemScreen(StationItemMenu menu, Inventory inventory, Component title) { super(menu,inventory,title); }
    @Override protected void renderBg(GuiGraphics g,float delta,int x,int y) {
        g.fill(leftPos-5,topPos-5,leftPos+imageWidth+5,topPos+imageHeight+5,0xF0202223);
        for (var slot:menu.slots) {
            g.fill(leftPos+slot.x-1,topPos+slot.y-1,leftPos+slot.x+17,topPos+slot.y+17,0xFF888888);
            g.fill(leftPos+slot.x,topPos+slot.y,leftPos+slot.x+16,topPos+slot.y+16,0xFF303234);
        }
        g.drawCenteredString(font,"→",leftPos+88,topPos+38,0xE2D4A7);
        g.drawCenteredString(font,"Input",leftPos+52,topPos+58,0xAAAAAA);
        g.drawCenteredString(font,"Take copy",leftPos+124,topPos+58,0xAAAAAA);
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) { super.render(g,x,y,delta); renderTooltip(g,x,y); }
    public JsonObject diagnostics() {
        var result=new JsonObject();var slots=new JsonArray();
        result.addProperty("containerId",menu.containerId);
        for(var slot:menu.slots) {
            var row=new JsonObject(); row.addProperty("slot",slot.index);
            row.addProperty("x",leftPos+slot.x);row.addProperty("y",topPos+slot.y);
            row.addProperty("width",16);row.addProperty("height",16);
            var stack=slot.getItem();row.addProperty("count",stack.getCount());
            row.addProperty("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            var station=stack.get(Stations.STATION_DATA.get());if(station!=null)row.add("station",station.json());
            slots.add(row);
        }
        result.add("slots",slots);result.addProperty("carriedCount",menu.getCarried().getCount());return result;
    }
}
