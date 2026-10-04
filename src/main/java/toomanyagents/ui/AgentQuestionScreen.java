package toomanyagents.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Pending questions remain answerable while the agent works or waits. */
public final class AgentQuestionScreen extends Screen {
    private final AgentUiAccess access;
    private final Screen parent;
    private final String agentId, requestId;
    private JsonObject request;
    private final List<Button> choices = new ArrayList<>();
    private List<FormattedCharSequence> lines = List.of();
    private String answer = "", feedback = "Choose an option or write your own answer.";
    private final Map<String, Set<String>> selected = new HashMap<>();
    private final Map<String, String> freeText = new HashMap<>();
    private int questionIndex;
    private boolean responding, resolved;
    private int left, contentWidth, detailsTop, detailsBottom, scroll, maxScroll, choicePage;
    private EditBox answerField;
    private Button sendButton, upButton, downButton;

    public AgentQuestionScreen(AgentUiAccess access, Screen parent, String agentId, JsonObject request) {
        super(Component.literal("Answer question"));
        this.access = access;
        this.parent = parent;
        this.agentId = agentId;
        this.request = request.deepCopy();
        requestId = AgentModels.text(request, "id");
    }

    private JsonArray questions() { return AgentModels.array(AgentModels.object(request,"payload"),"questions"); }
    private JsonObject question() { return questions().isEmpty()?new JsonObject():questions().get(Math.clamp(questionIndex,0,questions().size()-1)).getAsJsonObject(); }
    private String questionId() { return AgentModels.text(question(),"id"); }
    private boolean allowsText() { return question().has("allowFreeText")&&question().get("allowFreeText").getAsBoolean(); }

    @Override protected void init() {
        contentWidth = Math.min(760, width - 24);
        left = (width - contentWidth) / 2;
        var question=question();
        var options=AgentModels.array(question,"options");
        detailsTop=34;detailsBottom=Math.max(detailsTop+24,height-(options.size()*24+112));
        upButton = addRenderableWidget(Button.builder(Component.literal("↑"), button -> scrollBy(-pageHeight()))
            .bounds(left + contentWidth - 46, 8, 20, 20).build());
        downButton = addRenderableWidget(Button.builder(Component.literal("↓"), button -> scrollBy(pageHeight()))
            .bounds(left + contentWidth - 20, 8, 20, 20).build());
        choices.clear();
        StringBuilder details=new StringBuilder(AgentModels.text(question,"prompt"));
        String id=questionId();
        selected.computeIfAbsent(id,unused->new LinkedHashSet<>());
        for(int i=0;i<options.size();i++) {
            var option=options.get(i).getAsJsonObject();
            String value=AgentModels.text(option,"value"),label=AgentModels.text(option,"label");
            String description=AgentModels.text(option,"description");
            // Claude repeats the label as its description; show only real descriptions.
            if(description.equals(label))description="";
            if(!description.isBlank())details.append("\n\n").append(label).append(": ").append(description);
            var button=Button.builder(Component.literal(label),unused->{
                var selection=selected.get(id);
                if(selection.contains(value))selection.remove(value);
                else {
                    if(!question.has("multiSelect")||!question.get("multiSelect").getAsBoolean())selection.clear();
                    selection.add(value);
                }
                refresh();
            }).bounds(left,detailsBottom+8+i*24,contentWidth,20).build();
            if(!description.isBlank())button.setTooltip(Tooltip.create(Component.literal(description)));
            choices.add(addRenderableWidget(button));
        }
        answer=freeText.getOrDefault(id,"");
        answerField=addRenderableWidget(new EditBox(font,left,height-83,contentWidth,20,Component.literal("Your answer")));
        answerField.setMaxLength(4096);answerField.setValue(answer);answerField.setHint(Component.literal("Write an answer…"));
        answerField.setResponder(value->{answer=value;freeText.put(id,value);refresh();});
        answerField.visible=allowsText();
        int navWidth=(contentWidth-12)/3;
        var previous=addRenderableWidget(Button.builder(Component.literal("← Previous"),button->{questionIndex--;rebuildWidgets();})
            .bounds(left,height-57,navWidth,20).build());
        previous.active=questionIndex>0;
        var next=addRenderableWidget(Button.builder(Component.literal("Next →"),button->{questionIndex++;rebuildWidgets();})
            .bounds(left+navWidth+6,height-57,navWidth,20).build());
        next.active=questionIndex+1<questions().size();
        addRenderableWidget(Button.builder(Component.literal("Back to chat"),button->onClose())
            .bounds(left+2*(navWidth+6),height-57,navWidth,20).build());
        sendButton=addRenderableWidget(Button.builder(Component.literal("Send answers"),button->respond())
            .bounds(left,height-31,contentWidth,20).build());
        lines=font.split(Component.literal(details.toString()),contentWidth-24);
        maxScroll=Math.max(0,lines.size()*11-(detailsBottom-detailsTop-12));scroll=Math.clamp(scroll,0,maxScroll);
        refresh();setInitialFocus(allowsText()?answerField:sendButton);
    }

