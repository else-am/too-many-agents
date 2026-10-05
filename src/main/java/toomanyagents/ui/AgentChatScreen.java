package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Chat stays live while the integrated server and agent work. */
public final class AgentChatScreen extends Screen {
    private final AgentUiAccess access;
    private String agentId;
    private JsonObject creationSettings;
    private final JsonObject roleSelection = new JsonObject();
    private String creationProjectName="Choose project";
    private boolean creationGit;
    private String environmentProject;
    private Checkbox worktreeBox, minecraftBox;
    private Button projectButton;
    private Consumer<String> created;
    private final List<String> bodies = new ArrayList<>();
    private int catalogVersion;
    private String permissionOverride;
    private boolean executionEdited;
    private EditBox nameField, bodySearch;
    // A random starter name clears when the field is first clicked, until something is typed.
    private String suggestedName = "";
    private boolean nameSuggested;
    private Button providerButton, bodyButton;
    private List<PickerChoice> bodyChoices;
    private Screen dockParent;
    private Runnable closePanel;
    private Runnable toggleInventory;
    private boolean compact;
    // Docked settings sit beside this chat, or cover its transcript and composer when space is short.
    private AgentSettingsScreen settingsPanel;
    private boolean settingsCovering;
    private final AgentModels models = new AgentModels();
    private JsonObject state = new JsonObject(), request = new JsonObject(), question = new JsonObject(), pointing;
    private String draft = "", requestKey = "", feedback = "", transcriptKey = "";
    private boolean loadingModels, sending, stopping, bodyChecked;
    private int left, contentWidth, transcriptTop, transcriptBottom, scroll, maxScroll;
    private final List<Line> lines = new ArrayList<>();
    private ChatInput composer;
    private Button permissionsButton;
    private List<PickerChoice> pickerChoices;
    private int pickerOffset, pickerPageSize, pickerRowHeight;
    private record PickerChoice(String label, String description, String icon, Runnable select) {
        PickerChoice(String label, String description, Runnable select) { this(label, description, "", select); }
    }
    private Button modelButton, effortButton, speedButton, settingsButton, archiveButton, inventoryButton, sendButton;
    private List<AbstractWidget> pickerWidgets;
    private Button pickerAnchor;
    private int pickerX, pickerY, pickerWidth, pickerHeight;

    private record Line(FormattedCharSequence text, int color, long replyVersion, int inset, String copyText, ChatMarkdown.Row rich) {
        Line(FormattedCharSequence text,int color,long replyVersion,int inset,String copyText) {this(text,color,replyVersion,inset,copyText,null);}
        Line(FormattedCharSequence text,int color,long replyVersion,int inset) {this(text,color,replyVersion,inset,null);}
        Line(FormattedCharSequence text,int color,long replyVersion) {this(text,color,replyVersion,0);}
    }
    private record RichPanel(ChatMarkdown.Panel panel, int lineOffset, int inset) {}
    private final List<RichPanel> richPanels = new ArrayList<>();
    private record RichMedia(ChatMarkdown.Media media,int lineOffset,int inset) {}
    private final List<RichMedia> richMedia = new ArrayList<>();
    private final ChatImages chatImages = new ChatImages(this::loadChatAsset);
    private RichMedia pressedMedia;
    private String pressedLink;
    private boolean developmentTranscript;
    private java.util.function.BiFunction<String,String,CompletableFuture<JsonObject>> developmentAssets;
    private java.util.function.Function<String,CompletableFuture<Void>> developmentLinks;

    public void developmentAssets(java.util.function.BiFunction<String,String,CompletableFuture<JsonObject>> assets,
                                  java.util.function.Function<String,CompletableFuture<Void>> links) {
        if(!Boolean.getBoolean("too_many_agents.devWorld"))throw new IllegalStateException("Development only");
        developmentAssets=assets;developmentLinks=links;chatImages.close();
    }

    private CompletableFuture<JsonObject> loadChatAsset(String kind,String source) {
        return developmentTranscript && developmentAssets!=null ? developmentAssets.apply(kind,source) : access.chatAsset(agentId,kind,source);
    }

    /** Called only by the guarded development-world UI controls. Uses the real transcript renderer. */
    public JsonObject developmentTranscript(JsonObject request) {
        if (!Boolean.getBoolean("too_many_agents.devWorld")) throw new IllegalStateException("Development only");
        developmentTranscript=true;
        if (request.has("rows")) {
            transcript=new JsonObject();
            transcript.add("rows",request.getAsJsonArray("rows").deepCopy());
            loadedSequence++;
            transcriptKey="";
            rebuildTranscript();
        }
        if (request.has("scroll")) scroll=Math.clamp(request.get("scroll").getAsInt(),0,maxScroll);
        var result=new JsonObject();
        if(request.has("clipboardEquals")) result.addProperty("clipboardMatches",
            minecraft.keyboardHandler.getClipboard().equals(request.get("clipboardEquals").getAsString()));
        result.addProperty("scroll",scroll); result.addProperty("maxScroll",maxScroll);
        result.addProperty("left",left); result.addProperty("width",contentWidth);
        result.addProperty("top",transcriptTop); result.addProperty("bottom",transcriptBottom);
        result.addProperty("selected",selectedText());
        result.addProperty("feedback",feedback);
        var rendered=new com.google.gson.JsonArray();
        for(int i=0;i<lines.size();i++) {
            var row=new JsonObject(); var line=lines.get(i);
            row.addProperty("text",lineText(line)); row.addProperty("copy",line.copyText());
            row.addProperty("x",lineX(line)); row.addProperty("y",transcriptTop+6+i*LINE_HEIGHT-scroll);
            rendered.add(row);
        }
        result.add("lines",rendered);
        var panels=new com.google.gson.JsonArray();
        for(var entry:richPanels) {
            var panel=entry.panel();var item=new JsonObject();
            item.addProperty("label",panel.label);item.addProperty("source",panel.source);
            item.addProperty("x",left+8+entry.inset()+panel.inset);
            item.addProperty("y",transcriptTop+6+(entry.lineOffset()+panel.first)*LINE_HEIGHT-scroll);
            item.addProperty("width",panel.width);item.addProperty("scroll",panel.scroll);item.addProperty("maxScroll",panel.maxScroll());
            panels.add(item);
        }
        result.add("panels",panels);
        result.addProperty("textureBytes",chatImages.textureBytes());result.addProperty("imageCacheSize",chatImages.size());
        var media=new com.google.gson.JsonArray();
        for(var entry:richMedia) {
            var item=new JsonObject();var m=entry.media();
            int y=transcriptTop+6+(entry.lineOffset()+m.first())*LINE_HEIGHT-scroll;
            item.addProperty("kind",m.kind());item.addProperty("source",m.source());item.addProperty("alt",m.alt());
            item.addProperty("x",left+8+entry.inset()+m.inset());item.addProperty("y",y);item.addProperty("width",m.width());
            item.addProperty("height",m.height());
            if(y<transcriptBottom && y+(m.end()-m.first())*LINE_HEIGHT>transcriptTop) {
                var image=chatImages.get(m.kind(),m.source());
                item.addProperty("loading",image.loading);item.addProperty("error",image.error);item.addProperty("ready",image.texture!=null);
            }
            media.add(item);
        }
        result.add("media",media);
        return result;
    }

    private int lineWidthAt(Line line, int character) {
        return line.rich() == null ? ChatMarkdown.prefixWidth(font, line.text(), character)
            : line.rich().widthAt(font, character);
    }

    private int lineX(Line line) {
        return left + 8 + line.inset() - (line.rich() != null && line.rich().panel() != null ? line.rich().panel().scroll : 0);
    }
    private record Disclosure(AbstractWidget button, int line) {}
    private java.util.Map<String, String> questionDrafts = new java.util.HashMap<>();
    private java.util.Set<String> respondingRequests = new java.util.HashSet<>();
    private java.util.Map<String, String> requestErrors = new java.util.HashMap<>();
    private String focusedQuestion = "";
    private java.util.Map<String, EditBox> questionFields = new java.util.HashMap<>();
    private record MessageBubble(int firstLine, int endLine, int inset, int width) {}
    private List<MessageBubble> messageBubbles = new ArrayList<>();
    private final List<Disclosure> disclosures = new ArrayList<>();
    private JsonObject transcript = new JsonObject();
    private JsonObject catalog = new JsonObject(), turnDetails = new JsonObject(), olderCursor = new JsonObject();
    private boolean detailsLoading;
    private long markedReply;
    private String before = "", expandedGroup = "", expandedRow = "";
    private int groupOffset, textOffset, queryVersion;
    private int thinkingLine = -1;
    private List<Button> queueControls = new ArrayList<>();
    private int queueOffset, queueRows;
    private List<String> draftImages = new ArrayList<>();
    private boolean importingImages;
    private long loadedSequence = -1, nextTranscriptPoll;
    private boolean transcriptLoading, preserveScroll;
    private static final int LINE_HEIGHT = 12;
    private record TextPosition(int line,int character) {}
    private TextPosition selectionAnchor, selectionEnd;
    private boolean selectingText;
    private long lastTextClick;
    private double lastTextClickX,lastTextClickY;
    private int textClickCount,selectionMode;
    private TextPosition selectionUnitStart,selectionUnitEnd;

    private TextPosition[] selectionUnit(TextPosition position,int mode) {
        int first=position.line(),last=position.line();
        while(first>0 && continuesParagraph(first-1))first--;
        while(last+1<lines.size() && continuesParagraph(last))last++;
        if(mode==3)return new TextPosition[]{new TextPosition(first,0),new TextPosition(last,lineText(lines.get(last)).length())};
        var paragraph=new StringBuilder();
        int clicked=0;
        for(int i=first;i<=last;i++) {
            if(i==position.line())clicked=paragraph.length()+position.character();
            String copy=lines.get(i).copyText();
            paragraph.append(copy==null?lineText(lines.get(i)):copy);
        }
        String text=paragraph.toString();
        if(text.isEmpty())return new TextPosition[]{position,position};
        clicked=Math.min(clicked,text.length()-1);
        var words=java.text.BreakIterator.getWordInstance(java.util.Locale.ROOT);
        words.setText(text);
        int start=words.preceding(clicked+1),end=words.following(clicked);
        return new TextPosition[]{paragraphPosition(first,last,Math.max(0,start)),paragraphPosition(first,last,end<0?text.length():end)};
    }

    private boolean continuesParagraph(int index) {
        String copy=lines.get(index).copyText();
        return copy!=null && !copy.endsWith("\n");
    }

    private TextPosition paragraphPosition(int first,int last,int offset) {
        for(int i=first;i<=last;i++) {
            String visible=lineText(lines.get(i)),copy=lines.get(i).copyText();
            int length=copy==null?visible.length():copy.length();
            if(offset<=visible.length() || i==last)return new TextPosition(i,Math.min(offset,visible.length()));
            offset-=length;
            if(offset<0)return new TextPosition(i,visible.length());
        }
        return new TextPosition(last,lineText(lines.get(last)).length());
    }


    private static String lineText(Line line) {
        var text=new StringBuilder();
        line.text().accept((index,style,codePoint)->{text.appendCodePoint(codePoint);return true;});
        return text.toString();
    }

    private TextPosition textPosition(double mouseX,double mouseY) {
        int index=Math.clamp((int)Math.floor((mouseY-transcriptTop-6+scroll)/LINE_HEIGHT),0,lines.size()-1);
        var line=lines.get(index);
        String text=lineText(line);
        double target=mouseX-lineX(line);
        int offset=0;
        while(offset<text.length()) {
            int next=offset+Character.charCount(text.codePointAt(offset));
            if(target<(lineWidthAt(line,offset)+lineWidthAt(line,next))/2.0)break;
            offset=next;
        }
        return new TextPosition(index,offset);
    }

    private boolean hasSelection() {return selectionAnchor!=null && selectionEnd!=null && !selectionAnchor.equals(selectionEnd);}
    private TextPosition selectionStart() {
        return selectionAnchor.line()<selectionEnd.line() || selectionAnchor.line()==selectionEnd.line() && selectionAnchor.character()<=selectionEnd.character()?selectionAnchor:selectionEnd;
    }
    private TextPosition selectionFinish() {return selectionStart()==selectionAnchor?selectionEnd:selectionAnchor;}
    private void clearSelection() {selectionAnchor=null;selectionEnd=null;selectingText=false;textClickCount=0;selectionUnitStart=null;selectionUnitEnd=null;}

    private String selectedText() {
        if(!hasSelection())return "";
        var start=selectionStart();var end=selectionFinish();
        var text=new StringBuilder();
        for(int i=start.line();i<=end.line();i++) {
            var line=lines.get(i);
            String visible=lineText(line);
            int from=i==start.line()?Math.min(start.character(),visible.length()):0;
            if(i==end.line())text.append(visible.substring(from,Math.min(end.character(),visible.length())));
            else {
                String copy=line.copyText()==null?visible+"\n":line.copyText();
                text.append(copy.substring(Math.min(from,copy.length())));
            }
        }
        return text.toString();
    }

    private void drawSelection(GuiGraphics graphics,int index,int y) {
        if(!hasSelection())return;
        var start=selectionStart();var end=selectionFinish();
        if(index<start.line() || index>end.line())return;
        var line=lines.get(index);String text=lineText(line);
        int from=index==start.line()?Math.min(start.character(),text.length()):0;
        int to=index==end.line()?Math.min(end.character(),text.length()):text.length();
        int x=lineX(line);
        graphics.fill(x+lineWidthAt(line,from),y-1,x+lineWidthAt(line,to),y+font.lineHeight+1,0xB05A7EAA);
    }


    public AgentChatScreen(AgentUiAccess access, String agentId) {
        this(access, agentId, null);
    }

