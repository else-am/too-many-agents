package toomanyagents.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Shared row layout and dropdowns for the two native settings screens. */
abstract class SettingsFormScreen extends Screen {
    /** A dropdown option; disabled options are shown but cannot be chosen. */
    protected record Choice(String id, String label, boolean enabled) {
        Choice(String id, String label) { this(id, label, true); }
    }
    private record Placed(AbstractWidget widget, int offset) {}
    private record Text(String value, int offset, int color, boolean fullWidth, boolean rule) {}
    protected int left, contentWidth, controlX, controlWidth, formTop, formBottom, rowY;
    protected int scroll;
    protected String feedback = "";
    private final List<Placed> controls = new ArrayList<>();
    private final List<Text> labels = new ArrayList<>();
    private final List<Button> popupButtons = new ArrayList<>();
    private List<Choice> choices = List.of();
    private Consumer<String> choose;
    private Button anchor;
    private int popupX, popupY, popupWidth, popupRows, choiceOffset, choiceIndex;
    private boolean draggingScroll;
    // Set while the form is a pane beside or over a chat rather than its own screen.
    private Runnable close;
    private Runnable afterClose;
    private boolean side, header;

    protected SettingsFormScreen(Component title) { super(title); }

    /** Shows the form as a bare pane: no title or Done; closing runs the callback instead of changing screens. */
    public void dock(Runnable close) { this.close = close; }
    /** A docked form that stands in for the chat, with its own heading and close button. */
    public void dockWithHeader(Runnable close) { this.close = close; header = true; }
    protected String heading() { return title.getString(); }
    protected boolean docked() { return close != null; }
    /** Side panes use tighter padding than a pane covering a chat. */
    public void side(boolean value) { if (side != value) { side = value; if (minecraft != null) rebuildForm(); } }
    protected void leave(Screen parent) {
        var next = afterClose;
        afterClose = null;
        if (close != null) close.run();
        else if (minecraft.screen == this) minecraft.setScreen(parent);
        if (next != null) next.run();
    }
    /** Navigation waits for a successful save; validation errors keep this form visible. */
    void closeThen(Runnable next) { afterClose = next; onClose(); }
    protected void cancelClose() { afterClose = null; }

