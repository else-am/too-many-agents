package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
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
    private String creationProjectName="Minecraft";
    private boolean creationGit, creationMinecraft;
    private Checkbox worktreeBox, minecraftBox;
    private Consumer<String> created;
    private final List<String> bodies = new ArrayList<>();
    private int catalogVersion;
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
    private boolean savingPermissions, savingFollow;
    private List<PickerChoice> pickerChoices;
    private int pickerOffset, pickerPageSize, pickerRowHeight;
    private record PickerChoice(String label, String description, String icon, Runnable select) {
        PickerChoice(String label, String description, Runnable select) { this(label, description, "", select); }
    }
    private Button modelButton, effortButton, speedButton, settingsButton, archiveButton, inventoryButton, followButton, sendButton;
    private List<AbstractWidget> pickerWidgets;
    private Button pickerAnchor;
    private int pickerX, pickerY, pickerWidth, pickerHeight;

    private record Line(FormattedCharSequence text, int color, long replyVersion, int inset, String copyText) {
        Line(FormattedCharSequence text,int color,long replyVersion,int inset) {this(text,color,replyVersion,inset,null);}
        Line(FormattedCharSequence text,int color,long replyVersion) {this(text,color,replyVersion,0);}
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
        double target=mouseX-left-8-line.inset();
        int offset=0;
        while(offset<text.length()) {
            int next=offset+Character.charCount(text.codePointAt(offset));
            if(target<(font.width(text.substring(0,offset))+font.width(text.substring(0,next)))/2.0)break;
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
        int x=left+8+line.inset();
        graphics.fill(x+font.width(text.substring(0,from)),y-1,x+font.width(text.substring(0,to)),y+font.lineHeight+1,0xB05A7EAA);
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
        models.model = AgentModels.text(state, "model");
        models.effort = AgentModels.text(state, "effort");
        String serviceTier = AgentModels.text(state, "serviceTier");
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
        for (String[] defaultValue : new String[][]{{"body", toomanyagents.StarterAgents.body()}, {"providerId", "codex"}, {"mode", "survival"}, {"projectId", ""}}) {
            if (AgentModels.text(this.creationSettings, defaultValue[0]).isBlank()) this.creationSettings.addProperty(defaultValue[0], defaultValue[1]);
        }
        for (String key : new String[]{"cheats", "following", "nativeSubagentsEnabled"}) {
            if (!this.creationSettings.has(key)) this.creationSettings.addProperty(key, false);
        }
        if (!this.creationSettings.has("permissionMode")) this.creationSettings.addProperty("permissionMode", access.defaultPermissionMode());
        state = this.creationSettings;
        models.model = AgentModels.text(state, "model");
        models.effort = AgentModels.text(state, "effort");
        String tier = AgentModels.text(state, "serviceTier");
        models.serviceTier = tier.isBlank() ? "default" : tier;
    }

    private void readCreationProject(){
        creationProjectName="Minecraft";creationGit=false;
        var projects = access.projects();
        creationMinecraft = AgentModels.minecraftProject(projects, AgentModels.text(creationSettings, "projectId"));
        if (creationMinecraft || !creationSettings.has("minecraftAccess"))
            creationSettings.addProperty("minecraftAccess", creationMinecraft);
        for(var item:AgentModels.array(projects,"projects")){
            var project=item.getAsJsonObject();
            if(!AgentModels.text(project,"id").equals(AgentModels.text(creationSettings,"projectId")))continue;
            creationProjectName=AgentModels.text(project,"name");
            creationGit=project.has("isGitRepository")&&project.get("isGitRepository").isJsonPrimitive()&&project.get("isGitRepository").getAsBoolean();
        }
    }

    public String agentId() { return agentId; }
    public boolean draft() { return agentId.isBlank(); }

    private JsonObject settings() {
        if (!draft()) return AgentSettingsScreen.settings(state);
        var result = creationSettings.deepCopy();
        result.addProperty("model", models.model);
        result.addProperty("effort", models.effort);
        result.addProperty("serviceTier", models.serviceTier);
        AgentModels.permissionDefaults(result);
        return result;
    }

    private boolean availableHere() {
        return draft() || activeAgent() && state.has("currentWorld") && state.get("currentWorld").getAsBoolean();
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
        closePicker();
        if (docked()) closePanel.run();
        else super.onClose();
    }

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
                    models.serviceTier = models.serviceTier.equals("priority") ? "default" : "priority";
                    refreshButtons();
                }, message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                graphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(!active ? "widget/button_disabled"
                    : isHoveredOrFocused() ? "widget/button_highlighted" : "widget/button"), getX(), getY(), 20, 20);
                int color = !active ? 0xFF666666 : models.serviceTier.equals("priority") ? 0xFFFFD45A : 0xFFAAAAAA;
                int x = getX() + 6, y = getY() + 3;
                // A small pixel bolt avoids depending on a font's symbol coverage.
                for (int row = 0; row < 6; row++) {
                    graphics.fill(x + 4 - row / 2, y + row + 1, x + 7 - row / 2, y + row + 2, color);
                    graphics.fill(x + 3 - row / 2, y + row + 7, x + 6 - row / 2, y + row + 8, color);
                }
            }
        });
        followButton = addRenderableWidget(Button.builder(Component.literal("Follow"), button -> {
            if (draft()) {
                creationSettings.addProperty("following", !following());
                refreshButtons();
                return;
            }
            if (savingFollow) return;
            boolean resume = AgentModels.text(state,"followPauseReason").equals("work");
            savingFollow = true; refreshButtons();
            access.setFollowing(agentId, resume || !following()).whenComplete((done, failure) -> executeUi(() -> {
                savingFollow = false;
                if (failure != null) feedback = AgentModels.error(failure);
                refreshButtons();
            }));
        }).bounds(compact ? left : left + contentWidth - 320, controlsY, 68, 20).build());
        inventoryButton = addRenderableWidget(Button.builder(Component.literal("Inventory"), button -> toggleInventory.run())
            .bounds(compact ? left + 74 : left + contentWidth - 246, controlsY, 70, 20).build());
        // One archive, no confirmation: restore lives in Mod settings → Archive.
        archiveButton = addRenderableWidget(Button.builder(Component.literal("Archive"), button -> archive())
            .bounds(compact ? left + 148 : left + contentWidth - 170, controlsY, 58, 20).build());
        settingsButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if(settingsPanel!=null){settingsPanel.onClose();return;}
            var screen=new AgentSettingsScreen(access,returnScreen(),settings(),changes -> {
                if(draft()){
                    creationSettings=changes.deepCopy();state=creationSettings;readCreationProject();
                    if(!AgentModels.text(creationSettings,"name").equals(suggestedName))nameSuggested=false;
                    if(nameField!=null)nameField.setValue(AgentModels.text(creationSettings,"name"));
                    models.model=AgentModels.text(creationSettings,"model");models.effort=AgentModels.text(creationSettings,"effort");
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                return access.updateSettings(agentId,changes);
            },draft()?null:agentId);
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
            minecraftBox.visible = !creationMinecraft;
            // The project comes from where creation started; a worktree is the one choice about it.
            boolean worktree = creationSettings.has("useWorktree") && creationSettings.get("useWorktree").getAsBoolean();
            worktreeBox = Checkbox.builder(Component.literal("Worktree"), font).selected(worktree)
                .onValueChange((box, value) -> creationSettings.addProperty("useWorktree", value)).build();
            worktreeBox.setTooltip(Tooltip.create(Component.literal("Work in a separate Git worktree instead of the project folder")));
            worktreeBox.visible = creationGit;
            addRenderableWidget(worktreeBox);
        } else { nameField = null; bodyButton = null; minecraftBox = null; worktreeBox = null; }
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
        var catalog = provider.isBlank() ? access.catalog() : access.catalog(provider);
        catalog.whenComplete((result, failure) -> executeUi(() -> {
            if (version != catalogVersion || !provider.equals(AgentModels.text(state, "providerId"))) return;
            loadingModels = false;
            if (failure != null) feedback = "Model list unavailable: " + AgentModels.error(failure);
            else {
                models.load(result);
                if (draft()) {
                    creationSettings.add("provider", AgentModels.provider(result).deepCopy());
                    AgentModels.permissionDefaults(creationSettings);
                    for (var item : AgentModels.array(AgentModels.provider(creationSettings), "permissionFields")) {
                        var field = item.getAsJsonObject();
                        String key = AgentModels.text(field, "key");
                        var options = AgentModels.array(field, "options");
                        if (!options.isEmpty() && options.asList().stream().noneMatch(option -> AgentModels.text(option.getAsJsonObject(), "id").equals(AgentModels.text(creationSettings, key)))) {
                            if (field.has("default")) creationSettings.add(key, field.get("default").deepCopy());
                        }
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
                String id = AgentModels.text(model, "id");
                String name = AgentModels.text(model, "name");
                pickerChoices.add(new PickerChoice((id.equals(models.model) ? "✓ " : "") + (name.isBlank() ? id : name), "", () -> {
                    models.model = id;
                    models.normalize();
                }));
            }
        } else if (anchor == providerButton) {
            if (!draft()) return;
            for (String provider : List.of("codex", "claude")) {
                pickerChoices.add(new PickerChoice((provider.equals(AgentModels.text(state, "providerId")) ? "✓ " : "")
                    + providerName(provider), "", provider, () -> selectProvider(provider)));
            }
        } else if (anchor == bodyButton) {
            String selected = AgentModels.text(creationSettings, "body");
            for (String body : bodies) pickerChoices.add(new PickerChoice((body.equals(selected) ? "✓ " : "") + bodyLabel(body), body,
                () -> creationSettings.addProperty("body", body)));
            bodyChoices = List.copyOf(pickerChoices);
        } else if (anchor == permissionsButton) {
            var field = permissionField();
            String selected = AgentModels.text(settings(), "permissionMode");
            for (var item : AgentModels.array(field, "options")) {
                var option = item.getAsJsonObject();
                String id = AgentModels.text(option, "id");
                pickerChoices.add(new PickerChoice((id.equals(selected) ? "✓ " : "") + AgentModels.text(option, "label"),
                    switch (id) {
                        case "accept-edits" -> "Work in project folders; ask for extra access.";
                        case "auto" -> "Automatically review requests for extra access.";
                        case "full" -> "No sandbox or approval prompts.";
                        default -> AgentModels.text(option, "description");
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
        // Permission keys belong to the provider that supplied their choices.
        for (var item : AgentModels.array(AgentModels.provider(creationSettings), "permissionFields")) {
            creationSettings.remove(AgentModels.text(item.getAsJsonObject(), "key"));
        }
        creationSettings.remove("provider");
        creationSettings.addProperty("providerId", provider);
        models.model = ""; models.effort = ""; models.serviceTier = "default";
        models.load(new JsonObject());
        catalogVersion++;
        loadingModels = false;
        requestModels(() -> {});
    }

    private JsonObject permissionField() {
        for (var item : AgentModels.array(AgentModels.provider(state), "permissionFields")) {
            var field = item.getAsJsonObject();
            if (AgentModels.text(field, "key").equals("permissionMode")) return field;
        }
        return new JsonObject();
    }

    private void savePermission(String id) {
        if (draft()) { creationSettings.addProperty("permissionMode", id); refreshButtons(); return; }
        var settings = new JsonObject();
        settings.addProperty("permissionMode", id);
        savingPermissions = true;
        access.updateSettings(agentId, settings).whenComplete((unused, failure) -> executeUi(() -> {
            savingPermissions = false;
            state = access.snapshot(agentId);
            feedback = failure != null ? AgentModels.error(failure)
                : state.has("permissionsPending") && state.get("permissionsPending").getAsBoolean() ? "Approval settings saved for the next turn." : "";
            refreshButtons();
        }));
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
        if (!draft()) state = access.snapshot(agentId);
        readRequest();
        requestTranscript();
        rebuildTranscript();
        refreshButtons();
    }

    private void readRequest() {
        request = new JsonObject();
        question = new JsonObject();
        for (var item : AgentModels.array(state, "requests")) {
            var pending = item.getAsJsonObject();
            if (AgentModels.text(pending, "kind").equals("approval")) {
                if (request.isEmpty()) request = pending;
            } else if (question.isEmpty()) question = pending;
        }
        requestKey = AgentModels.array(state, "requests").toString();
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
        if (state.has("turnActive")) return state.get("turnActive").getAsBoolean();
        String status = AgentModels.text(state, "status").toLowerCase(java.util.Locale.ROOT);
        return status.equals("running") || status.equals("working") || status.equals("busy") || status.equals("starting")
            || status.equals("inprogress") || approval()
            || (!question.isEmpty() && !(question.has("async") && question.get("async").getAsBoolean()));
    }

    private static String providerName(String id) { return id.equals("claude") ? "Claude Code" : "Codex"; }

    /** Approval sits left; speed, context, provider, model and effort sit right, each as wide as its widest choice. */
    private void layoutControls() {
        int gap = 4;
        var permissionLabels = new ArrayList<String>(List.of("Permissions"));
        for (var item : AgentModels.array(permissionField(), "options")) permissionLabels.add(AgentModels.text(item.getAsJsonObject(), "label"));
        var modelLabels = new ArrayList<String>(List.of(models.modelLabel().isBlank() ? loadingModels ? "Loading models…" : "Choose model…" : models.modelLabel()));
        for (var model : models.choices()) {
            String name = AgentModels.text(model, "name");
            modelLabels.add(name.isBlank() ? AgentModels.text(model, "id") : name);
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
        boolean increasedSpeed = models.serviceTier.equals("priority");
        String speedHint = increasedSpeed ? "turn off increased speed" : "increase speed (consumes extra usage)";
        speedButton.setMessage(Component.literal(speedHint));
        speedButton.setTooltip(Tooltip.create(Component.literal(speedHint)));
        speedButton.visible = models.serviceTiers().stream().anyMatch(tier -> AgentModels.text(tier, "id").equals("priority"))
            && models.serviceTiers().stream().anyMatch(tier -> AgentModels.text(tier, "id").equals("default"));
        layoutControls();
        String label = models.modelLabel();
        modelButton.setMessage(Component.literal(font.plainSubstrByWidth(label.isBlank() ? loadingModels ? "Loading models…" : "Choose model…" : label, modelButton.getWidth() - 20) + " ▾"));
        modelButton.setTooltip(null);
        effortButton.setMessage(Component.literal((models.effort.isBlank() ? "Default" : models.effort) + " ▾"));
        effortButton.setTooltip(null);
        boolean active = availableHere();
        modelButton.active = effortButton.active = active && !loadingModels && !sending;
        speedButton.active = active && speedButton.visible && !loadingModels && !sending;
        effortButton.active &= !models.available() || models.efforts().size() > 1;
        String permission = AgentModels.text(settings(), "permissionMode");
        var permissionField = permissionField();
        for (var item : AgentModels.array(permissionField, "options")) {
            var option = item.getAsJsonObject();
            if (AgentModels.text(option, "id").equals(permission)) { permission = AgentModels.text(option, "label"); break; }
        }
        boolean permissionsPending = state.has("permissionsPending") && state.get("permissionsPending").getAsBoolean();
        permissionsButton.setMessage(Component.literal(font.plainSubstrByWidth(permission.isBlank() ? "Permissions" : permission, permissionsButton.getWidth() - (permissionsPending ? 28 : 20)) + (permissionsPending ? " * ▾" : " ▾")));
        permissionsButton.active = active && !sending && !loadingModels && !savingPermissions && !permissionField.isEmpty();
        permissionsButton.setTooltip(Tooltip.create(Component.literal(permissionsPending
            ? "Saved for the next turn. The running turn keeps its approval settings."
            : "Also used for new conversations")));
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
        followButton.setX(compact ? left : left + contentWidth - (inventoryButton.visible ? 320 : 244));
        archiveButton.visible = true;
        archiveButton.active = !draft() && active && !working() && !sending;
        archiveButton.setTooltip(Tooltip.create(Component.literal(draft() ? "Available after creating the agent" : working() ? "Stop the agent's work to archive it" : "Archive: removes the body and keeps the chat. Restore it from Mod settings → Archive.")));
        followButton.visible = true;
        followButton.active = active && !savingFollow && !sending;
        followButton.setMessage(Component.literal(savingFollow ? "…" : AgentModels.text(state,"followPauseReason").equals("work") ? "Resume" : following() ? "Following" : "Follow"));
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
            minecraftBox.active = !sending;
            minecraftBox.visible = !settingsCovering && !creationMinecraft;
            worktreeBox.visible = !settingsCovering && creationGit;
        }
        boolean busy = working();
        boolean showStop = !hasDraft() && (busy || actionWorking());
        sendButton.active = active && !savingPermissions && !sending && !stopping && !importingImages
            && (showStop || hasDraft() && !models.model.isBlank() && models.permissionError(AgentModels.text(settings(),"permissionMode")).isBlank());
        if (draft()) sendButton.active &= !loadingModels && models.available() && !AgentModels.text(creationSettings, "name").isBlank()
            && !AgentModels.text(creationSettings, "body").isBlank();
        sendButton.setMessage(Component.literal(sending || stopping ? "…" : showStop ? "Stop" : "Send"));
        sendButton.setTooltip(null);
    }

    private boolean activeAgent() {
        String lifecycle = AgentModels.text(state, "lifecycle");
        return (lifecycle.isBlank() || lifecycle.equals("active"))
            && !(state.has("conversationArchived") && state.get("conversationArchived").getAsBoolean())
            && !(state.has("bodyRemoved") && state.get("bodyRemoved").getAsBoolean());
    }

    private boolean following() {
        return state.has("following") && !state.get("following").isJsonNull() && state.get("following").getAsBoolean();
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
            creation.addProperty("initialTask", message); // names a new worktree; the message itself is sent below
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
        access.send(agentId, message, model, effort, captured, "queue", tier, attachments).whenComplete((unused, failure) -> executeUi(() -> {
            sending = false;
            if (failure != null) feedback = "Send failed: " + AgentModels.error(failure);
            else {
                if (draft.equals(message)) {
                    draft = "";
                    if (composer != null) composer.setValue("");
                }
                images().removeAll(attachments);
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
        if (draft()) return;
        long now=System.currentTimeMillis();
        JsonObject conversation=state.has("conversation")?state.getAsJsonObject("conversation"):new JsonObject();
        long sequence=conversation.has("sequence")?conversation.get("sequence").getAsLong():0;
        if(transcriptLoading || now<nextTranscriptPoll || loadedSequence==sequence) return;
        transcriptLoading=true; nextTranscriptPoll=now+250;
        int version=queryVersion;
        var query=new JsonObject();query.addProperty("before",before);query.addProperty("group",expandedGroup);
        query.addProperty("groupOffset",groupOffset);query.addProperty("row",expandedRow);query.addProperty("textOffset",textOffset);
        access.transcript(agentId,query).whenComplete((result,failure)->executeUi(()->{
            transcriptLoading=false;
            if(version!=queryVersion)return;
            if(failure!=null){feedback="History unavailable: "+AgentModels.error(failure);nextTranscriptPoll=System.currentTimeMillis()+2000;return;}
            transcript=result;loadedSequence=result.get("sequence").getAsLong();
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

    private void detail(JsonObject row,int inset,int wrapWidth) {
        if(!AgentModels.text(row,"id").equals(expandedRow) || !transcript.has("detail"))return;
        var detail=transcript.getAsJsonObject("detail");
        if(!AgentModels.text(detail,"id").equals(expandedRow))return;
        int offset=detail.get("offset").getAsInt();
        if(offset>0)disclosure("↑ Previous output",()->changeTranscript(()->textOffset=Math.max(0,offset-8000)),inset);
        paragraph(AgentModels.text(detail,"text"),0xBDBDBD,0,inset,wrapWidth);
        if(detail.get("hasMore").getAsBoolean())disclosure("↓ More output",()->changeTranscript(()->textOffset=offset+8000),inset);
    }

    private void userMessage(JsonObject row) {
        String id=AgentModels.text(row,"id"),text=AgentModels.text(row,"text");
        String error=AgentModels.text(row,"status").equals("rejected")?"Not sent: "+AgentModels.text(row,"detail"):"";
        int attachments=row.has("attachments")?row.get("attachments").getAsInt():0;
        boolean hasDetail=attachments>0 || row.has("truncated")&&row.get("truncated").getAsBoolean();
        int maxWidth=Math.max(40,(contentWidth-40)*3/4);
        int textWidth=Math.min(maxWidth,100);
        for(var line:font.split(Component.literal(text),maxWidth))textWidth=Math.max(textWidth,font.width(line));
        if(!error.isEmpty() || hasDetail)textWidth=maxWidth;
        int bubbleWidth=textWidth+16,inset=contentWidth-24-bubbleWidth;
        int firstLine=lines.size();
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
        paragraph(text,0xEEEEEE,0,inset+8,textWidth);
        if(!error.isEmpty())paragraph(error,0xFFAAAA,0,inset+8,textWidth);
        if(attachments>0) {
            var labels=new ArrayList<String>();
            int imageCount=row.has("images")?row.get("images").getAsInt():0;
            for(int i=1;i<=imageCount;i++)labels.add("[Image "+i+"]");
            if(attachments>imageCount)labels.add((attachments-imageCount)+" other attachment(s)");
            paragraph(String.join(" ",labels),0xA9C9E0,0,inset+8,textWidth);
        }
        if(hasDetail)disclosure((id.equals(expandedRow)?"▾ Hide":"▸ Read")+" full message",
            ()->changeTranscript(()->{expandedRow=id.equals(expandedRow)?"":id;textOffset=0;}),inset+8);
        detail(row,inset+8,textWidth);
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
        messageBubbles.add(new MessageBubble(firstLine,lines.size(),inset,bubbleWidth));
        lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
    }

    private void transcriptRow(JsonObject row,boolean nested) {
        String id=AgentModels.text(row,"id"),role=AgentModels.text(row,"role");
        if(role.equals("user")) { userMessage(row); return; }
        boolean message=role.equals("assistant");
        if(message) {
            // Collapsed activity and off-page replies are never marked as read.
            long reply=role.equals("assistant") && !nested && id.equals(AgentModels.text(transcript,"lastReplyId"))
                && !(row.has("truncated")&&row.get("truncated").getAsBoolean())?transcript.get("replyVersion").getAsLong():0;
            paragraph(AgentModels.text(row,"text"),0xEEEEEE,reply,nested?12:0);
            if(AgentModels.text(row,"status").equals("rejected"))paragraph("Not sent: "+AgentModels.text(row,"detail"),0xFFAAAA,0,0);
            int attachments=row.has("attachments")?row.get("attachments").getAsInt():0;
            if(attachments>0)paragraph(attachments+(attachments==1?" attachment":" attachments"),0xAAAAAA,0,0);
            if(attachments>0 || row.has("truncated")&&row.get("truncated").getAsBoolean())
                disclosure((id.equals(expandedRow)?"▾ Hide":"▸ Read")+" full message",()->changeTranscript(()->{expandedRow=id.equals(expandedRow)?"":id;textOffset=0;}));
        } else {
            String label=AgentModels.text(row,"title");if(label.isBlank())label=AgentModels.text(row,"kind");
            disclosure((id.equals(expandedRow)?"▾ ":"▸ ")+label+" - "+AgentModels.text(row,"status"),()->changeTranscript(()->{expandedRow=id.equals(expandedRow)?"":id;textOffset=0;}),nested?12:0);
        }
        detail(row);
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
        String key=loadedSequence+":"+queryVersion+":"+contentWidth+":"+AgentModels.text(state,"color")+":"+working()+":"+AgentModels.array(state,"queuedMessages")+":"+AgentModels.text(state,"canSteer")+":"+images()+":"+pointing+":"+composerFeedback()+":"+requestKey+":"+respondingRequests+":"+requestErrors;
        if(key.equals(transcriptKey))return;
        if(selectingText)return;
        var previousLines=new ArrayList<>(lines);
        boolean follow=!hasSelection() && !preserveScroll && scroll>=maxScroll-4;
        preserveScroll=false;transcriptKey=key;
        for(var entry:disclosures)removeWidget(entry.button());
        disclosures.clear();lines.clear();messageBubbles.clear();thinkingLine=-1;
        rebuildQueue();
        boolean hasRunningGroup=false;
        if(transcript.has("hasOlder")&&transcript.get("hasOlder").getAsBoolean())
            disclosure("↑ Older messages",()->changeTranscript(()->{before=AgentModels.text(transcript,"firstId");expandedGroup="";expandedRow="";scroll=0;}));
        if(!before.isBlank())disclosure("↓ Latest messages",()->changeTranscript(()->{before="";expandedGroup="";expandedRow="";scroll=Integer.MAX_VALUE;}));
        for(var value:AgentModels.array(transcript,"entries")) {
            var row=value.getAsJsonObject();String id=AgentModels.text(row,"id");
            if(!AgentModels.text(row,"kind").equals("work")){transcriptRow(row,false);continue;}
            boolean expanded=id.equals(expandedGroup),running=row.has("running")&&row.get("running").getAsBoolean();
            hasRunningGroup|=running;
            long seconds=Math.max(0,(row.get("updatedAt").getAsLong()-row.get("createdAt").getAsLong())/1000);
            String duration=seconds<60?seconds+"s":seconds/60+"m "+seconds%60+"s";
            String title=(expanded?"▾ ":"▸ ")+(running?"Working":"Worked for "+duration);
            disclosure(title,()->changeTranscript(()->{expandedGroup=expanded?"":id;expandedRow="";groupOffset=0;textOffset=0;}),0,running);
            if(expanded && transcript.has("group")) {
                var group=transcript.getAsJsonObject("group");int offset=group.get("offset").getAsInt();
                if(offset>0)disclosure("↑ Previous activities",()->changeTranscript(()->{groupOffset=Math.max(0,offset-24);expandedRow="";}));
                for(var member:AgentModels.array(group,"entries"))transcriptRow(member.getAsJsonObject(),true);
                if(group.get("hasMore").getAsBoolean())disclosure("↓ More activities",()->changeTranscript(()->{groupOffset=offset+24;expandedRow="";}));
            }
        }
        if(working() && before.isBlank() && !hasRunningGroup) {
            thinkingLine=lines.size();
            lines.add(new Line(Component.empty().getVisualOrderText(),0,0));
        }
        inlineRequests();
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

    private void inlineRequests() {
        for (var item : AgentModels.array(state, "requests")) {
            var pending = item.getAsJsonObject();
            String id = AgentModels.text(pending, "id");
            boolean approval = AgentModels.text(pending, "kind").equals("approval");
            boolean busy = respondingRequests.contains(id);
            lines.add(new Line(Component.empty().getVisualOrderText(), 0, 0));
            paragraph(approval ? "Approval requested" : "Question", 0xE3CAA0, 0, 0);
            paragraph(AgentModels.text(pending, "title"), 0xEEEEEE, 0, 0);
            String details = AgentModels.text(pending, "details");
            if (!details.isBlank()) paragraph(details, 0xBBBBBB, 0, 0);
            for (var choice : AgentModels.array(pending, "options")) {
                String label = choice.getAsString();
                var wrapped = font.split(Component.literal(label), contentWidth - 40);
                int h = Math.max(24, ((wrapped.size() * LINE_HEIGHT + 12 + LINE_HEIGHT - 1) / LINE_HEIGHT) * LINE_HEIGHT);
                var button = new Button(left + 8, 0, contentWidth - 24, h - 2, Component.literal(label),
                    unused -> respondInline(id, label), narration -> narration.get()) {
                    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                            isHoveredOrFocused() ? 0xFF40505B : 0xFF29333B);
                        for (int i = 0; i < wrapped.size(); i++)
                            graphics.drawString(font, wrapped.get(i), getX() + 8, getY() + 6 + i * LINE_HEIGHT,
                                active ? 0xEEEEEE : 0x888888);
                    }
                };
                button.active = !busy && activeAgent();
                inlineWidget(button, h);
            }
            if (!approval) {
                var field = questionFields.computeIfAbsent(id, unused -> new EditBox(font, left + 8, 0, contentWidth - 86, 20, Component.literal("Your answer")) {
                    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
                        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                            respondInline(id, getValue());
                            return true;
                        }
                        return super.keyPressed(key, scanCode, modifiers);
                    }
                    @Override public void setFocused(boolean focused) {
                        super.setFocused(focused);
                        if (focused) focusedQuestion = id;
                        else if (focusedQuestion.equals(id)) focusedQuestion = "";
                    }
                });
                field.setX(left + 8);
                field.setWidth(contentWidth - 86);
                field.setMaxLength(16384);
                field.setHint(Component.literal("Write an answer…"));
                if (!field.getValue().equals(questionDrafts.getOrDefault(id, "")))
                    field.setValue(questionDrafts.getOrDefault(id, ""));
                field.setEditable(!busy);
                var send = Button.builder(Component.literal("Send"), unused -> respondInline(id, field.getValue()))
                    .bounds(left + contentWidth - 70, 0, 54, 20).build();
                send.active = !busy && !field.getValue().isBlank() && activeAgent();
                field.setResponder(value -> {
                    questionDrafts.put(id, value);
                    send.active = !respondingRequests.contains(id) && !value.isBlank() && activeAgent();
                });
                disclosures.add(new Disclosure(addWidget(send), lines.size()));
                inlineWidget(field, 24);
                if (focusedQuestion.equals(id)) setFocused(field);
            }
            if (busy) paragraph("Sending…", 0xBBBBBB, 0, 0);
            String error = requestErrors.getOrDefault(id, "");
            if (!error.isBlank()) paragraph(error, 0xFFAAAA, 0, 0);
        }
    }

    private void inlineWidget(AbstractWidget widget, int height) {
        disclosures.add(new Disclosure(addWidget(widget), lines.size()));
        for (int i = 0; i < height / LINE_HEIGHT; i++)
            lines.add(new Line(Component.empty().getVisualOrderText(), 0, 0));
    }

    private void respondInline(String id, String answer) {
        if (answer.isBlank() || !activeAgent() || !respondingRequests.add(id)) return;
        requestErrors.remove(id);
        // Disable every choice immediately, before the next render can rebuild the card.
        rebuildTranscript();
        CompletableFuture<Void> operation;
        try { operation = access.respond(agentId, id, answer); }
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
        if (!worktreeBox.visible && !minecraftBox.visible) return 0;
        return worktreeBox.visible && minecraftBox.visible && worktreeBox.getWidth() + 12 + minecraftBox.getWidth() > contentWidth ? 48 : 24;
    }

    private void rebuildQueue() {
        for(var button:queueControls)removeWidget(button);
        queueControls.clear();
        var queued=AgentModels.array(state,"queuedMessages");
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
            worktreeBox.setPosition(left, optionsY);
            minecraftBox.setPosition(left + (worktreeBox.visible && optionsHeight == 24 ? worktreeBox.getWidth() + 12 : 0),
                optionsY + (optionsHeight == 48 ? 24 : 0));
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
        String permissionError = models.permissionError(AgentModels.text(settings(),"permissionMode"));
        if (!permissionError.isBlank()) return permissionError;
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
        var queued=AgentModels.array(state,"queuedMessages");
        for(int row=0;row<queueRows && queueOffset+row<queued.size();row++) {
            int y=transcriptBottom+4+row*22;
            String text=AgentModels.text(queued.get(queueOffset+row).getAsJsonObject(),"text");
            var attached=AgentModels.array(queued.get(queueOffset+row).getAsJsonObject(),"images");
            for(int i=0;i<attached.size();i++)text+=(text.isBlank()?"":" ")+"[Image "+(i+1)+"]";
            String preview=text.replace('\n',' ').replace('\r',' ');
            int available=contentWidth-104;
            if(font.width(preview)>available)preview=font.plainSubstrByWidth(preview,available-font.width("…"))+"…";
            graphics.fill(left+4,y,left+contentWidth-4,y+21,0xF0272727);
            graphics.drawString(font,preview,left+12,y+6,0xCCCCCC);
            if(!preview.equals(text) && mouseX>=left+4 && mouseX<left+contentWidth-84 && mouseY>=y && mouseY<y+21)
                renderTooltip(graphics, font.split(Component.literal(text), contentWidth - 24), mouseX, mouseY);
        }
        for(var button:queueControls) {
            button.active=!sending && activeAgent();
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
        if (lines.isEmpty()) {
            graphics.drawWordWrap(font, Component.literal(transcriptLoading ? "Loading conversation…" : draft() ? "" : "Your agent is ready. Ask about its project, or give it something to build."), left + 8, transcriptTop + 10, contentWidth - 24, 0xAAAAAA);
        } else {
            int first=Math.max(0,scroll/LINE_HEIGHT-1);
            int last=Math.min(lines.size(),(scroll+transcriptBottom-transcriptTop)/LINE_HEIGHT+1);
            for (int i = first; i < last; i++) {
                int y = transcriptTop + 6 + i * LINE_HEIGHT - scroll;
                if (y >= transcriptTop - 11 && y < transcriptBottom) {
                    drawSelection(graphics,i,y);
                    if(i==thinkingLine)drawShimmer(graphics,"Thinking…",left+8,y,false);
                    else graphics.drawString(font, lines.get(i).text(), left + 8 + lines.get(i).inset(), y, lines.get(i).color());
                }
                if (y >= transcriptTop + 4 && y + font.lineHeight <= transcriptBottom - 4) seenReply = Math.max(seenReply, lines.get(i).replyVersion());
            }
        }
        for(var entry:disclosures)if(entry.button().visible)entry.button().render(graphics,mouseX,mouseY,partialTick);
        graphics.disableScissor();
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
            if (seenReply > 0) access.markRead(agentId, seenReply);
            if (!message.isBlank() && message.equals(AgentModels.text(state, "error")))
                access.markErrorRead(agentId, number(state, "errorVersion"));
        }
    }

    /** A button-sized square left of the provider fills from the bottom as the context window is used. */
    private void renderContext(GuiGraphics graphics, int mouseX, int mouseY) {
        var usage = AgentModels.object(AgentModels.object(AgentModels.object(state, "conversation"), "state"), "contextWindowUsage");
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
                if(character>0 && mouseX<left+8+lines.get(position.line()).inset()+font.width(text.substring(0,character)))
                    position=new TextPosition(position.line(),text.offsetByCodePoints(character,-1));
                var unit=selectionUnit(position,selectionMode);
                selectionAnchor=selectionUnitStart=unit[0];
                selectionEnd=selectionUnitEnd=unit[1];
            } else {
                selectionAnchor=position;
                selectionEnd=position;
            }
            selectingText=true;
            setFocused(null);
            composer.setFocused(false);
            return true;
        }
        clearSelection();
        return super.mouseClicked(mouseX,mouseY,button);
    }

    @Override public boolean mouseDragged(double mouseX,double mouseY,int button,double deltaX,double deltaY) {
        if(button==0 && selectingText) {
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
        if(button==0 && selectingText) {selectingText=false;return true;}
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
            queueOffset=Math.clamp(queueOffset-(int)Math.signum(vertical),0,Math.max(0,AgentModels.array(state,"queuedMessages").size()-queueRows));
            transcriptKey="";
            rebuildTranscript();
            return true;
        }
        if (mouseX >= left && mouseX <= left + contentWidth && mouseY >= transcriptTop && mouseY <= transcriptBottom) {
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
        return status.equals("running") || status.equals("queued") || status.equals("in_progress");
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