    @Override public void tick() {
        JsonObject current = null;
        for (var item : AgentModels.interactions(access.snapshot(agentId))) {
            if (requestId.equals(AgentModels.text(item.getAsJsonObject(), "id")) && java.util.Set.of("pending","resolving").contains(AgentModels.text(item.getAsJsonObject(),"status"))) current = item.getAsJsonObject();
        }
        if (current == null) {
            if (!resolved && !responding) feedback = "This question is no longer pending.";
            resolved = true;
        } else if (!request.equals(current)) {
            request = current.deepCopy();
            rebuildWidgets();
        }
        refresh();
    }

    private boolean complete() {
        for(var value:questions()) {
            String id=AgentModels.text(value.getAsJsonObject(),"id");
            if(selected.getOrDefault(id,Set.of()).isEmpty()&&freeText.getOrDefault(id,"").isBlank())return false;
        }
        return !questions().isEmpty();
    }

    private JsonObject resolution() {
        var result=new JsonObject();result.addProperty("kind","user_answer");
        var answers=new JsonObject();
        for(var value:questions()) {
            String id=AgentModels.text(value.getAsJsonObject(),"id");
            var answer=new JsonObject();var selection=new JsonArray();
            for(String option:selected.getOrDefault(id,Set.of()))selection.add(option);
            answer.add("selected",selection);
            String text=freeText.getOrDefault(id,"").strip();
            if(!text.isBlank())answer.addProperty("freeText",text);
            answers.add(id,answer);
        }
        result.add("answers",answers);return result;
    }

    private void refresh() {
        if(sendButton==null)return;
        boolean editable=!responding&&!resolved&&!AgentModels.text(request,"status").equals("resolving")&&!AgentModels.text(access.snapshot(agentId),"status").equals("disconnected");
        var options=AgentModels.array(question(),"options");
        for(int i=0;i<choices.size();i++) {
            var option=options.get(i).getAsJsonObject();
            choices.get(i).active=editable;
            choices.get(i).setMessage(Component.literal((selected.getOrDefault(questionId(),Set.of()).contains(AgentModels.text(option,"value"))?"✓ ":"")+AgentModels.text(option,"label")));
        }
        answerField.setEditable(editable&&allowsText());
        sendButton.active=editable&&complete();
        sendButton.setMessage(Component.literal(responding?"Sending…":"Send answers"));
        upButton.active=scroll>0;downButton.active=scroll<maxScroll;
    }

    private void respond() {
        if (!sendButton.active) return;
        responding = true;
        feedback = "Sending answer…";
        refresh();
        CompletableFuture<Void> operation;
        try { operation = access.respond(agentId, requestId, resolution()); }
        catch (RuntimeException failure) { completed(failure); return; }
        operation.whenComplete((unused, failure) -> screenExecutor.execute(() -> completed(failure)));
    }

    private void completed(Throwable failure) {
        responding = false;
        if (failure == null) {
            resolved = true;
            if (minecraft.screen == this) minecraft.setScreen(parent);
        } else feedback = resolved ? "This question is no longer pending." : AgentModels.error(failure);
        refresh();
    }

    private int pageHeight() { return Math.max(22, detailsBottom - detailsTop - 12); }
    private void scrollBy(int amount) { scroll = Math.clamp(scroll + amount, 0, maxScroll); refresh(); }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x <= left + contentWidth && y >= detailsTop && y <= detailsBottom) {
            scrollBy(-(int) (vertical * 33));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBy((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * pageHeight());
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && answerField.isFocused()) {
            respond();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    public JsonObject diagnostics() {
        var result = request.deepCopy();
        result.addProperty("answer", answer);
        result.addProperty("questionIndex", questionIndex);
        result.add("resolution",resolution());
        result.addProperty("scroll", scroll);
        result.addProperty("maxScroll", maxScroll);
        result.addProperty("responding", responding);
        result.addProperty("resolved", resolved);
        return result;
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, "Question "+(questionIndex+1)+" of "+questions().size(), left, 14, 0xFFDC80);
        graphics.fill(left, detailsTop, left + contentWidth, detailsBottom, 0xB0101010);
        graphics.enableScissor(left + 4, detailsTop + 4, left + contentWidth - 8, detailsBottom - 4);
        for (int i = 0; i < lines.size(); i++) {
            int y = detailsTop + 6 + i * 11 - scroll;
            if (y >= detailsTop - 11 && y < detailsBottom) graphics.drawString(font, lines.get(i), left + 8, y, 0xEEEEEE);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int track = detailsBottom - detailsTop - 8;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = detailsTop + 4 + (track - thumb) * scroll / maxScroll;
            graphics.fill(left + contentWidth - 5, detailsTop + 4, left + contentWidth - 3, detailsBottom - 4, 0xFF444444);
            graphics.fill(left + contentWidth - 5, y, left + contentWidth - 3, y + thumb, 0xFFAAAAAA);
        }

        graphics.drawString(font, font.plainSubstrByWidth(feedback, contentWidth), left, height - 98, 0xBDBDBD);
        if (font.width(feedback) > contentWidth && mouseY >= height - 100 && mouseY < height - 86) graphics.renderTooltip(font, font.split(Component.literal(feedback), contentWidth), mouseX, mouseY);
    }
}