    protected void begin() {
        closeDropdown(); controls.clear(); labels.clear(); rowY = 0;
        if (docked()) { left = side ? 4 : 10; contentWidth = Math.max(60, width - left - (side ? 12 : 18)); formTop = header ? 36 : 6; formBottom = height - 16; }
        else { contentWidth = Math.min(600, width - 32); left = (width - contentWidth) / 2; formTop = 34; formBottom = height - 48; }
        int labelWidth = Math.min(164, contentWidth * 2 / 5);
        controlX = left + labelWidth + 12; controlWidth = contentWidth - labelWidth - 12;
        // The chat's close button sits in the same place.
        if (header) addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose()).bounds(left+contentWidth-20,7,20,20).build());
    }

    /** Minecraft's centered Done; screens save as they close. */
    protected void done() {
        int w=Math.min(200,contentWidth);
        if (!docked()) addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds((width-w)/2,height-26,w,20).build());
        layout();
    }

    /** A creation action sits right under the form it submits. */
    protected void submit(String label, Runnable action, boolean enabled) {
        rowY+=8;
        int w=Math.max(100,font.width(label)+24);
        place(Button.builder(Component.literal(label), b -> action.run()).bounds(left+contentWidth-w,0,w,20).build(),rowY).active=enabled;
        rowY+=24;
        layout();
    }

    protected void section(String text) {
        if(rowY>0) rowY+=12;
        labels.add(new Text(text,rowY+4,0xE2D4A7,true,true)); rowY+=20;
    }
    protected void note(String text) {
        for(var line:font.split(Component.literal(text),contentWidth-12)) {
            // Store plain text here; native wrapping has already determined each row.
            StringBuilder value=new StringBuilder(); line.accept((i,style,point)->{value.appendCodePoint(point);return true;});
            labels.add(new Text(value.toString(),rowY,0xAAAAAA,true,false)); rowY+=12;
        }
        rowY+=6;
    }
    protected int row(String label) {
        int y=rowY; labels.add(new Text(label,y+6,0xD4D4D4,false,false)); rowY+=24; return y;
    }
    protected <T extends AbstractWidget> T place(T widget,int offset) {
        controls.add(new Placed(addWidget(widget),offset)); widget.setY(formTop+offset-scroll); return widget;
    }
    protected EditBox input(String label,String value,int limit,Consumer<String> change,boolean enabled) {
        int y=row(label);
        var field=new EditBox(font,controlX,0,controlWidth,20,Component.literal(label));
        field.setMaxLength(limit);field.setValue(value);field.setResponder(change);field.setEditable(enabled);
        return place(field,y);
    }
    protected void value(String label,String value) {
        int y=row(label);
        var field=new EditBox(font,controlX,0,controlWidth,12,Component.literal(label));
        field.setMaxLength(8192);field.setValue(value);field.setEditable(false);
        field.setBordered(false);field.setTextColorUneditable(0xAAAAAA);
        if(font.width(value)>controlWidth)field.setTooltip(Tooltip.create(Component.literal(value)));
        place(field,y+6);
    }
    protected Checkbox toggle(String label,boolean checked,Consumer<Boolean> change,boolean enabled) {
        int y=row(label);
        var widget=Checkbox.builder(Component.literal(checked?"On":"Off"),font).pos(controlX,0).selected(checked)
            .onValueChange((box,v)->change.accept(v)).build();
        widget.active=enabled;return place(widget,y+1);
    }
    protected Button action(String label,String caption,Runnable run,boolean enabled) {
        int y=row(label);
        var widget=Button.builder(Component.literal(caption),b->run.run()).bounds(controlX,0,controlWidth,20).build();
        widget.active=enabled;return place(widget,y);
    }
    protected void actions(String label,String first,Runnable a,String second,Runnable b,boolean enabled) {
        int y=row(label), w=(controlWidth-6)/2;
        var one=Button.builder(Component.literal(first),button->a.run()).bounds(controlX,0,w,20).build();
        var two=Button.builder(Component.literal(second),button->b.run()).bounds(controlX+w+6,0,controlWidth-w-6,20).build();
        one.active=two.active=enabled;place(one,y);place(two,y);
    }
    protected Button choice(String label,String selected,List<Choice> options,Consumer<String> change,boolean enabled) {
        int y=row(label);
        String text=options.stream().filter(o->o.id().equals(selected)).map(Choice::label).findFirst().orElse(selected.isBlank()?"Choose…":selected);
        var button=Button.builder(Component.literal(font.plainSubstrByWidth(text,controlWidth-24)+" ▾"),b->openDropdown(b,options,selected,change))
            .bounds(controlX,0,controlWidth,20).build();
        if(font.width(text)>controlWidth-24)button.setTooltip(Tooltip.create(Component.literal(text)));
        button.active=enabled&&!options.isEmpty();
        return place(button,y);
    }
    protected List<Choice> options(String... pairs) {
        var result=new ArrayList<Choice>();for(int i=0;i<pairs.length;i+=2)result.add(new Choice(pairs[i],pairs[i+1]));return result;
    }
    protected void rebuildForm() { rebuildWidgets(); }
    /** Scrolls so a row offset (from row()) is in view. */
    protected void reveal(int offset) { scroll=Math.clamp(offset-12,0,maxScroll()); }

    private int maxScroll() { return Math.max(0,rowY-(formBottom-formTop)); }
    private void layout() {
        scroll=Math.clamp(scroll,0,maxScroll());
        for(var placed:controls) {
            var widget=placed.widget();widget.setY(formTop+placed.offset()-scroll);
            // Partly visible rows are clipped while drawing; clicks outside the form never reach them.
            widget.visible=widget.getY()+widget.getHeight()>formTop&&widget.getY()<formBottom;
        }
    }
    private void openDropdown(Button button,List<Choice> options,String selected,Consumer<String> change) {
        closeDropdown();anchor=button;choices=List.copyOf(options);choose=change;
        choiceIndex=0;for(int i=0;i<choices.size();i++)if(choices.get(i).id().equals(selected))choiceIndex=i;
        popupWidth=button.getWidth();popupX=button.getX();
        popupRows=Math.max(1,Math.min(7,(height-40)/22));popupRows=Math.min(popupRows,choices.size());
        int h=popupRows*22+4;
        popupY=Math.clamp(button.getY()+22,8,height-h-8);
        choiceOffset=Math.clamp(choiceIndex-popupRows/2,0,choices.size()-popupRows);
        popupWidgets();
    }
    private void popupWidgets() {
        popupButtons.forEach(this::removeWidget);popupButtons.clear();
        for(int i=0;i<popupRows;i++) {
            int index=choiceOffset+i;var option=choices.get(index);
            var button=addWidget(new Button(popupX+2,popupY+2+i*22,popupWidth-4,22,Component.literal(option.label()),b->selectChoice(index),n->n.get()) {
                @Override protected void renderWidget(GuiGraphics g,int x,int y,float delta) {
                    if(index==choiceIndex||option.enabled()&&isHoveredOrFocused())g.fill(getX(),getY(),getX()+getWidth(),getY()+22,0xFF505050);
                    g.drawString(font,font.plainSubstrByWidth(option.label(),getWidth()-12),getX()+6,getY()+7,option.enabled()?0xFFFFFF:0x777777);
                }
            });
            popupButtons.add(button);
        }
    }
    private void selectChoice(int index) {
        if(!choices.get(index).enabled())return;
        String id=choices.get(index).id();var callback=choose;closeDropdown();callback.accept(id);
    }
    private void closeDropdown() {
        popupButtons.forEach(this::removeWidget);popupButtons.clear();anchor=null;choices=List.of();
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(anchor!=null) {
            for(var item:List.copyOf(popupButtons))if(item.mouseClicked(x,y,button))return true;
            closeDropdown();return true;
        }
        if(button==0 && maxScroll()>0 && x>=left+contentWidth+4 && x<=left+contentWidth+12 && y>=formTop && y<=formBottom) {
            draggingScroll=true;scroll=(int)((y-formTop)/(formBottom-formTop)*maxScroll());layout();return true;
        }
        if(y<formTop||y>formBottom)for(var placed:controls)placed.widget().visible=false;
        try{return super.mouseClicked(x,y,button);}finally{layout();}
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(draggingScroll){scroll=(int)((y-formTop)/(formBottom-formTop)*maxScroll());layout();return true;}
        return anchor!=null || super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        draggingScroll=false;return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(anchor!=null) {
            choiceOffset=Math.clamp(choiceOffset-(int)Math.signum(vertical),0,choices.size()-popupRows);popupWidgets();return true;
        }
        if(y>=formTop&&y<=formBottom){scroll-=(int)(vertical*28);layout();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(anchor!=null) {
            if(key==GLFW.GLFW_KEY_ESCAPE){closeDropdown();return true;}
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){selectChoice(choiceIndex);return true;}
            if(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN) {
                choiceIndex=Math.clamp(choiceIndex+(key==GLFW.GLFW_KEY_UP?-1:1),0,choices.size()-1);
                if(choiceIndex<choiceOffset)choiceOffset=choiceIndex;
                if(choiceIndex>=choiceOffset+popupRows)choiceOffset=choiceIndex-popupRows+1;
                popupWidgets();
            }
            return true;
        }
        if(key==GLFW.GLFW_KEY_PAGE_DOWN||key==GLFW.GLFW_KEY_PAGE_UP){scroll+=(key==GLFW.GLFW_KEY_PAGE_DOWN?1:-1)*(formBottom-formTop);layout();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean charTyped(char character,int modifiers) {
        return anchor!=null || super.charTyped(character,modifiers);
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float delta) {
        if (!docked()) super.renderBackground(g,x,y,delta);
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        if (!docked()) { renderForm(g,x,y,delta); return; }
        // Widget tooltips normally go to Minecraft's current screen; keep them in this pane's coordinates.
        var tooltips=new java.util.LinkedHashMap<AbstractWidget,Tooltip>();
        Tooltip hovered=null;
        for(var child:children()) {
            if(!(child instanceof AbstractWidget widget)||widget.getTooltip()==null)continue;
            tooltips.put(widget,widget.getTooltip());
            if(anchor==null&&widget.visible&&widget.isMouseOver(x,y)&&y>=formTop&&y<formBottom)hovered=widget.getTooltip();
            widget.setTooltip(null);
        }
        try { renderForm(g,x,y,delta); }
        finally { tooltips.forEach(AbstractWidget::setTooltip); }
        if(hovered!=null)setTooltipForNextRenderPass(hovered.toCharSequence(minecraft),
            (screenWidth,screenHeight,tx,ty,tooltipWidth,tooltipHeight)->new org.joml.Vector2i(
                Math.max(4,Math.min(tx+10,width-tooltipWidth-4)),Math.max(4,Math.min(ty+10,height-tooltipHeight-4))),true);
    }
    private void renderForm(GuiGraphics g,int x,int y,float delta) {
        layout();
        super.render(g,x,y,delta);
        if (!docked()) g.drawCenteredString(font,title,width/2,12,0xFFFFFF);
        if (header) g.drawString(font,heading(),left,12,0xFFFFFF);
        g.fill(left-8,formTop-5,left+contentWidth+8,side?height:Math.min(formBottom,formTop+rowY)+3,0xA0101010);
        g.flush();
        g.enableScissor(left-2,formTop,left+contentWidth+2,formBottom);
        for(var label:labels) {
            int yy=formTop+label.offset()-scroll;
            if(yy+9<=formTop||yy>=formBottom)continue;
            g.drawString(font,font.plainSubstrByWidth(label.value(),label.fullWidth()?contentWidth:controlX-left-16),left,yy,label.color());
            // A faint rule after each section heading.
            if(label.rule())g.fill(left+font.width(label.value())+8,yy+4,left+contentWidth,yy+5,0x40E2D4A7);
        }
        for(var placed:controls)if(placed.widget().visible)placed.widget().render(g,x,y,delta);
        g.flush();
        g.disableScissor();
        if(maxScroll()>0){int h=formBottom-formTop,thumb=Math.max(16,h*h/(h+maxScroll()));int yy=formTop+(h-thumb)*scroll/maxScroll();
            g.fill(left+contentWidth+7,formTop,left+contentWidth+9,formBottom,0xFF333333);g.fill(left+contentWidth+6,yy,left+contentWidth+10,yy+thumb,0xFFAAAAAA);}
        int feedbackY=docked()?height-11:height-43;
        g.drawString(font,font.plainSubstrByWidth(feedback,contentWidth),left,feedbackY,0xD8CFAF);
        if(font.width(feedback)>contentWidth&&y>=feedbackY-3&&y<feedbackY+14)g.renderTooltip(font,font.split(Component.literal(feedback),contentWidth),x,y);
        if(anchor!=null) {
            g.pose().pushPose();g.pose().translate(0,0,300);
            g.fill(popupX-1,popupY-1,popupX+popupWidth+1,popupY+popupRows*22+5,0xFFAAAAAA);
            g.fill(popupX,popupY,popupX+popupWidth,popupY+popupRows*22+4,0xFF252525);
            for(var button:popupButtons)button.render(g,x,y,delta);
            if(choices.size()>popupRows)g.fill(popupX+popupWidth-3,popupY+2+choiceOffset*popupRows*22/choices.size(),popupX+popupWidth-1,popupY+2+(choiceOffset+popupRows)*popupRows*22/choices.size(),0xFFB0B0B0);
            g.pose().popPose();
        }
    }
    @Override public boolean isPauseScreen() { return false; }
}