    public AgentChatScreen(AgentUiAccess access, String agentId, JsonObject pointing) {
        super(Component.literal("Agent chat"));
        this.access = access;
        this.agentId = agentId;
        state = access.snapshot(agentId);
        this.pointing = pointing != null && state.has("minecraftAccess") && state.get("minecraftAccess").getAsBoolean()
            ? pointing.deepCopy() : null;
        models.model = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "model");
        models.effort = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "reasoningLevel");
        String serviceTier = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "serviceTier");
        models.serviceTier = serviceTier.isBlank() ? "default" : serviceTier;
        readRequest();
    }

    public AgentChatScreen(AgentUiAccess access, JsonObject creationSettings, Consumer<String> created) {
        super(Component.literal("New agent"));
        this.access = access;
        this.agentId = "";
        this.creationSettings = creationSettings.deepCopy();
        this.created = created;
        readCreationProject();
        if (AgentModels.text(this.creationSettings, "name").isBlank()) {
            var taken = new java.util.HashSet<String>();
            for (var agent : access.worldAgents()) taken.add(AgentModels.text(agent.getAsJsonObject(), "name"));
            suggestedName = toomanyagents.StarterAgents.name(taken);
            nameSuggested = true;
            this.creationSettings.addProperty("name", suggestedName);
        }
        for (String[] defaultValue : new String[][]{{"body", toomanyagents.StarterAgents.body()}, {"mode", "survival"}, {"projectId", ""}}) {
            if (AgentModels.text(this.creationSettings, defaultValue[0]).isBlank()) this.creationSettings.addProperty(defaultValue[0], defaultValue[1]);
        }
        if (!this.creationSettings.has("worktree")) this.creationSettings.addProperty("worktree", false);
        state = this.creationSettings;
        models.model = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "model");
        models.effort = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "reasoningLevel");
        String tier = AgentModels.text(draft() ? state : AgentSettingsScreen.settings(state), "serviceTier");
        models.serviceTier = tier.isBlank() ? "default" : tier;
    }

    private void readCreationProject(){
        creationProjectName="Choose project";
        if (!creationSettings.has("minecraftAccess")) creationSettings.addProperty("minecraftAccess", true);
        String projectId=AgentModels.text(creationSettings,"projectId");
        if (AgentModels.worldProject(access.projects(), projectId)) {
            creationSettings.addProperty("minecraftAccess", true);
            creationSettings.addProperty("worktree", false);
        }
        for(var item:AgentModels.array(access.projects(),"projects")){
            var project=item.getAsJsonObject();
            if(AgentModels.text(project,"id").equals(projectId))creationProjectName=AgentModels.text(project,"name");
        }
        if(projectId.equals(environmentProject))return;
        environmentProject=projectId;creationGit=false;
        if(projectId.isBlank())return;
        access.projectCreationOptions(projectId).whenComplete((options,failure)->net.minecraft.client.Minecraft.getInstance().execute(()->{
            if(!projectId.equals(environmentProject))return;
            if(failure!=null){feedback="Worktree availability unavailable: "+AgentModels.error(failure);return;}
            creationGit=options.has("worktreeAvailable") && options.get("worktreeAvailable").getAsBoolean();
            if(font!=null)refreshButtons();
        }));
    }

    public String agentId() { return agentId; }
    public boolean draft() { return agentId.isBlank(); }

    private boolean worldDraft() {
        return draft() && AgentModels.worldProject(access.projects(), AgentModels.text(creationSettings, "projectId"));
    }

    private JsonObject settings() {
        if (!draft()) {
            var result=AgentSettingsScreen.settings(state);
            result.add("provider", selectedProvider());
            if (permissionOverride != null) result.addProperty("permissionMode", permissionOverride);
            return result;
        }
        var result = creationSettings.deepCopy();
        result.addProperty("model", models.model);
        result.addProperty("reasoningLevel", models.effort);
        result.addProperty("serviceTier", models.serviceTier);
        return result;
    }

    private boolean availableHere() {
        return draft() || activeAgent() && state.has("currentWorld") && state.get("currentWorld").getAsBoolean();
    }

    private boolean backendAvailable() {
        return !developmentTranscript && !Set.of("disconnected", "unavailable").contains(AgentModels.text(state, "status"));
    }

    private static String bodyLabel(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The host owns this chat's bounds and input while retaining its draft. */
    public AgentChatScreen dock(Screen parent, Runnable closePanel, Runnable toggleInventory) {
        this.dockParent = java.util.Objects.requireNonNull(parent);
        this.closePanel = java.util.Objects.requireNonNull(closePanel);
        this.toggleInventory = java.util.Objects.requireNonNull(toggleInventory);
        return this;
    }

    public boolean docked() { return dockParent != null; }

    public AgentSettingsScreen settingsPanel() { return settingsPanel; }
    /** Saves and closes docked settings; a failed save leaves them open with the error. */
    public void closeSettings() { if (settingsPanel != null) settingsPanel.onClose(); }
    void closeSettingsThen(Runnable next) { if (settingsPanel == null) next.run(); else settingsPanel.closeThen(next); }

    /** The transcript and composer area, in this chat's coordinates, that covering settings replace. */
    public int[] settingsBounds() { return new int[]{left, transcriptTop, contentWidth, composerBaseline() + 20 - transcriptTop}; }

    public void cover(boolean covering) {
        if (settingsCovering == covering) return;
        settingsCovering = covering;
        if (covering) { setFocused(null); composer.setFocused(false); clearSelection(); }
        refreshButtons();
        transcriptKey = "";
        rebuildTranscript();
    }
    public Screen returnScreen() { return docked() ? dockParent : this; }

    private void executeUi(Runnable action) {
        // Completions must survive visits to settings and embedded rendering.
        net.minecraft.client.Minecraft.getInstance().execute(action);
    }

    private int composerBaseline() { return height - 52; }

    boolean pickerOpen() { return pickerAnchor != null; }

    @Override public void onClose() {
        chatImages.close();
        closePicker();
        if (docked()) closePanel.run();
        else super.onClose();
    }

    @Override public void removed() { chatImages.close(); }
    public static JsonObject imageResources() { return ChatImages.resources(); }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!docked()) super.renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override protected void setInitialFocus() {
        // Screen's default keyboard traversal would replace the composer's focus.
        if (getFocused() == null && composer != null && !settingsCovering) setInitialFocus(composer);
    }

    @Override protected void init() {
        closePicker();
        compact = width < 560;
        contentWidth = Math.max(180, Math.min(760, width - 24));
        left = (width - contentWidth) / 2;
        transcriptTop = draft() ? (compact ? 83 : 57) : compact ? 56 : 29;
        transcriptBottom = height - 61;
        int controlsY = compact ? 29 : 7;
        // Bottom row widths and x positions are set by layoutControls as the choices load.
        int controlY = height - 25;
        providerButton = addRenderableWidget(new Button(left, controlY, 20, 20, Component.empty(), button -> togglePicker(providerButton), message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                // The name lives in the tooltip and picker; the button shows only the mark.
                graphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(!active ? "widget/button_disabled"
                    : isHoveredOrFocused() ? "widget/button_highlighted" : "widget/button"), getX(), getY(), getWidth(), getHeight());
                ProviderIcon.render(graphics, AgentModels.text(state, "providerId"), getX() + (getWidth() - ProviderIcon.SIZE) / 2, getY() + 6);
            }
        });
        permissionsButton = addRenderableWidget(Button.builder(Component.empty(), button -> togglePicker(permissionsButton))
            .bounds(left, controlY, 92, 20).build());
        modelButton = addRenderableWidget(Button.builder(Component.empty(), button -> requestModels(() -> togglePicker(modelButton)))
            .bounds(left, controlY, 92, 20).build());
        effortButton = addRenderableWidget(Button.builder(Component.empty(), button -> requestModels(() -> togglePicker(effortButton)))
            .bounds(left, controlY, 60, 20).build());
        speedButton = addRenderableWidget(new Button(left, controlY, 20, 20,
                Component.literal("Increase speed"), button -> {
                    closePicker();
                    executionEdited = true;
                    models.nextServiceTier();
                    refreshButtons();
                }, message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                graphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(!active ? "widget/button_disabled"
                    : isHoveredOrFocused() ? "widget/button_highlighted" : "widget/button"), getX(), getY(), 20, 20);
                int color = !active ? 0xFF666666 : !models.serviceTier.isBlank() && !models.serviceTier.equals("default") ? 0xFFFFD45A : 0xFFAAAAAA;
                int x = getX() + 6, y = getY() + 3;
                // A small pixel bolt avoids depending on a font's symbol coverage.
                for (int row = 0; row < 6; row++) {
                    graphics.fill(x + 4 - row / 2, y + row + 1, x + 7 - row / 2, y + row + 2, color);
                    graphics.fill(x + 3 - row / 2, y + row + 7, x + 6 - row / 2, y + row + 8, color);
                }
            }
        });
        inventoryButton = addRenderableWidget(Button.builder(Component.literal("Inventory"), button -> toggleInventory.run())
            .bounds(compact ? left : left + contentWidth - 246, controlsY, 70, 20).build());
        // One archive, no confirmation: restore lives in Mod settings → Archive.
        archiveButton = addRenderableWidget(Button.builder(Component.literal("Archive"), button -> archive())
            .bounds(compact ? left + 74 : left + contentWidth - 170, controlsY, 58, 20).build());
        settingsButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if(settingsPanel!=null){settingsPanel.onClose();return;}
            var screen=new AgentSettingsScreen(access,returnScreen(),settings(),changes -> {
                if(draft()){
                    creationSettings=changes.deepCopy();state=creationSettings;readCreationProject();
                    if(!AgentModels.text(creationSettings,"name").equals(suggestedName))nameSuggested=false;
                    if(nameField!=null)nameField.setValue(AgentModels.text(creationSettings,"name"));
                    catalogVersion++;loadingModels=false;models.load(new JsonObject());
                    models.model=AgentModels.text(creationSettings,"model");models.effort=AgentModels.text(creationSettings,"reasoningLevel");
                    models.serviceTier=AgentModels.text(creationSettings,"serviceTier");
                    if(worktreeBox!=null&&worktreeBox.selected()!=(creationSettings.has("worktree")&&creationSettings.get("worktree").getAsBoolean()))worktreeBox.onPress();
                    requestModels(this::refreshButtons);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                return access.updateSettings(agentId,changes);
            },draft()?null:agentId);
            screen.rememberRole(roleSelection);
            if(!docked()){minecraft.setScreen(screen);return;}
            settingsPanel=screen;
            screen.dock(()->{if(settingsPanel==screen){settingsPanel=null;cover(false);refreshButtons();}});
            refreshButtons();
        }).bounds(left+contentWidth-(compact?80:106),controlsY,80,20).build());
        if (draft()) {
            nameField = addRenderableWidget(new EditBox(font, left, 7, contentWidth - 30, 20, Component.literal("Agent name")) {
                @Override public void setFocused(boolean focused) {
                    super.setFocused(focused);
                    if (!nameSuggested) return;
                    if (focused) setValue("");
                    else if (getValue().isBlank()) setValue(suggestedName);
                }
            });
            nameField.setMaxLength(80);
            nameField.setValue(AgentModels.text(creationSettings, "name"));
            nameField.setHint(Component.literal("New agent"));
            nameField.setResponder(value -> {
                if (nameField.isFocused() && !value.isBlank()) nameSuggested = false;
                creationSettings.addProperty("name", value);
                refreshButtons();
            });
            bodyButton = addRenderableWidget(Button.builder(Component.empty(), button -> requestModels(() -> togglePicker(bodyButton)))
                .bounds(left, compact ? 55 : 29, 120, 20).build());
            minecraftBox = Checkbox.builder(Component.literal("Can act in Minecraft"), font)
                .selected(creationSettings.get("minecraftAccess").getAsBoolean())
                .onValueChange((box, value) -> {
                    creationSettings.addProperty("minecraftAccess", value);
                    refreshButtons();
                }).build();
            addRenderableWidget(minecraftBox);
            minecraftBox.visible = !worldDraft();
            projectButton = addRenderableWidget(Button.builder(Component.empty(), button -> togglePicker(projectButton))
                .bounds(left, 0, Math.min(180, contentWidth), 20).build());
            projectButton.visible = !worldDraft();
            boolean worktree = creationSettings.has("worktree") && creationSettings.get("worktree").getAsBoolean();
            worktreeBox = Checkbox.builder(Component.literal("Worktree"), font).selected(worktree)
                .onValueChange((box, value) -> creationSettings.addProperty("worktree", value)).build();
            worktreeBox.setTooltip(Tooltip.create(Component.literal("Work in a separate Git worktree instead of the project folder")));
            worktreeBox.visible = creationGit;
            addRenderableWidget(worktreeBox);
        } else { nameField = null; bodyButton = null; minecraftBox = null; worktreeBox = null; projectButton = null; }
        addRenderableWidget(Button.builder(Component.literal("×"), button -> onClose())
            .bounds(left + contentWidth - 20, 7, 20, 20).build());
        var previousComposer = composer;
        composer = addRenderableWidget(new ChatInput(font, left, composerBaseline(), contentWidth - 64));
        composer.setValue(draft);
        composer.keepEditingState(previousComposer);
        composer.setValueListener(this::draftChanged);
        sendButton = addRenderableWidget(Button.builder(Component.literal("Send"), button -> { if(!hasDraft())interrupt(); else send(); })
            .bounds(left + contentWidth - 50, composerBaseline(), 50, 20).build());
        disclosures.clear();
        transcriptKey = "";
        rebuildTranscript();
        requestTranscript();
        refreshButtons();
        if (!settingsCovering) setInitialFocus(composer);
        if (!models.available()) requestModels(() -> {});
        if (!bodyChecked && state.has("currentWorld") && state.get("currentWorld").getAsBoolean()) {
            bodyChecked = true;
            access.checkBody(agentId).whenComplete((unused, failure) -> executeUi(() -> {
                if (failure != null) feedback = AgentModels.error(failure);
            }));
        }
    }

    private List<String> images() {
        if(draftImages==null)draftImages=new ArrayList<>();
        return draftImages;
    }

    private boolean hasDraft() { return !draft.isBlank() || !images().isEmpty(); }

    @Override public void onFilesDrop(List<java.nio.file.Path> paths) {
        if(importingImages || sending || !availableHere())return;
        var imageFiles=new ArrayList<java.nio.file.Path>();
        var fileText=new StringBuilder();
        for(var path:paths) {
            if(ChatAttachments.isImage(path))imageFiles.add(path);
            else fileText.append(ChatAttachments.fileText(path)).append(' ');
        }
        if(!fileText.isEmpty())composer.insertText(fileText.toString());
        if(imageFiles.isEmpty())return;
        if(images().size()+imageFiles.size()>4) { feedback="Attach up to 4 images per message."; return; }
        importingImages=true;
        refreshButtons();
        CompletableFuture.supplyAsync(()->{
            var copies=new ArrayList<String>();
            try { for(var path:imageFiles)copies.add(ChatAttachments.copyImage(path)); }
            catch(Exception failure) {
                for(String copy:copies)try {java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(copy));}catch(java.io.IOException ignored) {}
                throw new java.util.concurrent.CompletionException(failure);
            }
            return copies;
        }).whenComplete((copies,failure)->executeUi(()->{
            importingImages=false;
            if(failure!=null)feedback=AgentModels.error(failure);
            else {images().addAll(copies);feedback="";}
            transcriptKey=""; rebuildTranscript(); refreshButtons();
        }));
    }

    private void paste() {
        if(importingImages || sending || !availableHere())return;
        String clipboard=minecraft.keyboardHandler.getClipboard();
        var path=ChatAttachments.imagePath(clipboard);
        if(path!=null) {onFilesDrop(List.of(path));return;}
        // Text remains ordinary text; bare names and URLs may stand beside copied files or image data.
        if(!ChatAttachments.mayHoldFiles(clipboard)) {composer.insertText(clipboard);return;}
        importingImages=true;
        refreshButtons();
        CompletableFuture.supplyAsync(()->{
            try {return ChatAttachments.pasteboard();}
            catch(Exception failure) {throw new java.util.concurrent.CompletionException(failure);}
        }).whenComplete((pasted,failure)->executeUi(()->{
            importingImages=false;
            if(pasted!=null && !pasted.files().isEmpty()) onFilesDrop(pasted.files());
            else if(pasted!=null && pasted.image()!=null) {
                if(images().size()<4) {images().add(pasted.image());feedback="";}
                else {
                    feedback="Attach up to 4 images per message.";
                    try {java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(pasted.image()));} catch(java.io.IOException ignored) {}
                }
            }
            else if(!clipboard.isEmpty()) composer.insertText(clipboard);
            else feedback=failure!=null?AgentModels.error(failure):"No image or text on clipboard. Try dropping the file.";
            transcriptKey="";rebuildTranscript();refreshButtons();
        }));
    }

    private void draftChanged(String value) {
        draft=value;
        boolean atBottom = scroll >= maxScroll;
        rebuildQueue();
        maxScroll = Math.max(0, lines.size() * LINE_HEIGHT - (transcriptBottom - transcriptTop - 12));
        scroll = atBottom ? maxScroll : Math.clamp(scroll, 0, maxScroll);
        positionDisclosures();
        refreshButtons();
    }

    private void requestModels(Runnable change) {
        if (models.available()) {
            change.run();
            refreshButtons();
            return;
        }
        if (loadingModels) return;
        loadingModels = true;
        refreshButtons();
        String provider = AgentModels.text(state, "providerId");
        int version = ++catalogVersion;
        var catalog = access.catalog(provider, AgentModels.text(AgentModels.object(state,"thread"),"environmentId"));
        var defaults = draft() ? access.projectExecutionOptions(AgentModels.text(creationSettings,"projectId")) : CompletableFuture.completedFuture(new JsonObject());
        catalog.thenCombine(defaults, (result, options) -> {
            var copy = result.deepCopy(); copy.add("draftDefaults",options); return copy;
        }).whenComplete((result, failure) -> executeUi(() -> {
            if (version != catalogVersion || !provider.equals(AgentModels.text(state, "providerId"))) return;
            loadingModels = false;
            if (failure != null) feedback = "Model list unavailable: " + AgentModels.error(failure);
            else {
                this.catalog = result;
                if (draft()) {
                    String selected = "";
                    boolean available = false;
                    for (var item : AgentModels.array(result, "providers")) {
                        var info = item.getAsJsonObject();
                        if (!info.has("available") || !info.get("available").getAsBoolean()) continue;
                        String id = AgentModels.text(info, "id");
                        if (selected.isBlank()) selected = id;
                        if (id.equals(provider)) available = true;
                    }
                    if (!available && !selected.isBlank()) {
                        creationSettings.addProperty("providerId", selected);
                        models.load(new JsonObject());
                        requestModels(change);
                        return;
                    }
                }
                if (draft()) {
                    var options = AgentModels.object(result,"draftDefaults");
                    if (provider.equals(AgentModels.text(options,"providerId"))) {
                        if(models.model.isBlank()) models.model=AgentModels.text(options,"model");
                        if(models.effort.isBlank()) models.effort=AgentModels.text(options,"reasoningLevel");
                        if(!creationSettings.has("serviceTier") && options.has("serviceTier")) models.serviceTier=AgentModels.text(options,"serviceTier");
                        if(!creationSettings.has("permissionMode") && options.has("permissionMode")) creationSettings.add("permissionMode",options.get("permissionMode"));
                    }
                }
                models.load(result);
                if (draft()) {
                    for (var item : AgentModels.array(result, "providers")) {
                        var info = item.getAsJsonObject();
                        if (AgentModels.text(info, "id").equals(provider)) creationSettings.add("provider", info.deepCopy());
                    }
                    bodies.clear();
                    for (var item : AgentModels.array(result, "bodies")) bodies.add(item.getAsString());
                    bodies.sort(String::compareTo);
                    String body = AgentModels.text(creationSettings, "body");
                    if (!bodies.contains(body)) creationSettings.addProperty("body", bodies.isEmpty() ? "" : bodies.getFirst());
                }
                feedback = models.available() ? "" : "No models available";
                if (models.available()) change.run();
            }
            refreshButtons();
        }));
    }

    private void closePicker() {
        if (pickerWidgets != null) {
            for (var widget : pickerWidgets) removeWidget(widget);
            pickerWidgets = null;
        }
        if (pickerAnchor != null) setFocused(pickerAnchor);
        pickerAnchor = null;
        bodySearch = null;
        setDragging(false);
    }

    private void togglePicker(Button anchor) {
        boolean wasOpen = pickerAnchor == anchor;
        closePicker();
        if (wasOpen) return;
        boolean effort = anchor == effortButton;
        if (effort && models.efforts().size() < 2) return;
        pickerChoices = new ArrayList<>();
        pickerOffset = 0;
        if (anchor == modelButton) {
            for (var model : models.choices()) {
                String id = AgentModels.text(model, "model");
                String name = AgentModels.text(model, "displayName");
                pickerChoices.add(new PickerChoice((id.equals(models.model) ? "✓ " : "") + (name.isBlank() ? id : name), "", () -> {
                    executionEdited = true;
                    models.model = id;
                    models.normalize();
                }));
            }
        } else if (anchor == providerButton) {
            if (!draft()) return;
            for (var item : AgentModels.array(catalog, "providers")) {
                var info = item.getAsJsonObject();
                String provider = AgentModels.text(info, "id");
                if (!info.has("available") || !info.get("available").getAsBoolean()) continue;
                pickerChoices.add(new PickerChoice((provider.equals(AgentModels.text(state, "providerId")) ? "✓ " : "")
                    + AgentModels.text(info, "displayName"), "", provider, () -> selectProvider(provider)));
            }
        } else if (anchor == projectButton) {
            String selected = AgentModels.text(creationSettings, "projectId");
            for (var item : AgentModels.array(access.projects(), "projects")) {
                var project = item.getAsJsonObject();
                if (AgentModels.text(project, "kind").equals("world")) continue;
                String id = AgentModels.text(project, "id");
                pickerChoices.add(new PickerChoice((id.equals(selected) ? "✓ " : "") + AgentModels.text(project, "name"), "", () -> {
                    if (id.equals(AgentModels.text(creationSettings, "projectId"))) return;
                    creationSettings.addProperty("projectId", id);
                    creationSettings.addProperty("worktree", false);
                    readCreationProject();
                    // Ignore defaults still arriving for the previous project.
                    catalogVersion++;
                    loadingModels = false;
                    models.load(new JsonObject());
                    rebuildWidgets();
                }));
            }
        } else if (anchor == bodyButton) {
            String selected = AgentModels.text(creationSettings, "body");
            for (String body : bodies) pickerChoices.add(new PickerChoice((body.equals(selected) ? "✓ " : "") + bodyLabel(body), body,
                () -> creationSettings.addProperty("body", body)));
            bodyChoices = List.copyOf(pickerChoices);
        } else if (anchor == permissionsButton) {
            String selected = AgentModels.text(settings(), "permissionMode");
            for (String id : permissionModes()) {
                pickerChoices.add(new PickerChoice((id.equals(selected) ? "✓ " : "") + AgentModels.permissionLabel(id),
                    switch (id) {
                        case "accept-edits" -> "Work in project folders; ask for extra access.";
                        case "auto" -> "Automatically review requests for extra access.";
                        case "full" -> "No sandbox or approval prompts.";
                        default -> "";
                    }, () -> savePermission(id)));
            }
        }
        if (!effort && pickerChoices.isEmpty()) return;
        clearSelection();
        pickerAnchor = anchor;
        pickerWidgets = new ArrayList<>();
        // Model and provider lists fit their longest choice; 20 covers the row and text insets on both sides.
        int labelWidth = pickerChoices.stream().mapToInt(choice -> font.width(choice.label().startsWith("✓ ") ? choice.label() : "✓ " + choice.label())
            + (choice.icon().isBlank() ? 0 : ProviderIcon.SIZE + 4)).max().orElse(0);
        pickerWidth = Math.min(contentWidth, effort ? 196 : anchor == bodyButton ? Math.max(120, labelWidth + 20) : anchor == modelButton || anchor == providerButton
            ? Math.max(labelWidth, font.width("↑ Previous")) + 20 : 280);
        pickerX = Math.clamp(anchor.getX() + anchor.getWidth() - pickerWidth, left, left + contentWidth - pickerWidth);
        pickerY = anchor.getY() + anchor.getHeight() + 3;
        pickerRowHeight = 22;
        if (anchor == permissionsButton) {
            int descriptionLines = pickerChoices.stream().mapToInt(choice -> font.split(Component.literal(choice.description()), pickerWidth - 20).size()).max().orElse(0);
            pickerRowHeight = 24 + descriptionLines * font.lineHeight;
        }
        int availableHeight = Math.max(anchor.getY(), height - pickerY) - 12 - (anchor == bodyButton ? 24 : 0);
        pickerPageSize = pickerChoices.size() * pickerRowHeight <= availableHeight ? Math.max(1, pickerChoices.size())
            : Math.max(1, (availableHeight - 2 * pickerRowHeight) / pickerRowHeight);
        pickerHeight = effort ? 52 : (anchor == bodyButton ? 24 : 0) + Math.min(pickerChoices.size(), pickerPageSize) * pickerRowHeight + 8 + (pickerChoices.size() > pickerPageSize ? 2 * pickerRowHeight : 0);
        if (pickerY + pickerHeight > height - 4) pickerY = Math.max(4, anchor.getY() - pickerHeight - 3);
        if (effort) {
            var choices = models.efforts();
            var slider = new AbstractSliderButton(pickerX + 8, pickerY + 24, pickerWidth - 16, 20,
                    Component.literal("Effort: " + models.effort),
                    (double) choices.indexOf(models.effort) / (choices.size() - 1)) {
                @Override protected void updateMessage() {
                    setMessage(Component.literal("Effort: " + models.effort));
                }
                @Override protected void applyValue() {
                    int index = (int) Math.round(value * (choices.size() - 1));
                    value = (double) index / (choices.size() - 1);
                    executionEdited = true;
                    models.effort = choices.get(index);
                    refreshButtons();
                }
                @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
                    if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                        int index = Math.clamp(choices.indexOf(models.effort) + (key == GLFW.GLFW_KEY_LEFT ? -1 : 1), 0, choices.size() - 1);
                        value = (double) index / (choices.size() - 1);
                        applyValue();
                        updateMessage();
                        return true;
                    }
                    return super.keyPressed(key, scanCode, modifiers);
                }
            };
            pickerWidgets.add(addWidget(slider));
        } else {
            if (anchor == bodyButton) {
                bodySearch = new EditBox(font, pickerX + 6, pickerY + 6, pickerWidth - 12, 20, Component.literal("Search bodies"));
                bodySearch.setHint(Component.literal("Search bodies…"));
                bodySearch.setMaxLength(128);
                bodySearch.setResponder(query -> {
                    String match = query.toLowerCase(java.util.Locale.ROOT).trim();
                    pickerChoices = bodyChoices.stream().filter(choice -> choice.description().toLowerCase(java.util.Locale.ROOT).contains(match)).toList();
                    pickerOffset = 0;
                    populatePicker();
                });
            }
            populatePicker();
        }
        setFocused(pickerWidgets.getFirst());
    }

    private void selectProvider(String provider) {
        if (!draft() || sending || provider.equals(AgentModels.text(state, "providerId"))) return;
        for (String key : List.of("permissionMode","model","reasoningLevel","serviceTier")) creationSettings.remove(key);
        creationSettings.remove("provider");
        creationSettings.addProperty("providerId", provider);
        models.model = ""; models.effort = ""; models.serviceTier = "default";
        models.load(new JsonObject());
        catalogVersion++;
        loadingModels = false;
        requestModels(() -> {});
    }

    private JsonObject selectedProvider() {
        for(var item:AgentModels.array(catalog,"providers")) {
            var info=item.getAsJsonObject();
            if(AgentModels.text(info,"id").equals(AgentModels.text(state,"providerId")))return info;
        }
        return AgentModels.provider(state);
    }

    private List<String> permissionModes() { return AgentModels.permissionModes(selectedProvider()); }

    private void savePermission(String id) {
        if (draft()) creationSettings.addProperty("permissionMode", id);
        else permissionOverride = id;
        refreshButtons();
    }

    private void populatePicker() {
        for (var widget : pickerWidgets) removeWidget(widget);
        pickerWidgets.clear();
        if (bodySearch != null) pickerWidgets.add(addWidget(bodySearch));
        boolean paged = pickerChoices.size() > pickerPageSize;
        if (paged) pickerRow("↑ Previous", "", () -> pagePicker(-1)).active = pickerOffset > 0;
        for (int i = pickerOffset; i < Math.min(pickerChoices.size(), pickerOffset + pickerPageSize); i++) {
            var choice = pickerChoices.get(i);
            pickerRow(choice.label(), choice.description(), choice.icon(), () -> {
                closePicker();
                choice.select().run();
                refreshButtons();
            });
        }
        if (bodySearch != null && pickerChoices.isEmpty()) pickerRow("No matching bodies", "", () -> {}).active = false;
        if (paged) pickerRow("↓ More", "", () -> pagePicker(1)).active = pickerOffset + pickerPageSize < pickerChoices.size();
    }

    private void pagePicker(int direction) {
        pickerOffset = Math.clamp(pickerOffset + direction * pickerPageSize, 0, Math.max(0, pickerChoices.size() - pickerPageSize));
        populatePicker();
        setFocused(pickerWidgets.getFirst());
    }

    private Button pickerRow(String label, String description, Runnable select) { return pickerRow(label, description, "", select); }

    private Button pickerRow(String label, String description, String icon, Runnable select) {
        var option = new Button(pickerX + 4, pickerY + 4 + pickerWidgets.size() * pickerRowHeight + (bodySearch == null ? 0 : 2), pickerWidth - 8, pickerRowHeight - 2,
                Component.literal(label), button -> select.run(), message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF454545);
                int x = getX() + 6, color = active ? 0xFFFFFFFF : 0xFF777777;
                String text = getMessage().getString();
                if (!icon.isBlank()) {
                    // A check column keeps every provider mark aligned: "✓ [icon] Name".
                    if (text.startsWith("✓ ")) { graphics.drawString(font, "✓", x, getY() + 6, color); text = text.substring(2); }
                    x += font.width("✓ ");
                    x += ProviderIcon.render(graphics, icon, x, getY() + 6) + 4;
                }
                graphics.drawString(font, font.plainSubstrByWidth(text, getX() + getWidth() - 6 - x), x, getY() + 6, color);
                if (bodySearch == null && !description.isBlank()) graphics.drawWordWrap(font, Component.literal(description), getX() + 6, getY() + 18, getWidth() - 12, 0xFF999999);
            }
        };
        pickerWidgets.add(addWidget(option));
        return option;
    }

    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (pickerAnchor == null) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(pickerX, pickerY, pickerX + pickerWidth, pickerY + pickerHeight, 0xFF202020);
        graphics.renderOutline(pickerX, pickerY, pickerWidth, pickerHeight, 0xFF777777);
        if (pickerAnchor == effortButton) graphics.drawString(font, "Reasoning effort", pickerX + 8, pickerY + 8, 0xFFBBBBBB);
        for (var widget : pickerWidgets) widget.render(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();
    }

    @Override public void tick() {
        if (!draft()) {
            state = access.snapshot(agentId);
            if (!executionEdited && !sending) {
                var execution = AgentModels.execution(state);
                if (!AgentModels.text(execution,"model").isBlank()) {
                    models.model = AgentModels.text(execution,"model");
                    models.effort = AgentModels.text(execution,"reasoningLevel");
                    models.serviceTier = AgentModels.text(execution,"serviceTier");
                }
            }
        }
        readRequest();
        requestTranscript();
        rebuildTranscript();
        refreshButtons();
    }

    private void readRequest() {
        request = new JsonObject();
        question = new JsonObject();
        for (var item : AgentModels.interactions(state)) {
            var pending = item.getAsJsonObject();
            if (!Set.of("pending", "resolving").contains(AgentModels.text(pending,"status"))) continue;
            if (AgentModels.text(AgentModels.object(pending, "payload"), "kind").equals("approval")) {
                if (request.isEmpty()) request = pending;
            } else if (question.isEmpty()) question = pending;
        }
        requestKey = AgentModels.interactions(state).toString();
    }

    private boolean approval() { return !request.isEmpty(); }

    /** Set when this screen archives its agent; ClientControls clears the selection and reopens the list. */
    public static volatile String archived;

    private void archive() {
        sending = true;
        feedback = "Archiving…";
        refreshButtons();
        access.archiveConversation(agentId, true).whenComplete((done, failure) -> executeUi(() -> {
            sending = false;
            if (failure == null) { archived = agentId; return; }
            feedback = "Could not archive: " + AgentModels.error(failure);
            refreshButtons();
        }));
    }

    private boolean working() {
        return List.of("active", "pending", "starting", "stopping").contains(AgentModels.text(state, "status"));
    }

    private String providerName(String id) {
        for (var item : AgentModels.array(catalog, "providers")) {
            var info = item.getAsJsonObject();
            if (AgentModels.text(info, "id").equals(id)) return AgentModels.text(info, "displayName");
        }
        String name = AgentModels.text(AgentModels.provider(state), "displayName");
        return name.isBlank() ? id : name;
    }

    /** Approval sits left; speed, context, provider, model and effort sit right, each as wide as its widest choice. */
    private void layoutControls() {
        int gap = 4;
        var permissionLabels = new ArrayList<String>(List.of("Permissions"));
        for (String mode : permissionModes()) permissionLabels.add(AgentModels.permissionLabel(mode));
        var modelLabels = new ArrayList<String>(List.of(models.modelLabel().isBlank() ? loadingModels ? "Loading models…" : "Choose model…" : models.modelLabel()));
        for (var model : models.choices()) {
            String name = AgentModels.text(model, "displayName");
            modelLabels.add(name.isBlank() ? AgentModels.text(model, "model") : name);
        }
        var effortLabels = new ArrayList<String>(List.of("Default"));
        effortLabels.addAll(models.efforts());
        int effortWidth = buttonWidth(effortLabels, " ▾");
        int fixed = 20 + gap + 20 + gap + effortWidth + (speedButton.visible ? 20 + gap : 0);
        boolean pending = state.has("permissionsPending") && state.get("permissionsPending").getAsBoolean();
        int permissionWidth = Math.min(buttonWidth(permissionLabels, pending ? " * ▾" : " ▾"), (contentWidth - fixed) / 2);
        int modelWidth = Math.clamp(buttonWidth(modelLabels, " ▾"), 40, Math.max(40, contentWidth - fixed - permissionWidth - 2 * gap));
        permissionsButton.setWidth(permissionWidth);
        int x = left + contentWidth - effortWidth;
        effortButton.setX(x); effortButton.setWidth(effortWidth);
        modelButton.setX(x -= modelWidth + gap); modelButton.setWidth(modelWidth);
        providerButton.setX(x - 20 - gap);
        if (speedButton.visible) speedButton.setX(providerButton.getX() - 2 * (20 + gap));
    }

    private int buttonWidth(List<String> labels, String suffix) {
        return labels.stream().mapToInt(label -> font.width(label + suffix)).max().orElse(0) + 12;
    }

    private void refreshButtons() {
        if (sendButton == null) return;
        boolean increasedSpeed = !models.serviceTier.isBlank() && !models.serviceTier.equals("default");
        String speedHint = increasedSpeed ? "turn off increased speed" : "increase speed (consumes extra usage)";
        speedButton.setMessage(Component.literal(speedHint));
        speedButton.setTooltip(Tooltip.create(Component.literal(speedHint)));
        speedButton.visible = models.hasSpeedChoices();
        layoutControls();
        String label = models.modelLabel();
        modelButton.setMessage(Component.literal(font.plainSubstrByWidth(label.isBlank() ? loadingModels ? "Loading models…" : "Choose model…" : label, modelButton.getWidth() - 20) + " ▾"));
        modelButton.setTooltip(null);
        effortButton.setMessage(Component.literal((models.effort.isBlank() ? "Default" : models.effort) + " ▾"));
        effortButton.setTooltip(null);
        boolean active = availableHere();
        modelButton.active = effortButton.active = active && backendAvailable() && !loadingModels && !sending;
        speedButton.active = active && backendAvailable() && speedButton.visible && !loadingModels && !sending;
        effortButton.active &= !models.available() || models.efforts().size() > 1;
        String permission = AgentModels.text(settings(), "permissionMode");
        permission = AgentModels.permissionLabel(permission);
        boolean permissionsPending = permissionOverride != null;
        permissionsButton.setMessage(Component.literal(font.plainSubstrByWidth(permission.isBlank() ? "BB default" : permission, permissionsButton.getWidth() - (permissionsPending ? 28 : 20)) + (permissionsPending ? " * ▾" : " ▾")));
        permissionsButton.active = active && backendAvailable() && !sending && !loadingModels && !permissionModes().isEmpty();
        permissionsButton.setTooltip(Tooltip.create(Component.literal(permissionsPending
            ? "Selected for your next message. The running turn keeps its approval settings."
            : "BB approval mode for the next message")));
        if (pickerAnchor != null && !pickerAnchor.active) closePicker();
        String provider = providerName(AgentModels.text(state, "providerId"));
        providerButton.setMessage(Component.literal(provider));
        providerButton.active = draft() && !sending;
        providerButton.setTooltip(Tooltip.create(Component.literal(draft() ? provider : provider + " - Fixed for this conversation")));
        composer.active = active && !sending;
        settingsButton.visible = true;
        settingsButton.active = settingsPanel != null || !sending && (draft() || state.has("name"));
        composer.visible = sendButton.visible = !settingsCovering;
        inventoryButton.visible = state.has("minecraftAccess") && state.get("minecraftAccess").getAsBoolean();
        boolean inventoryOpen = dockParent instanceof AgentInventoryScreen inventory && agentId.equals(inventory.agentId());
        inventoryButton.active = !draft() && (inventoryOpen || active && inventoryButton.visible);
        inventoryButton.setMessage(Component.literal(inventoryOpen ? "Inventory ✓" : "Inventory"));
        archiveButton.visible = true;
        archiveButton.active = !draft() && active && backendAvailable() && !working() && !sending;
        archiveButton.setTooltip(Tooltip.create(Component.literal(draft() ? "Available after creating the agent" : working() ? "Stop the agent's work to archive it" : "Archive conversation. Restore it from Mod settings → Archive.")));
        settingsButton.setMessage(Component.literal(settingsPanel == null ? "Settings…" : settingsCovering ? "Back" : "Settings ✓"));
        if (worktreeBox != null) worktreeBox.active = !sending;
        if (draft()) {
            nameField.setEditable(!sending);
            int nameSpace = headerWidth() - Math.min(font.width(creationProjectName), headerWidth() / 3) - 10;
            nameField.setWidth(Math.min(nameSpace, Math.max(96, font.width(nameField.getValue()) + 16)));
            var bodyLabels = new ArrayList<String>(bodies.stream().map(AgentChatScreen::bodyLabel).toList());
            bodyLabels.add(bodyLabel(AgentModels.text(creationSettings, "body")));
            bodyButton.setWidth(Math.min(contentWidth, buttonWidth(bodyLabels, " ▾")));
            bodyButton.setMessage(Component.literal(font.plainSubstrByWidth(bodyLabel(AgentModels.text(creationSettings, "body")), bodyButton.getWidth() - font.width(" ▾") - 12) + " ▾"));
            bodyButton.active = !sending && !loadingModels;
            projectButton.active = !sending && !worldDraft();
            projectButton.visible = !settingsCovering && !worldDraft();
            projectButton.setMessage(Component.literal(font.plainSubstrByWidth(creationProjectName, projectButton.getWidth() - 24) + " ▾"));
            projectButton.setTooltip(Tooltip.create(Component.literal(creationProjectName)));
            minecraftBox.active = !sending && !worldDraft();
            minecraftBox.visible = !settingsCovering && !worldDraft();
            boolean worktreeShown = worktreeBox.visible;
            worktreeBox.visible = !settingsCovering && creationGit;
            // Worktree support arrives after layout; place the options beside each other once it does.
            if (worktreeBox.visible != worktreeShown) rebuildQueue();
        }
        boolean busy = working();
        boolean showStop = !hasDraft() && (busy || actionWorking());
        sendButton.active = active && backendAvailable() && !sending && !stopping && !importingImages
            && (showStop || hasDraft() && !models.model.isBlank());
        if (draft()) sendButton.active &= !loadingModels && models.available() && !AgentModels.text(creationSettings, "name").isBlank()
            && !AgentModels.text(creationSettings, "body").isBlank()
            && !AgentModels.text(creationSettings, "projectId").isBlank();
        sendButton.setMessage(Component.literal(sending || stopping ? "…" : showStop ? "Stop" : "Send"));
        sendButton.setTooltip(draft() && AgentModels.text(creationSettings, "projectId").isBlank()
            ? Tooltip.create(Component.literal("Choose a project before sending")) : null);
    }

    private boolean activeAgent() {
        String lifecycle = AgentModels.text(state, "lifecycle");
        return (lifecycle.isBlank() || lifecycle.equals("active"))
            && !(state.has("conversationArchived") && state.get("conversationArchived").getAsBoolean())
            && !(state.has("bodyRemoved") && state.get("bodyRemoved").getAsBoolean());
    }

    private void send() {
        if (!sendButton.active || !hasDraft()) return;
        String message = draft;
        var attachments = List.copyOf(images());
        String model = models.model, effort = models.effort, tier = models.serviceTier;
        JsonObject captured = pointing == null ? null : pointing.deepCopy();
        sending = true;
        feedback = draft() ? "Creating agent…" : working() ? "Queuing…" : "Sending…";
        closePicker();
        refreshButtons();
        if (draft()) {
            var creation = settings();
            creation.addProperty("name", AgentModels.text(creation, "name").trim());
            creation.remove("initialTask");
            access.spawn(creation).whenComplete((id, failure) -> executeUi(() -> {
                if (failure != null) {
                    sending = false;
                    feedback = "Could not create agent: " + AgentModels.error(failure);
                    refreshButtons();
                    return;
                }
                // Retain this identity even if delivery fails: Send must never spawn twice.
                agentId = id;
                state = access.snapshot(id);
                loadedSequence = -1;
                transcriptKey = "";
                feedback = "Sending…";
                rebuildWidgets();
                if (created != null) created.accept(id);
                deliver(message, model, effort, tier, captured, attachments);
            }));
        } else deliver(message, model, effort, tier, captured, attachments);
    }

    private void deliver(String message, String model, String effort, String tier, JsonObject captured, List<String> attachments) {
        var request = new JsonObject();
        request.addProperty("text", message);
        request.addProperty("model", model);
        request.addProperty("reasoningLevel", effort);
        request.addProperty("serviceTier", tier);
        request.addProperty("permissionMode", AgentModels.text(settings(), "permissionMode"));
        request.addProperty("delivery", "queue");
        if (captured != null) request.add("pointing", captured);
        var images = new com.google.gson.JsonArray();
        attachments.forEach(images::add);
        request.add("images", images);
        access.send(agentId, request).whenComplete((unused, failure) -> executeUi(() -> {
            sending = false;
            if (failure != null) feedback = "Send failed: " + AgentModels.error(failure);
            else {
                if (draft.equals(message)) {
                    draft = "";
                    if (composer != null) composer.setValue("");
                }
                images().removeAll(attachments);
                permissionOverride = null;
                executionEdited = false;
                transcriptKey = "";
                pointing = null;
                feedback = "";
                rebuildTranscript();
                scroll = maxScroll;
            }
            refreshButtons();
        }));
    }

    private void steerQueued(String messageId) {
        if(sending)return;
        sending=true;
        feedback="Steering…";
        refreshButtons();
        finish(access.steerQueued(agentId,messageId),()->sending=false);
    }

    private void interrupt() {
        if (!sendButton.active || !(working() || actionWorking())) return;
        stopping = true;
        feedback = "Stopping…";
        refreshButtons();
        finish(access.interrupt(agentId), () -> stopping = false);
    }

    public void inventoryResult(CompletableFuture<Void> operation) { finish(operation, () -> {}); }

    public boolean hasMinecraftInventory() {
        return !draft() && availableHere() && state.has("minecraftAccess") && state.get("minecraftAccess").getAsBoolean();
    }

    private void finish(CompletableFuture<Void> operation, Runnable complete) {
        operation.whenComplete((unused, failure) -> executeUi(() -> {
            complete.run();
            feedback = failure == null ? "" : AgentModels.error(failure);
            refreshButtons();
        }));
    }

    private void requestTranscript() {
        if (draft() || !backendAvailable() || AgentModels.text(state,"threadId").isBlank()) return;
        long now=System.currentTimeMillis();
        if(transcriptLoading || now<nextTranscriptPoll) return;
        transcriptLoading=true; nextTranscriptPoll=now+750;
        int version=queryVersion;
        var query=new JsonObject();
        query.addProperty("segmentLimit", "40");
        if (!olderCursor.isEmpty()) {
            query.addProperty("beforeAnchorSeq", AgentModels.text(olderCursor,"anchorSeq"));
            query.add("beforeAnchorId", olderCursor.get("anchorId"));
        }
        access.transcript(agentId,query).whenComplete((result,failure)->executeUi(()->{
            transcriptLoading=false;
            if(version!=queryVersion)return;
            if(failure!=null){feedback="History unavailable: "+AgentModels.error(failure);nextTranscriptPoll=System.currentTimeMillis()+2000;return;}
            transcript=result;loadedSequence=number(result,"maxSeq");
            transcriptKey="";rebuildTranscript();
        }));
    }

    private void changeTranscript(Runnable change) {
        change.run();queryVersion++;loadedSequence=-1;nextTranscriptPoll=0;preserveScroll=true;
        requestTranscript();
    }

    private void disclosure(String label,Runnable action) { disclosure(label,action,0); }

    private void disclosure(String label,Runnable action,int inset) {
        disclosure(label,action,inset,false);
    }

    private void disclosure(String label,Runnable action,int inset,boolean running) {
        int line=lines.size();
        int labelWidth=Math.min(font.width(label),contentWidth-24-inset);
        // Keep native keyboard and narration support, with text-only rendering.
        var button=addWidget(new Button(left+8+inset,0,labelWidth,LINE_HEIGHT,
            Component.literal(label),b->action.run(),narration->narration.get()) {
            @Override protected void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
                int color=isHoveredOrFocused()?0xEEEEEE:0x999999;
                String text=font.plainSubstrByWidth(getMessage().getString(),getWidth());
                if(!running || !working()) {
                    graphics.drawString(font,text,getX(),getY(),color);
                    return;
                }
                drawShimmer(graphics,text,getX(),getY(),isHoveredOrFocused());
            }
        });
        if(font.width(label)>button.getWidth())button.setTooltip(Tooltip.create(Component.literal(label)));
        disclosures.add(new Disclosure(button,line));
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
    }

    private void drawShimmer(GuiGraphics graphics,String text,int x,int y,boolean highlighted) {
        // Move a three-character highlight one character at a time.
        int length=text.codePointCount(0,text.length());
        int center=(int)((net.minecraft.Util.getMillis()/100)%(length+4))-2;
        var shimmer=Component.empty();
        int index=0,base=highlighted?190:153;
        for(int offset=0;offset<text.length();) {
            int next=offset+Character.charCount(text.codePointAt(offset));
            String glyph=text.substring(offset,next);
            int distance=Math.abs(index-center);
            int shade=distance==0?245:distance==1?(base+245)/2:base;
            int tint=(shade<<16)|(shade<<8)|shade;
            shimmer.append(Component.literal(glyph).withStyle(style->style.withColor(tint)));
            index++;
            offset=next;
        }
        graphics.drawString(font,shimmer,x,y,0x999999);
    }

    private void paragraph(String text,int color,long replyVersion,int inset) {
        paragraph(text,color,replyVersion,inset,contentWidth-24-inset);
    }

    private void paragraph(String text,int color,long replyVersion,int inset,int wrapWidth) {
        font.getSplitter().splitLines(text,wrapWidth,net.minecraft.network.chat.Style.EMPTY,true,(style,start,end)-> {
            String copy=text.substring(start,end);
            String visible=copy.endsWith("\n") || copy.endsWith(" ")?copy.substring(0,copy.length()-1):copy;
            lines.add(new Line(Component.literal(visible).withStyle(style).getVisualOrderText(),color,end==text.length()?replyVersion:0,inset,
                end==text.length() && !copy.endsWith("\n")?copy+"\n":copy));
        });
    }

    private void detail(JsonObject row) { detail(row,24,contentWidth-48); }

    private void markdown(String text, int color, long replyVersion, int inset, int wrapWidth) {
        appendMarkdown(ChatMarkdown.layout(font,text,wrapWidth,chatImages,maxImageHeight()),color,replyVersion,inset);
    }

    private void appendMarkdown(ChatMarkdown.Layout layout,int color,long replyVersion,int inset) {
        int first = lines.size();
        for (int i = 0; i < layout.rows().size(); i++) {
            var row = layout.rows().get(i);
            lines.add(new Line(row.text(), color, i == layout.rows().size() - 1 ? replyVersion : 0,
                inset + row.inset(), row.copyText(), row));
        }
        for (var panel : layout.panels()) richPanels.add(new RichPanel(panel, first, inset));
        for (var media : layout.media()) richMedia.add(new RichMedia(media, first, inset));
    }

    private void attachments(JsonObject row,int inset,int width) {
        var attachments=AgentModels.object(row,"attachments");
        var sources=new java.util.LinkedHashSet<String>();
        for(String key:List.of("imageUrls","localImagePaths"))for(var value:AgentModels.array(attachments,key))
            if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())sources.add(value.getAsString());
        for(String source:sources) appendMarkdown(ChatMarkdown.image(font,source,"Attached image",width,chatImages,maxImageHeight()),0xEEEEEE,0,inset);
        for(var value:AgentModels.array(attachments,"localFilePaths")) {
            String path=value.getAsString();
            var component=Component.literal("↗ "+path).withStyle(style->style.withColor(0x96C9DB).withUnderlined(true)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL,path)));
            for(var line:font.split(component,width))lines.add(new Line(line,0x96C9DB,0,inset));
        }
        if(sources.isEmpty() && attachmentCount(row)>0 && AgentModels.array(attachments,"localFilePaths").isEmpty())
            paragraph(attachmentCount(row)+" attachment(s) unavailable",0xAAAAAA,0,inset,width);
    }

    private void detail(JsonObject row,int inset,int wrapWidth) {
        if(!AgentModels.text(row,"id").equals(expandedRow))return;
        String text = workDetail(row);
        int offset = Math.clamp(textOffset, 0, Math.max(0, text.length() - 1));
        if(offset>0)disclosure("↑ Previous output",()->changeTranscript(()->textOffset=Math.max(0,offset-8000)),inset);
        paragraph(text.substring(offset,Math.min(text.length(),offset+8000)),0xBDBDBD,0,inset,wrapWidth);
        if(offset+8000<text.length())disclosure("↓ More output",()->changeTranscript(()->textOffset=offset+8000),inset);
        for (var child : AgentModels.array(row, "childRows")) transcriptRow(child.getAsJsonObject(), true);
    }

    private static String workDetail(JsonObject row) {
        var parts = new ArrayList<String>();
        for (String key : new String[]{"command", "cwd", "path", "query", "url", "prompt", "description", "text", "detail", "output", "stdout", "stderr", "error", "summary", "explanation"}) {
            String text = AgentModels.text(row, key);
            if (!text.isBlank()) parts.add(text);
        }
        String presentation = AgentModels.text(AgentModels.object(row, "presentation"), "detail");
        if (!presentation.isBlank()) parts.add(presentation);
        for (String key : new String[]{"toolArgs", "change", "steps", "payload", "answers"})
            if (row.has(key) && !row.get(key).isJsonNull()) parts.add(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(row.get(key)));
        return String.join("\n\n", parts);
    }

    private static int attachmentCount(JsonObject row) {
        var attachments = AgentModels.object(row, "attachments");
        return (int)(number(attachments,"webImages") + number(attachments,"localImages") + number(attachments,"localFiles"));
    }

    private void userMessage(JsonObject row) {
        String text=AgentModels.text(row,"text");
        String error=AgentModels.text(AgentModels.object(row,"turnRequest"),"status").equals("rejected")?"Not sent: "+AgentModels.text(row,"detail"):"";
        int attachments=attachmentCount(row);
        int maxWidth=Math.max(40,(contentWidth-40)*3/4);
        int textWidth=Math.min(maxWidth,100);
        for(var line:font.split(Component.literal(text),maxWidth))textWidth=Math.max(textWidth,font.width(line));
        if(!error.isEmpty() || attachments>0)textWidth=maxWidth;
        int bubbleWidth=textWidth+16,inset=contentWidth-24-bubbleWidth;
        if(!text.isBlank() || !error.isEmpty() || attachments>0) {
            int firstLine=lines.size();
            lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
            markdown(text,0xEEEEEE,0,inset+8,textWidth);
            if(!error.isEmpty())paragraph(error,0xFFAAAA,0,inset+8,textWidth);
            if(attachments>0)attachments(row,inset+8,textWidth);
            lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
            messageBubbles.add(new MessageBubble(firstLine,lines.size(),inset,bubbleWidth));
        }
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
    }

    private String lastReplyId() {
        if (!olderCursor.isEmpty()) return "";
        String id="";
        for(var value:AgentModels.array(transcript,"rows")) {
            var row=value.getAsJsonObject();
            if(AgentModels.text(row,"role").equals("assistant"))id=AgentModels.text(row,"id");
        }
        return id;
    }

    private void transcriptRow(JsonObject row,boolean nested) {
        var presentation=AgentModels.object(row,"presentation");
        if(presentation.has("suppress")&&presentation.get("suppress").getAsBoolean())return;
        String id=AgentModels.text(row,"id"),role=AgentModels.text(row,"role");
        if(role.equals("user")) { userMessage(row); return; }
        boolean message=role.equals("assistant");
        if(message) {
            // Collapsed activity and off-page replies are never marked as read.
            long reply=role.equals("assistant") && !nested && id.equals(lastReplyId())
                && !(row.has("truncated")&&row.get("truncated").getAsBoolean())?number(state,"latestAttentionAt"):0;
            markdown(AgentModels.text(row,"text"),0xEEEEEE,reply,nested?12:0,contentWidth-24-(nested?12:0));
            if(AgentModels.text(AgentModels.object(row,"turnRequest"),"status").equals("rejected"))paragraph("Not sent: "+AgentModels.text(row,"detail"),0xFFAAAA,0,0);
            int attachments=attachmentCount(row);
            if(attachments>0)attachments(row,nested?12:0,contentWidth-24-(nested?12:0));
        } else {
            String label=AgentModels.text(AgentModels.object(row,"presentation"),"title");
            if(label.isBlank())label=AgentModels.text(AgentModels.object(presentation,"label"),AgentModels.text(row,"status").equals("pending")?"pending":"completed");
            if(label.isBlank())label=AgentModels.text(row,"title");
            if(label.isBlank())label=AgentModels.text(row,"command");
            if(label.isBlank())label=AgentModels.text(row,"toolName");
            if(label.isBlank())label=AgentModels.text(row,"workKind");
            if(label.isBlank())label=AgentModels.text(row,"kind");
            disclosure((id.equals(expandedRow)?"▾ ":"▸ ")+label+" - "+AgentModels.text(row,"status"),()->changeTranscript(()->{expandedRow=id.equals(expandedRow)?"":id;textOffset=0;}),nested?12:0);
        }
        if(!message)detail(row);
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
    }

    private void rebuildTranscript() {
        if(font==null)return;
        // Existing screens do not run field initializers when classes are hot reloaded.
        if (questionDrafts == null) questionDrafts = new java.util.HashMap<>();
        if (respondingRequests == null) respondingRequests = new java.util.HashSet<>();
        if (requestErrors == null) requestErrors = new java.util.HashMap<>();
        if (questionFields == null) questionFields = new java.util.HashMap<>();
        if (focusedQuestion == null) focusedQuestion = "";
        if(messageBubbles==null) {
            messageBubbles=new ArrayList<>();
            transcriptKey="";
        }
        if(queueControls==null) { queueControls=new ArrayList<>(); transcriptKey=""; }
        String key=loadedSequence+":"+queryVersion+":"+contentWidth+":"+maxImageHeight()+":"+chatImages.layoutVersion()+":"+working()+":"+AgentModels.queuedMessages(state)+":"+AgentModels.text(state,"canSteer")+":"+images()+":"+pointing+":"+composerFeedback()+":"+requestKey+":"+respondingRequests+":"+requestErrors;
        if(key.equals(transcriptKey))return;
        if(selectingText)return;
        var previousLines=new ArrayList<>(lines);
        boolean follow=!hasSelection() && !preserveScroll && scroll>=maxScroll-4;
        preserveScroll=false;transcriptKey=key;
        for(var entry:disclosures)removeWidget(entry.button());
        var previousPanels = new ArrayList<>(richPanels);
        disclosures.clear();lines.clear();messageBubbles.clear();richPanels.clear();richMedia.clear();thinkingLine=-1;
        rebuildQueue();
        boolean hasRunningGroup=false;
        var page = AgentModels.object(transcript, "timelinePage");
        if (page.has("hasOlderRows") && page.get("hasOlderRows").getAsBoolean())
            disclosure("↑ Older messages",()->changeTranscript(()->{
                olderCursor = AgentModels.object(page,"olderCursor").deepCopy(); before="older";
                expandedGroup=""; expandedRow=""; scroll=0;
            }));
        if(!olderCursor.isEmpty())disclosure("↓ Latest messages",()->changeTranscript(()->{
            olderCursor=new JsonObject();before="";expandedGroup="";expandedRow="";scroll=Integer.MAX_VALUE;
        }));
        for(var value:AgentModels.array(transcript,"rows")) {
            var row=value.getAsJsonObject();String id=AgentModels.text(row,"id");
            if(!AgentModels.text(row,"kind").equals("turn")){transcriptRow(row,false);continue;}
            boolean expanded=id.equals(expandedGroup),running=AgentModels.text(row,"status").equals("pending");
            hasRunningGroup|=running;
            long seconds=Math.max(0,(number(row,"completedAt")-number(row,"startedAt"))/1000);
            String duration=seconds<60?seconds+"s":seconds/60+"m "+seconds%60+"s";
            String label=(expanded?"▾ ":"▸ ")+(running?"Working":"Worked for "+duration);
            disclosure(label,()->{
                expandedGroup=expanded?"":id;expandedRow="";textOffset=0;turnDetails=new JsonObject();
                transcriptKey="";rebuildTranscript();
                if(!expanded && AgentModels.array(row,"children").isEmpty()) requestTurnDetails(row, "");
            },0,running);
            if(expanded) {
                for(var member:AgentModels.array(row,"children"))transcriptRow(member.getAsJsonObject(),true);
                for(var member:AgentModels.array(turnDetails,"rows"))transcriptRow(member.getAsJsonObject(),true);
                String cursor=AgentModels.text(turnDetails,"olderCursor");
                if(!cursor.isBlank())disclosure("↑ Earlier activities",()->requestTurnDetails(row,cursor));
                if(detailsLoading)paragraph("Loading activities…",0xBBBBBB,0,12);
            }
        }
        var thinking=AgentModels.object(transcript,"activeThinking");
        if(!thinking.isEmpty() && olderCursor.isEmpty()) {
            String id=AgentModels.text(thinking,"id");
            disclosure((id.equals(expandedRow)?"▾ ":"▸ ")+"Thinking…",()->{
                expandedRow=id.equals(expandedRow)?"":id;transcriptKey="";rebuildTranscript();
            },0,true);
            if(id.equals(expandedRow))paragraph(AgentModels.text(thinking,"text"),0xBBBBBB,0,12);
        } else if(working() && olderCursor.isEmpty() && !hasRunningGroup) {
            thinkingLine=lines.size();
            lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
        }
        inlineRequests();
        for (int i = 0; i < Math.min(previousPanels.size(), richPanels.size()); i++) {
            var old = previousPanels.get(i).panel(); var current = richPanels.get(i).panel();
            if (old.source.equals(current.source)) current.scroll = Math.min(old.scroll, current.maxScroll());
        }
        maxScroll=Math.max(0,lines.size()*LINE_HEIGHT-(transcriptBottom-transcriptTop-12));
        scroll=follow?maxScroll:Math.clamp(scroll,0,maxScroll);
        if(hasSelection()) {
            var start=selectionStart();var end=selectionFinish();
            if(end.line()>=lines.size() || end.line()>=previousLines.size())clearSelection();
            else for(int i=start.line();i<=end.line();i++) {
                if(!lineText(lines.get(i)).equals(lineText(previousLines.get(i)))) {clearSelection();break;}
            }
        }
        positionDisclosures();
    }

    private void requestTurnDetails(JsonObject row, String cursor) {
        if (detailsLoading) return;
        String id = AgentModels.text(row,"id");
        var query=new JsonObject();
        for (String key : new String[]{"turnId","sourceSeqStart","sourceSeqEnd"}) query.addProperty(key,AgentModels.text(row,key));
        if(!cursor.isBlank())query.addProperty("beforeCursor",cursor);
        detailsLoading=true;
        access.timelineTurnSummaryDetails(agentId,query).whenComplete((result,failure)->executeUi(()->{
            detailsLoading=false;
            if(!id.equals(expandedGroup))return;
            if(failure!=null)feedback=AgentModels.error(failure);
            else turnDetails=result;
            transcriptKey="";rebuildTranscript();
        }));
    }

    private void inlineRequests() {
        for (var item : AgentModels.interactions(state)) {
            var pending = item.getAsJsonObject();
            String status=AgentModels.text(pending,"status");
            if(!status.equals("pending") && !status.equals("resolving"))continue;
            String id = AgentModels.text(pending, "id");
            var payload=AgentModels.object(pending,"payload");
            boolean approval=AgentModels.text(payload,"kind").equals("approval");
            boolean busy=respondingRequests.contains(id)||status.equals("resolving");
            lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
            paragraph(approval?"Approval requested":"Question",0xE3CAA0,0,0);
            if(approval) {
                paragraph(AgentInteractions.approvalDetails(pending),0xEEEEEE,0,0);
                for(var value:AgentModels.array(payload,"availableDecisions")) {
                    String decision=value.getAsString();
                    var button=Button.builder(Component.literal(AgentInteractions.decisionLabel(decision)),unused->respondInline(id,AgentInteractions.approvalResolution(pending,decision)))
                        .bounds(left+8,0,contentWidth-24,20).build();
                    button.active=!busy&&activeAgent()&&backendAvailable();inlineWidget(button,24);
                }
            } else if(AgentModels.text(payload,"kind").equals("user_question")) {
                for(var value:AgentModels.array(payload,"questions"))paragraph(AgentModels.text(value.getAsJsonObject(),"prompt"),0xEEEEEE,0,0);
                var button=Button.builder(Component.literal("Answer questions…"),unused->minecraft.setScreen(new AgentQuestionScreen(access,minecraft.screen,agentId,pending)))
                    .bounds(left+8,0,contentWidth-24,20).build();
                button.active=!busy&&activeAgent()&&backendAvailable();inlineWidget(button,24);
            } else {
                paragraph(AgentModels.text(payload,"title"),0xEEEEEE,0,0);
                paragraph("Open this interaction in BB.",0xBBBBBB,0,0);
            }
            if(busy)paragraph("Sending…",0xBBBBBB,0,0);
            String error=requestErrors.getOrDefault(id,"");
            if(!error.isBlank())paragraph(error,0xFFAAAA,0,0);
        }
    }

    private void inlineWidget(AbstractWidget widget, int height) {
        disclosures.add(new Disclosure(addWidget(widget), lines.size()));
        for (int i = 0; i < height / LINE_HEIGHT; i++)
            lines.add(new Line(Component.empty().getVisualOrderText(), 0, 0));
    }

    private void respondInline(String id, JsonObject resolution) {
        if (resolution.isEmpty() || !activeAgent() || !respondingRequests.add(id)) return;
        requestErrors.remove(id);
        // Disable every choice immediately, before the next render can rebuild the card.
        rebuildTranscript();
        CompletableFuture<Void> operation;
        try { operation = access.respond(agentId, id, resolution); }
        catch (RuntimeException failure) { inlineResponseCompleted(id, failure); return; }
        operation.whenComplete((unused, failure) -> executeUi(() -> inlineResponseCompleted(id, failure)));
    }

    private void inlineResponseCompleted(String id, Throwable failure) {
        respondingRequests.remove(id);
        if (failure == null) questionDrafts.remove(id);
        else requestErrors.put(id, AgentModels.error(failure));
        state = access.snapshot(agentId);
        readRequest();
        rebuildTranscript();
    }

    private int creationOptionsHeight() {
        if (!draft() || minecraftBox == null) return 0;
        int x = 0, rows = 0;
        for (var widget : List.of(projectButton, worktreeBox, minecraftBox)) {
            if (!widget.visible) continue;
            if (rows == 0 || x + widget.getWidth() > contentWidth) { rows++; x = 0; }
            x += widget.getWidth() + 12;
        }
        return rows * 24;
    }

    private void rebuildQueue() {
        for(var button:queueControls)removeWidget(button);
        queueControls.clear();
        var queued=AgentModels.queuedMessages(state);
        queueRows=Math.min(queued.size(),Math.max(1,Math.min(4,(height-240)/22)));
        queueOffset=Math.clamp(queueOffset,0,Math.max(0,queued.size()-queueRows));
        int attachmentCount = images().size() + (pointing == null ? 0 : 1);
        int attachmentHeight = attachmentCount == 0 ? 0 : 22;
        int feedbackHeight = composerFeedback().isBlank() ? 0 : 18;
        int optionsHeight = creationOptionsHeight();
        int baseBottom = composerBaseline() - 9
            - (queueRows == 0 ? 0 : queueRows * 22 + 4) - attachmentHeight - feedbackHeight - optionsHeight;
        int maxInputHeight = Math.min(62, Math.max(20, baseBottom - transcriptTop - 36 + 20));
        composer.setHeight(Math.clamp(composer.getInnerHeight() + 8, 20, maxInputHeight));
        int growth = composer.getHeight() - 20;
        composer.setY(composerBaseline() - growth);
        transcriptBottom = baseBottom - growth;
        if (draft()) {
            int optionsY = composer.getY() - optionsHeight;
            int x = left;
            for (var widget : List.of(projectButton, worktreeBox, minecraftBox)) {
                if (!widget.visible) continue;
                if (x > left && x + widget.getWidth() > left + contentWidth) { x = left; optionsY += 24; }
                widget.setPosition(x, optionsY);
                x += widget.getWidth() + 12;
            }
        }
        int chipX = left;
        int chipWidth = attachmentCount == 0 ? 0 : Math.min(96, contentWidth / attachmentCount);
        if (pointing != null) {
            attachmentControl("Context", pointingLabel(),
                chipX, composer.getY() - 26 - optionsHeight, chipWidth - 4, 0x5589BFA3, () -> {
                    pointing = null; rebuildTranscript(); refreshButtons();
                });
            chipX += chipWidth;
        }
        for(int i=0;i<images().size();i++) {
            String path=images().get(i);
            attachmentControl("Image " + (i+1), "",
                chipX, composer.getY() - 26 - optionsHeight, chipWidth - 4, 0x556D98D4, () -> {
                    images().remove(path); transcriptKey=""; rebuildTranscript(); refreshButtons();
                });
            chipX += chipWidth;
        }
        for(int row=0;row<queueRows;row++) {
            String id=AgentModels.text(queued.get(queueOffset+row).getAsJsonObject(),"id");
            int y=transcriptBottom+4+row*22;
            if(state.has("canSteer") && state.get("canSteer").getAsBoolean())
                queueControl("Steer",left+contentWidth-80,y,44,()->steerQueued(id));
            queueControl("×",left+contentWidth-28,y,20,()-> {
                if(sending)return;
                sending=true;
                finish(access.cancelQueued(agentId,id),()->sending=false);
            });
        }
    }

    private String composerFeedback() {
        return feedback.isBlank() ? AgentModels.text(state, "error") : feedback;
    }

    private void attachmentControl(String label, String tooltip, int x, int y, int width, int tint, Runnable action) {
        var button = addWidget(new Button(x, y, width, 20, Component.literal(label + " ×"), b -> action.run(), n -> n.get()) {
            private boolean overRemove(double mouseX, double mouseY) {
                return mouseX >= getX()+getWidth()-18 && mouseX < getX()+getWidth()
                    && mouseY >= getY() && mouseY < getY()+getHeight();
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return overRemove(mouseX, mouseY) && super.mouseClicked(mouseX, mouseY, button);
            }

            @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                graphics.fill(getX(), getY(), getX()+getWidth(), getY()+getHeight(), tint);
                if (active && (overRemove(mouseX, mouseY) || isFocused()))
                    graphics.fill(getX()+getWidth()-18, getY(), getX()+getWidth(), getY()+getHeight(), 0x22FFFFFF);
                String text = font.plainSubstrByWidth(label, Math.max(0, getWidth()-24));
                graphics.drawString(font, text, getX()+5, getY()+6, active ? 0xFFE5EDF5 : 0xFF999999);
                graphics.drawString(font, "×", getX()+getWidth()-11, getY()+6, 0xFFCCD7E0);
            }
        });
        if (!tooltip.isBlank()) button.setTooltip(Tooltip.create(Component.literal(tooltip)));
        queueControls.add(button);
    }

    private void queueControl(String label,int x,int y,int width,Runnable action) {
        var button=addWidget(new Button(x,y,width,20,Component.literal(label),b->action.run(),n->n.get()) {
            @Override protected void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
                graphics.drawString(font,getMessage(),getX()+4,getY()+6,isHoveredOrFocused()?0xEEEEEE:0xAAAAAA);
            }
        });
        if(label.equals("Steer"))button.setTooltip(Tooltip.create(Component.literal("Send into the current turn using its model settings")));
        queueControls.add(button);
    }

    private void renderQueue(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        var queued=AgentModels.queuedMessages(state);
        for(int row=0;row<queueRows && queueOffset+row<queued.size();row++) {
            int y=transcriptBottom+4+row*22;
            String text=AgentInteractions.queuedText(queued.get(queueOffset+row).getAsJsonObject());

            String preview=text.replace('\n',' ').replace('\r',' ');
            int available=contentWidth-104;
            if(font.width(preview)>available)preview=font.plainSubstrByWidth(preview,available-font.width("…"))+"…";
            graphics.fill(left+4,y,left+contentWidth-4,y+21,0xF0272727);
            graphics.drawString(font,preview,left+12,y+6,0xCCCCCC);
            if(!preview.equals(text) && mouseX>=left+4 && mouseX<left+contentWidth-84 && mouseY>=y && mouseY<y+21)
                renderTooltip(graphics, font.split(Component.literal(text), contentWidth - 24), mouseX, mouseY);
        }
        for(var button:queueControls) {
            button.active=!sending && activeAgent() && backendAvailable();
            button.render(graphics,mouseX,mouseY,partialTick);
        }
    }

    private void positionDisclosures() {
        for(var entry:disclosures) {
            int y=transcriptTop+6+entry.line()*LINE_HEIGHT-scroll;
            entry.button().setY(y);
            entry.button().visible=!settingsCovering&&y>=transcriptTop+4&&y+entry.button().getHeight()<=transcriptBottom-4;
        }
    }

    private void renderTooltip(GuiGraphics graphics, List<FormattedCharSequence> text, int mouseX, int mouseY) {
        if (!docked()) {
            graphics.renderTooltip(font, text, mouseX, mouseY);
            return;
        }
        graphics.renderTooltip(font, text, (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) ->
            new org.joml.Vector2i(Math.max(4, Math.min(x + 10, width - tooltipWidth - 4)),
                Math.max(4, Math.min(y + 10, height - tooltipHeight - 4))), mouseX, mouseY);
    }

    private record WidgetTooltip(AbstractWidget widget, Tooltip tooltip) {}

    private String linkAt(double mouseX, double mouseY) {
        if(settingsCovering || mouseY<transcriptTop+4 || mouseY>=transcriptBottom-4
            || mouseX<left+4 || mouseX>=left+contentWidth-8) return null;
        int index=(int)Math.floor((mouseY-transcriptTop-6+scroll)/LINE_HEIGHT);
        if(index<0 || index>=lines.size()) return null;
        var line=lines.get(index);
        if(line.rich()!=null && line.rich().panel()!=null && (mouseX<left+8+line.inset()
            || mouseX>=left+8+line.inset()+line.rich().panel().width))return null;
        int x=(int)mouseX-lineX(line);
        if(x<0) return null;
        var style=line.rich()==null?font.getSplitter().componentStyleAtWidth(line.text(),x):line.rich().styleAt(font,x);
        var click=style==null?null:style.getClickEvent();
        return click==null?null:click.getValue();
    }

    private void openLink(String target) {
        try {
            if(target.matches("(?i)^https?://.*")) {
                var uri=net.minecraft.Util.parseAndValidateUntrustedUri(target);
                if(uri.getHost()==null)throw new java.net.URISyntaxException(target,"Missing host");
                if(!minecraft.options.chatLinks().get()){feedback="Web links are disabled in Minecraft chat settings.";return;}
                Screen parent=returnScreen();
                if(minecraft.options.chatLinksPrompt().get())minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(accepted->{
                    if(accepted)net.minecraft.Util.getPlatform().openUri(uri);
                    minecraft.setScreen(parent);
                },target,false));
                else net.minecraft.Util.getPlatform().openUri(uri);
            } else if(!target.matches("(?i)^[a-z][\\w+.-]*:.*") || target.startsWith("@thread:") || target.matches(".*:\\d+(?::\\d+)?$")) {
                feedback="Opening in BB…";
                (developmentTranscript && developmentLinks!=null ? developmentLinks.apply(target) : access.openChatLink(agentId,target)).whenComplete((ignored,failure)->executeUi(()->{
                    feedback=failure==null?"Opened in BB":"Could not open link: "+AgentModels.error(failure);
                }));
            } else feedback="Unsupported link: "+target;
        } catch(java.net.URISyntaxException failure) { feedback="Invalid link"; }
    }

    private void renderRichPanels(GuiGraphics graphics,int mouseX,int mouseY) {
        for(var entry:richPanels) {
            var panel=entry.panel();
            int x=left+8+entry.inset()+panel.inset;
            int y=transcriptTop+6+(entry.lineOffset()+panel.first)*LINE_HEIGHT-scroll;
            int bottom=y+(panel.end-panel.first)*LINE_HEIGHT;
            if(bottom<transcriptTop || y>transcriptBottom) continue;
            graphics.fill(x,y-2,x+panel.width,bottom-2,0xEF171C21);
            graphics.fill(x,y-2,x+panel.width,y+LINE_HEIGHT-2,0xFF2A333A);
            graphics.drawString(font,font.plainSubstrByWidth(panel.label,Math.max(8,panel.width-50)),x+6,y,0xAEBEC7);
            graphics.drawString(font,"Copy",x+panel.width-30,y,0xC9D8DF);
            graphics.enableScissor(x,transcriptTop+4,x+panel.width,transcriptBottom-4);
            for(int column:panel.columns) graphics.fill(x+column-panel.scroll,y+LINE_HEIGHT-2,
                x+column-panel.scroll+1,bottom-LINE_HEIGHT,0xFF36424A);
            if(panel.maxScroll()>0) {
                int track=panel.width-12;
                int thumb=Math.max(12,track*panel.width/Math.max(panel.width,panel.contentWidth));
                int start=x+6+(track-thumb)*panel.scroll/panel.maxScroll();
                graphics.fill(x+6,bottom-6,x+panel.width-6,bottom-4,0xFF35414A);
                graphics.fill(start,bottom-6,start+thumb,bottom-4,0xFF93A8B4);
            }
            graphics.disableScissor();
        }
    }

    private RichMedia mediaAt(double mouseX,double mouseY) {
        if(mouseY<transcriptTop+4 || mouseY>=transcriptBottom-4)return null;
        for(var entry:richMedia) {
            var media=entry.media();int x=left+8+entry.inset()+media.inset();
            int y=transcriptTop+6+(entry.lineOffset()+media.first())*LINE_HEIGHT-scroll;
            if(mouseX>=x && mouseX<x+media.width() && mouseY>=y && mouseY<y+media.height())return entry;
        }
        return null;
    }

    private int maxImageHeight() { return Math.max(120,transcriptBottom-transcriptTop-24); }

    private void renderRichMedia(GuiGraphics graphics) {
        for(var entry:richMedia) {
            var media=entry.media();int x=left+8+entry.inset()+media.inset();
            int y=transcriptTop+6+(entry.lineOffset()+media.first())*LINE_HEIGHT-scroll;
            int height=media.height();
            if(y+height<transcriptTop || y>transcriptBottom)continue;
            var image=chatImages.get(media.kind(),media.source());
            if(image.texture!=null)ChatImages.draw(graphics,image,x,y,media.width(),height);
            else {
                String status=image.loading?"Loading "+(media.kind().equals("mermaid")?"diagram":"image")+"…":"Click to retry · "+image.error;
                var wrapped=font.split(Component.literal(status),Math.max(24,media.width()));
                for(int i=0;i<Math.min(wrapped.size(),height/LINE_HEIGHT);i++)
                    graphics.drawString(font,wrapped.get(i),x,y+i*LINE_HEIGHT,0xBDB5A6);
            }
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!docked()) {
            renderChat(graphics, mouseX, mouseY, partialTick);
            return;
        }
        // Native widgets normally queue tooltips on Minecraft's active inventory screen.
        // Keep them in this panel's coordinates instead.
        var tooltips = new ArrayList<WidgetTooltip>();
        Tooltip hovered = null;
        for (var child : children()) {
            if (!(child instanceof AbstractWidget widget) || widget.getTooltip() == null) continue;
            var tooltip = widget.getTooltip();
            tooltips.add(new WidgetTooltip(widget, tooltip));
            widget.setTooltip(null);
            boolean pickerVisible = pickerAnchor == null || pickerWidgets.contains(widget);
            if (widget.visible && pickerVisible && (widget.isMouseOver(mouseX, mouseY)
                || widget.isFocused() && minecraft.getLastInputType().isKeyboard())) hovered = tooltip;
        }
        try {
            renderChat(graphics, mouseX, mouseY, partialTick);
        } finally {
            for (var item : tooltips) item.widget().setTooltip(item.tooltip());
        }
        if (hovered != null) setTooltipForNextRenderPass(hovered.toCharSequence(minecraft),
            (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> new org.joml.Vector2i(
                Math.max(4, Math.min(x + 10, width - tooltipWidth - 4)),
                Math.max(4, Math.min(y + 10, height - tooltipHeight - 4))), true);
    }

    private void renderChat(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if(messageBubbles==null || queueControls==null)rebuildTranscript();
        positionDisclosures();
        super.render(graphics, mouseX, mouseY, partialTick);
        int growth = composer.getHeight() - 20;
        String name = AgentModels.text(state, "name");
        String project = draft() ? creationProjectName : AgentModels.text(state, "projectName");
        int projectX;
        if (draft()) projectX = nameField.getX() + nameField.getWidth() + 10;
        else {
            int nameSpace = headerWidth() - Math.min(font.width(project), headerWidth() / 3) - 10;
            String title = font.plainSubstrByWidth(name.isBlank() ? "Agent" : name, nameSpace);
            graphics.drawString(font, title, left, 12, 0xFFFFFF);
            projectX = left + font.width(title) + 10;
        }
        graphics.drawString(font, font.plainSubstrByWidth(project, Math.max(0, left + headerWidth() - projectX)), projectX, 12, 0x8C8C8C);
        if (settingsCovering) {
            renderContext(graphics, mouseX, mouseY);
            renderPicker(graphics, mouseX, mouseY, partialTick);
            return;
        }
        if (!docked()) graphics.fill(left, transcriptTop, left + contentWidth, transcriptBottom, 0xB0101010);
        graphics.enableScissor(left + 4, transcriptTop + 4, left + contentWidth - 8, transcriptBottom - 4);
        for(var bubble:messageBubbles) {
            int top=transcriptTop+6+bubble.firstLine()*LINE_HEIGHT-scroll+4;
            int bottom=transcriptTop+6+bubble.endLine()*LINE_HEIGHT-scroll-4;
            if(bottom>transcriptTop && top<transcriptBottom) {
                int x=left+8+bubble.inset();
                graphics.fill(x,top,x+bubble.width(),bottom,0xE02C4255);
            }
        }
        long seenReply = 0;
        renderRichPanels(graphics, mouseX, mouseY);
        renderRichMedia(graphics);
        if (lines.isEmpty()) {
            graphics.drawWordWrap(font, Component.literal(transcriptLoading ? "Loading conversation…" : draft() ? "" : "Your agent is ready. Ask about its project, or give it something to build."), left + 8, transcriptTop + 10, contentWidth - 24, 0xAAAAAA);
        } else {
            int first=Math.max(0,scroll/LINE_HEIGHT-1);
            int last=Math.min(lines.size(),(scroll+transcriptBottom-transcriptTop)/LINE_HEIGHT+1);
            for (int i = first; i < last; i++) {
                int y = transcriptTop + 6 + i * LINE_HEIGHT - scroll;
                if (y >= transcriptTop - 11 && y < transcriptBottom) {
                    var line = lines.get(i);
                    var panel = line.rich() == null ? null : line.rich().panel();
                    if (panel != null) graphics.enableScissor(left+8+line.inset(),transcriptTop+4,
                        left+8+line.inset()+panel.width,transcriptBottom-4);
                    drawSelection(graphics,i,y);
                    if(i==thinkingLine)drawShimmer(graphics,"Thinking…",left+8,y,false);
                    else if(line.rich()!=null) line.rich().draw(graphics,font,lineX(line),y,line.color());
                    else graphics.drawString(font, line.text(), lineX(line), y, line.color());
                    if (panel != null) graphics.disableScissor();
                }
                if (y >= transcriptTop + 4 && y + font.lineHeight <= transcriptBottom - 4) seenReply = Math.max(seenReply, lines.get(i).replyVersion());
            }
        }
        for(var entry:disclosures)if(entry.button().visible)entry.button().render(graphics,mouseX,mouseY,partialTick);
        graphics.disableScissor();
        String hoverLink = linkAt(mouseX, mouseY);
        if (hoverLink != null && !selectingText && pickerAnchor == null)
            renderTooltip(graphics, font.split(Component.literal(hoverLink), Math.max(80, contentWidth-24)), mouseX, mouseY);
        renderQueue(graphics,mouseX,mouseY,partialTick);
        if (maxScroll > 0) {
            int track = transcriptBottom - transcriptTop - 8;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int y = transcriptTop + 4 + (track - thumb) * scroll / maxScroll;
            graphics.fill(left + contentWidth - 5, transcriptTop + 4, left + contentWidth - 3, transcriptBottom - 4, 0xFF444444);
            graphics.fill(left + contentWidth - 5, y, left + contentWidth - 3, y + thumb, 0xFFAAAAAA);
        }
        String message = composerFeedback();
        if (!message.isBlank()) {
            int messageY = composer.getY() - creationOptionsHeight() - (pointing != null || !images().isEmpty() ? 22 : 0) - 16;
            graphics.drawString(font, font.plainSubstrByWidth(message, contentWidth), left, messageY, 0xBBD5BB);
            if (font.width(message) > contentWidth && mouseY >= messageY - 2 && mouseY < messageY + 12 && mouseX >= left && mouseX < left + contentWidth)
                renderTooltip(graphics, font.split(Component.literal(message), contentWidth - 24), mouseX, mouseY);
        }
        renderContext(graphics, mouseX, mouseY);
        renderPicker(graphics, mouseX, mouseY, partialTick);
        if (minecraft.screen == returnScreen() && minecraft.isWindowActive()) {
            if (seenReply > markedReply) { markedReply=seenReply;access.markRead(agentId, seenReply); }
        }
    }

    /** A button-sized square left of the provider fills from the bottom as the context window is used. */
    private void renderContext(GuiGraphics graphics, int mouseX, int mouseY) {
        var usage = AgentModels.object(transcript, "contextWindowUsage");
        long used = number(usage, "usedTokens"), size = number(usage, "modelContextWindow");
        int x = providerButton.getX() - 24, y = providerButton.getY();
        double fraction = size > 0 ? Math.clamp((double) used / size, 0, 1) : 0;
        int color = fraction >= 0.9 ? 0xFFFF7A5C : fraction >= 0.75 ? 0xFFFFD45A : 0xFFD8D8D8;
        graphics.fill(x, y, x + 20, y + 20, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xFF8A8A8A);
        graphics.fill(x + 2, y + 2, x + 18, y + 18, 0xFF1C1C1C);
        int rows = used > 0 ? Math.max(1, (int) Math.round(fraction * 16)) : 0;
        graphics.fill(x + 2, y + 18 - rows, x + 18, y + 18, color);
        if (pickerAnchor != null || mouseX < x || mouseX >= x + 20 || mouseY < y || mouseY >= y + 20) return;
        var tooltip = new ArrayList<FormattedCharSequence>();
        tooltip.add(Component.literal("Context window:").withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
        String estimate = usage.has("estimated") && usage.get("estimated").getAsBoolean() ? "~" : "";
        if (!usage.has("usedTokens") || usage.get("usedTokens").isJsonNull())
            tooltip.add(Component.literal("Not reported yet").getVisualOrderText());
        else if (size > 0) {
            int percent = (int) Math.round(fraction * 100);
            tooltip.add(Component.literal(estimate + percent + "% used (" + (100 - percent) + "% left)").getVisualOrderText());
            tooltip.add(Component.literal(estimate + tokens(used) + " / " + tokens(size) + " tokens used").getVisualOrderText());
        } else tooltip.add(Component.literal(estimate + tokens(used) + " tokens used").getVisualOrderText());
        // Centered above the square so it clears the model button and screen edge.
        graphics.renderTooltip(font, tooltip, (screenWidth, screenHeight, ignoredX, ignoredY, tooltipWidth, tooltipHeight) ->
            new org.joml.Vector2i(Math.max(4, Math.min(x + 10 - tooltipWidth / 2, (docked() ? width : screenWidth) - tooltipWidth - 4)), y - tooltipHeight - 8), mouseX, mouseY);
    }

    private static long number(JsonObject value, String key) {
        var field = value.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isNumber() ? field.getAsLong() : 0;
    }

    private static String tokens(long count) {
        if (count >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0).replace(".0M", "M");
        return count >= 1000 ? Math.round(count / 1000.0) + "k" : Long.toString(count);
    }

    @Override public boolean mouseClicked(double mouseX,double mouseY,int button) {
        if (pickerAnchor != null) {
            if (mouseX >= pickerX && mouseX < pickerX + pickerWidth && mouseY >= pickerY && mouseY < pickerY + pickerHeight) {
                for (var widget : List.copyOf(pickerWidgets)) {
                    if (widget.mouseClicked(mouseX, mouseY, button)) {
                        if (pickerAnchor != null && pickerWidgets.contains(widget)) { setFocused(widget); setDragging(button == 0); }
                        return true;
                    }
                }
                return true;
            }
            boolean onAnchor = pickerAnchor.isMouseOver(mouseX, mouseY);
            closePicker();
            if (onAnchor) return true;
        }
        // Rebind existing widgets after HotSwap, which can invalidate old lambda methods.
        composer.setValueListener(this::draftChanged);
        if(button==0 && mouseX>=left+4 && mouseX<left+contentWidth-8 && mouseY>=transcriptTop+4 && mouseY<transcriptBottom-4 && !lines.isEmpty()) {
            pressedMedia=mediaAt(mouseX,mouseY);
            if(pressedMedia!=null){clearSelection();return true;}
            for (var entry : richPanels) {
                var panel=entry.panel();
                int x=left+8+entry.inset()+panel.inset;
                int y=transcriptTop+6+(entry.lineOffset()+panel.first)*LINE_HEIGHT-scroll;
                if(mouseY>=y && mouseY<y+LINE_HEIGHT && mouseX>=x+panel.width-36 && mouseX<x+panel.width) {
                    minecraft.keyboardHandler.setClipboard(panel.source);
                    feedback="Copied "+panel.label;
                    clearSelection();
                    return true;
                }
            }
            for(var entry:disclosures)if(entry.button().isMouseOver(mouseX,mouseY)) {
                clearSelection();
                return super.mouseClicked(mouseX,mouseY,button);
            }
            long now=net.minecraft.Util.getMillis();
            textClickCount=now-lastTextClick<=350 && Math.abs(mouseX-lastTextClickX)<=4 && Math.abs(mouseY-lastTextClickY)<=4
                ?textClickCount%3+1:1;
            lastTextClick=now;lastTextClickX=mouseX;lastTextClickY=mouseY;
            selectionMode=textClickCount;
            var position=textPosition(mouseX,mouseY);
            if(selectionMode>1) {
                String text=lineText(lines.get(position.line()));
                int character=position.character();
                if(character>0 && mouseX<lineX(lines.get(position.line()))+lineWidthAt(lines.get(position.line()),character))
                    position=new TextPosition(position.line(),text.offsetByCodePoints(character,-1));
                var unit=selectionUnit(position,selectionMode);
                selectionAnchor=selectionUnitStart=unit[0];
                selectionEnd=selectionUnitEnd=unit[1];
            } else {
                selectionAnchor=position;
                selectionEnd=position;
            }
            pressedLink=selectionMode==1?linkAt(mouseX,mouseY):null;
            selectingText=true;
            setFocused(null);
            composer.setFocused(false);
            return true;
        }
        clearSelection();
        return super.mouseClicked(mouseX,mouseY,button);
    }

    @Override public boolean mouseDragged(double mouseX,double mouseY,int button,double deltaX,double deltaY) {
        pressedMedia=null;
        if(button==0 && selectingText) {
            pressedLink=null;
            if(mouseY<transcriptTop+4)scroll=Math.max(0,scroll-LINE_HEIGHT);
            else if(mouseY>transcriptBottom-4)scroll=Math.min(maxScroll,scroll+LINE_HEIGHT);
            var position=textPosition(mouseX,mouseY);
            if(selectionMode>1 && selectionUnitStart!=null) {
                var unit=selectionUnit(position,selectionMode);
                boolean backwards=position.line()<selectionUnitStart.line() || position.line()==selectionUnitStart.line() && position.character()<selectionUnitStart.character();
                selectionAnchor=backwards?selectionUnitEnd:selectionUnitStart;
                selectionEnd=backwards?unit[0]:unit[1];
            } else selectionEnd=position;
            textClickCount=0;
            return true;
        }
        return super.mouseDragged(mouseX,mouseY,button,deltaX,deltaY);
    }

    @Override public boolean mouseReleased(double mouseX,double mouseY,int button) {
        if(button==0 && pressedMedia!=null) {
            var entry=pressedMedia;pressedMedia=null;
            if(entry==mediaAt(mouseX,mouseY)) {
                var media=entry.media();var image=chatImages.get(media.kind(),media.source());
                if(!image.error.isBlank())chatImages.retry(media.kind(),media.source());
            }
            return true;
        }
        if(button==0 && selectingText) {
            selectingText=false;
            String target=pressedLink;
            pressedLink=null;
            if(target!=null && !hasSelection() && target.equals(linkAt(mouseX,mouseY))) openLink(target);
            return true;
        }
        return super.mouseReleased(mouseX,mouseY,button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (pickerAnchor != null) {
            if (pickerAnchor != effortButton && vertical != 0 && pickerChoices.size() > pickerPageSize) {
                pickerOffset = Math.clamp(pickerOffset - (int) Math.signum(vertical) * 3, 0, Math.max(0, pickerChoices.size() - pickerPageSize));
                populatePicker();
            }
            return true;
        }
        if (composer.isMouseOver(mouseX, mouseY)) return composer.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        if(queueRows>0 && mouseX>=left && mouseX<=left+contentWidth && mouseY>=transcriptBottom+4 && mouseY<transcriptBottom+4+queueRows*22) {
            queueOffset=Math.clamp(queueOffset-(int)Math.signum(vertical),0,Math.max(0,AgentModels.queuedMessages(state).size()-queueRows));
            transcriptKey="";
            rebuildTranscript();
            return true;
        }
        if (mouseX >= left && mouseX <= left + contentWidth && mouseY >= transcriptTop && mouseY <= transcriptBottom) {
            if(horizontal!=0 || hasShiftDown()) for(var entry:richPanels) {
                var panel=entry.panel();
                int y=transcriptTop+6+(entry.lineOffset()+panel.first)*LINE_HEIGHT-scroll;
                if(mouseY>=y && mouseY<y+(panel.end-panel.first)*LINE_HEIGHT && panel.maxScroll()>0) {
                    panel.scroll=Math.clamp(panel.scroll-(int)((horizontal!=0?horizontal:vertical)*33),0,panel.maxScroll());
                    return true;
                }
            }
            scroll = Math.clamp(scroll - (int) (vertical * 33), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (pickerAnchor != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { closePicker(); return true; }
            if (key == GLFW.GLFW_KEY_TAB) {
                int index = pickerWidgets.indexOf(getFocused());
                setFocused(pickerWidgets.get(Math.floorMod(index + (hasShiftDown() ? -1 : 1), pickerWidgets.size())));
                return true;
            }
            return getFocused() != null && getFocused().keyPressed(key, scanCode, modifiers);
        }
        if (getFocused() instanceof EditBox field && field != nameField && field != bodySearch
            && key != GLFW.GLFW_KEY_TAB && key != GLFW.GLFW_KEY_ESCAPE) {
            return field.keyPressed(key, scanCode, modifiers);
        }
        composer.setValueListener(this::draftChanged);
        if(key == GLFW.GLFW_KEY_V && ChatInput.shortcut(modifiers)) {
            selectingText=false;
            selectionAnchor=selectionEnd=null;
            setFocused(composer);
            composer.setFocused(true);
            paste();
            return true;
        }
        if(!composer.isFocused() && isCopy(key) && hasSelection()) {
            minecraft.keyboardHandler.setClipboard(selectedText());
            return true;
        }
        if (!composer.isFocused() && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            scroll = Math.clamp(scroll + (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * Math.max(22, transcriptBottom - transcriptTop - 22), 0, maxScroll);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (composer.isFocused()) {
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) composer.insertText("\n");
                else send();
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    public JsonObject pointingContext() { return pointing == null ? null : pointing.deepCopy(); }

    public void capturePointing(JsonObject context) {
        pointing = context != null && state.has("minecraftAccess") && state.get("minecraftAccess").getAsBoolean()
            ? context.deepCopy() : null;
    }

    private boolean actionWorking() {
        if (!state.has("action") || !state.get("action").isJsonObject()) return false;
        String status = AgentModels.text(state.getAsJsonObject("action"), "status");
        return status.equals("active") || status.equals("starting") || status.equals("stopping") || status.equals("running") || status.equals("queued") || status.equals("in_progress");
    }

    private int headerWidth() {
        return compact ? contentWidth - 28 : contentWidth - (inventoryButton.visible ? 332 : 256);
    }

    private String pointingLabel() {
        if (pointing == null) return "No captured target";
        if (!pointing.has("target")) return "Captured your position and view";
        var target = pointing.getAsJsonObject("target");
        String type = AgentModels.text(target, "type");
        if (type.equals("miss")) return "Captured your position and view (no target)";
        String name = AgentModels.text(target, type.equals("block") ? "block" : "name");
        if (name.isBlank()) name = type;
        String position = "";
        if (target.has("position")) {
            var pos = target.getAsJsonObject("position");
            position = " @ " + (int) Math.floor(pos.get("x").getAsDouble()) + ", " + (int) Math.floor(pos.get("y").getAsDouble()) + ", " + (int) Math.floor(pos.get("z").getAsDouble());
        }
        return "Pointing: " + name + position;
    }

    @Override public boolean isPauseScreen() { return false; }
}
