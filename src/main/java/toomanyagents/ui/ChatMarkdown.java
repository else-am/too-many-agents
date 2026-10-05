package toomanyagents.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** CommonMark supplies syntax; Minecraft supplies glyphs, measurements and interaction. */
final class ChatMarkdown {
    static final int LINE_HEIGHT = 12;
    static final Style CODE = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath("too_many_agents", "code")).withColor(0xDDD5C5);
    private static final Parser PARSER = Parser.builder().extensions(List.of(
        TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create())).build();

    record Run(int x, FormattedCharSequence text) {}
    record Row(List<Run> runs, int inset, String copyText, Panel panel) {
        FormattedCharSequence text() {
            var sequences = new ArrayList<FormattedCharSequence>();
            for (var run : runs) {
                if (!sequences.isEmpty()) sequences.add(Component.literal("\t").getVisualOrderText());
                sequences.add(run.text());
            }
            return FormattedCharSequence.composite(sequences);
        }

        int widthAt(Font font, int character) {
            int used = 0;
            for (var run : runs) {
                int length = plain(run.text()).length();
                if (character <= used + length) return run.x() + prefixWidth(font, run.text(), character - used);
                used += length + 1;
            }
            var last = runs.getLast();
            return last.x() + font.width(last.text());
        }

        Style styleAt(Font font, int x) {
            for (var run : runs) if (x >= run.x() && x < run.x() + font.width(run.text()))
                return font.getSplitter().componentStyleAtWidth(run.text(), x - run.x());
            return null;
        }

        void draw(GuiGraphics graphics, Font font, int x, int y, int color) {
            for (var run : runs) graphics.drawString(font, run.text(), x + run.x(), y, color);
        }
    }

    static final class Panel {
        final int first, inset, width;
        final String label, source;
        final List<Integer> columns = new ArrayList<>();
        int end, contentWidth, scroll;
        Panel(int first, int inset, int width, String label, String source) {
            this.first = first; this.inset = inset; this.width = width; this.label = label; this.source = source;
        }
        int maxScroll() { return Math.max(0, contentWidth - width + 12); }
    }

    record Media(int first, int end, int inset, int width, int height, String kind, String source, String alt) {}
    record Layout(List<Row> rows, List<Panel> panels, List<Media> media) {}
    private final Font font;
    private final int width;
    private final ChatImages images;
    private final int maxImageHeight;
    private final List<Row> rows = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Media> media = new ArrayList<>();

    private ChatMarkdown(Font font, int width, ChatImages images, int maxImageHeight) {
        this.font = font; this.width = Math.max(40, width);
        this.images = images; this.maxImageHeight = maxImageHeight;
    }

    static Layout layout(Font font, String source, int width, ChatImages images, int maxImageHeight) {
        var layout = new ChatMarkdown(font, width, images, maxImageHeight);
        layout.blocks(PARSER.parse(source), 0, 0);
        // Messages provide their own bottom padding; keep gaps only between blocks.
        if (!layout.rows.isEmpty() && layout.rows.getLast().panel() == null
            && plain(layout.rows.getLast().text()).isEmpty()
            && (layout.media.isEmpty() || layout.media.getLast().end() < layout.rows.size()))
            layout.rows.removeLast();
        return new Layout(layout.rows, layout.panels, layout.media);
    }

    private void blocks(Node parent, int inset, int depth) {
        // Keep adversarial nesting from exhausting the client stack.
        if (depth > 32) { paragraph(Component.literal("[Nesting limit reached]"), inset); return; }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) block(node, inset, depth);
    }

    private void block(Node node, int inset, int depth) {
        if (node instanceof Paragraph) {
            paragraph(inlines(node, Style.EMPTY, 0), inset);
            images(node, inset, 0);
            if (!(node.getParent() instanceof ListItem item && item.getParent() instanceof ListBlock list && list.isTight())) gap();
        } else if (node instanceof Heading heading) {
            gap();
            paragraph(inlines(node, Style.EMPTY.withBold(true).withColor(heading.getLevel() <= 2 ? 0xF1DCB4 : 0xE5D8BF), 0), inset);
            gap();
        } else if (node instanceof FencedCodeBlock code) {
            code(code.getLiteral(), code.getInfo().strip().split("\\s+", 2)[0], inset);
            if (code.getInfo().strip().equalsIgnoreCase("mermaid") && code.getClosingFenceLength()!=null)
                media("mermaid",code.getLiteral(),"Mermaid diagram",inset);
        } else if (node instanceof IndentedCodeBlock code) {
            code(code.getLiteral(), "code", inset);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            int ordinal = node instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : 0;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                int first = rows.size();
                blocks(child, Math.min(inset + 16, width / 2), depth + 1);
                String marker = node instanceof OrderedList ? ordinal++ + "." : "•";
                for (Node part = child.getFirstChild(); part != null; part = part.getNext())
                    if (part instanceof TaskListItemMarker task) marker = task.isChecked() ? "☑" : "☐";
                if (first < rows.size()) {
                    Row row = rows.get(first);
                    var runs = new ArrayList<Run>();
                    runs.add(new Run(-16, Component.literal(marker).getVisualOrderText()));
                    runs.addAll(row.runs());
                    rows.set(first, new Row(runs, row.inset(), marker + "\t" + row.copyText(), row.panel()));
                }
            }
            gap();
        } else if (node instanceof BlockQuote) {
            int first = rows.size();
            blocks(node, Math.min(inset + 12, width / 2), depth + 1);
            for (int i = first; i < rows.size(); i++) {
                var row = rows.get(i);
                var runs = new ArrayList<Run>();
                runs.add(new Run(inset - row.inset(), Component.literal("│").withStyle(s -> s.withColor(0x6B8C9D)).getVisualOrderText()));
                for (var run : row.runs()) runs.add(new Run(run.x() + 4, run.text()));
                rows.set(i, new Row(runs, row.inset(), "│\t" + row.copyText(), row.panel()));
            }
        } else if (node instanceof TableBlock) table(node, inset);
        else if (node instanceof ThematicBreak) {
            paragraph(Component.literal("─".repeat(Math.max(1, (width - inset) / Math.max(1, font.width("─"))))).withStyle(s -> s.withColor(0x58636A)), inset);
            gap();
        } else if (node instanceof HtmlBlock html) {
            paragraph(Component.literal(html.getLiteral()), inset);
        } else blocks(node, inset, depth + 1);
    }

    private void gap() {
        if (!rows.isEmpty() && !plain(rows.getLast().text()).isEmpty())
            rows.add(new Row(List.of(new Run(0, FormattedCharSequence.EMPTY)), 0, "\n", null));
    }

    private void images(Node parent, int inset, int depth) {
        if(depth>32)return;
        for(Node node=parent.getFirstChild();node!=null;node=node.getNext()) {
            if(node instanceof Image image) media("image",image.getDestination(),inlines(image,Style.EMPTY,0).getString(),inset);
            else images(node,inset,depth+1);
        }
    }

    private void media(String kind,String source,String alt,int inset) {
        int first=rows.size(), available=Math.max(40,width-inset);
        var size=images.size(kind,source,available,maxImageHeight);
        int count=(size.height()+LINE_HEIGHT-1)/LINE_HEIGHT;
        for(int i=0;i<count;i++)rows.add(new Row(List.of(new Run(0,FormattedCharSequence.EMPTY)),inset,i==count-1?"\n":"",null));
        media.add(new Media(first,rows.size(),inset,size.width(),size.height(),kind,source,alt));
    }

    static Layout image(Font font,String source,String alt,int width,ChatImages images,int maxImageHeight) {
        var layout=new ChatMarkdown(font,width,images,maxImageHeight);
        layout.media("image",source,alt,0);
        return new Layout(layout.rows,layout.panels,layout.media);
    }

    private void paragraph(Component component, int inset) {
        if (component.getString().isEmpty()) return;
        var wrapped = font.split(component, Math.max(24, width - inset));
        String raw = component.getString();
        int cursor = 0;
        for (int i = 0; i < wrapped.size(); i++) {
            var visual = wrapped.get(i);
            String visible = plain(visual);
            int end = Math.min(raw.length(), cursor + visible.length());
            // Minecraft drops a wrapping space/newline. Preserve it for cross-line copying.
            if (end < raw.length() && (raw.charAt(end) == ' ' || raw.charAt(end) == '\n')) end++;
            String copy = raw.substring(cursor, end);
            if (i == wrapped.size() - 1 && !copy.endsWith("\n")) copy += "\n";
            rows.add(new Row(List.of(new Run(0, visual)), inset, copy, null));
            cursor = end;
        }
    }

    private void code(String source, String language, int inset) {
        var panel = new Panel(rows.size(), inset, Math.max(40, width - inset), language.isBlank() ? "code" : language, source);
        panels.add(panel);
        rows.add(new Row(List.of(new Run(0, FormattedCharSequence.EMPTY)), inset, "", panel));
        String[] lines = source.split("\n", -1);
        int count = lines.length - (source.endsWith("\n") ? 1 : 0);
        for (int i = 0; i < Math.max(1, count); i++) {
            String line = lines[i];
            var visual = FormattedCharSequence.forward(expandTabs(line), CODE);
            panel.contentWidth = Math.max(panel.contentWidth, font.width(visual) + 12);
            rows.add(new Row(List.of(new Run(6, visual)), inset, expandTabs(line) + (i < lines.length - 1 ? "\n" : ""), panel));
        }
        rows.add(new Row(List.of(new Run(0, FormattedCharSequence.EMPTY)), inset, "\n", panel));
        panel.end = rows.size();
        gap();
    }

    private void table(Node table, int inset) {
        var cells = new ArrayList<List<TableCell>>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext())
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                var columns = new ArrayList<TableCell>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) if (cell instanceof TableCell c) columns.add(c);
                if (!columns.isEmpty()) cells.add(columns);
            }
        if (cells.isEmpty()) return;
        int count = cells.stream().mapToInt(List::size).max().orElse(1);
        int[] widths = new int[count];
        var source = new StringBuilder();
        for (var row : cells) {
            for (int i = 0; i < row.size(); i++) {
                Component text = inlines(row.get(i), row.get(i).isHeader() ? Style.EMPTY.withBold(true) : Style.EMPTY, 0);
                widths[i] = Math.max(widths[i], Math.clamp(font.width(text) + 16, 48, 220));
                if (i > 0) source.append('\t');
                source.append(text.getString());
            }
            source.append('\n');
        }
        var panel = new Panel(rows.size(), inset, Math.max(40, width - inset), "table", source.toString());
        panels.add(panel);
        rows.add(new Row(List.of(new Run(0, FormattedCharSequence.EMPTY)), inset, "", panel));
        int x = 0;
        for (int columnWidth : widths) { panel.columns.add(x); x += columnWidth; }
        panel.columns.add(x);
        panel.contentWidth = x;
        for (var row : cells) {
            var wrapped = new ArrayList<List<FormattedCharSequence>>();
            int height = 1;
            for (int i = 0; i < row.size(); i++) {
                var cell = row.get(i);
                var text = inlines(cell, cell.isHeader() ? Style.EMPTY.withBold(true).withColor(0xF1DCB4) : Style.EMPTY, 0);
                var lines = font.split(text, widths[i] - 16);
                wrapped.add(lines); height = Math.max(height, lines.size());
            }
            for (int line = 0; line < height; line++) {
                var runs = new ArrayList<Run>();
                var copy = new StringBuilder();
                for (int col = 0; col < row.size(); col++) {
                    var text = line < wrapped.get(col).size() ? wrapped.get(col).get(line) : FormattedCharSequence.EMPTY;
                    var alignment = row.get(col).getAlignment();
                    int padding = alignment == TableCell.Alignment.RIGHT ? widths[col] - 8 - font.width(text)
                        : alignment == TableCell.Alignment.CENTER ? (widths[col] - font.width(text)) / 2 : 8;
                    runs.add(new Run(panel.columns.get(col) + padding, text));
                    if (col > 0) copy.append('\t');
                    copy.append(plain(text));
                }
                rows.add(new Row(runs, inset, copy + "\n", panel));
            }
        }
        rows.add(new Row(List.of(new Run(0, FormattedCharSequence.EMPTY)), inset, "\n", panel));
        panel.end = rows.size();
        gap();
    }

    private static MutableComponent inlines(Node parent, Style style, int depth) {
        var result = Component.empty();
        if (depth > 64) return result.append("[Nesting limit reached]");
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text text) result.append(Component.literal(text.getLiteral()).withStyle(style));
            else if (node instanceof Code code) result.append(Component.literal(code.getLiteral()).withStyle(CODE.applyTo(style)));
            else if (node instanceof SoftLineBreak) result.append(Component.literal(" ").withStyle(style));
            else if (node instanceof HardLineBreak) result.append(Component.literal("\n").withStyle(style));
            else if (node instanceof HtmlInline html) result.append(Component.literal(html.getLiteral()).withStyle(style));
            else if (node instanceof TaskListItemMarker task) result.append(Component.literal(task.isChecked() ? "☑ " : "☐ ").withStyle(style));
            else if (node instanceof Image) continue;
            else {
                Style next = node instanceof StrongEmphasis ? style.withBold(true)
                    : node instanceof Emphasis ? style.withItalic(true)
                    : node instanceof Strikethrough ? style.withStrikethrough(true)
                    : node instanceof Link link ? linkStyle(style, link.getDestination()) : style;
                result.append(inlines(node, next, depth + 1));
            }
        }
        return result;
    }

    private static Style linkStyle(Style style, String destination) {
        // Only OPEN_URL is emitted. The screen routes workspace paths separately.
        return style.withColor(0x96C9DB).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, destination));
    }

    static String plain(FormattedCharSequence sequence) {
        var result = new StringBuilder();
        sequence.accept((index, style, codePoint) -> { result.appendCodePoint(codePoint); return true; });
        return result.toString();
    }

    static int prefixWidth(Font font, FormattedCharSequence text, int character) {
        int[] cursor = {0};
        FormattedCharSequence prefix = sink -> text.accept((index, style, codePoint) -> {
            if (cursor[0] >= character) return false;
            cursor[0] += Character.charCount(codePoint);
            return sink.accept(index, style, codePoint);
        });
        return font.width(prefix);
    }

    private static String expandTabs(String line) {
        var expanded = new StringBuilder();
        int column = 0;
        for (int cp : line.codePoints().toArray()) {
            if (cp == '\t') { int spaces = 4 - column % 4; expanded.append(" ".repeat(spaces)); column += spaces; }
            else { expanded.appendCodePoint(cp); column++; }
        }
        return expanded.toString();
    }
}
