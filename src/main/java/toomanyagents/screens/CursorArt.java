package toomanyagents.screens;

import java.awt.Cursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** The page's cursor, drawn in place of the crosshair: '#' outline, '.' fill. */
final class CursorArt {
    private record Art(String[] rows, int hotX, int hotY) {}

    private static final Art ARROW = new Art(new String[] {
        "#",
        "##",
        "#.#",
        "#..#",
        "#...#",
        "#....#",
        "#.....#",
        "#......#",
        "#.......#",
        "#........#",
        "#.....#####",
        "#..#..#",
        "#.# #..#",
        "##  #..#",
        "#    #..#",
        "     #..#",
        "      ##",
    }, 0, 0);

    private static final Art HAND = new Art(new String[] {
        "     ##",
        "    #..#",
        "    #..#",
        "    #..#",
        "    #..###",
        "    #..#..##",
        "    #..#..#.##",
        " ## #..#..#..#",
        "#..##........#",
        "#...#........#",
        " #...........#",
        "  #..........#",
        "  #.........#",
        "   #........#",
        "    #......#",
        "    ########",
    }, 5, 0);

    private static final Art TEXT = new Art(new String[] {
        "### ###",
        "#..#..#",
        " ##.## ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        "  #.#  ",
        " ##.## ",
        "#..#..#",
        "### ###",
    }, 3, 7);

    private static final Art CROSS = new Art(new String[] {
        "   ###   ",
        "   #.#   ",
        "   #.#   ",
        "####.####",
        "#.......#",
        "####.####",
        "   #.#   ",
        "   #.#   ",
        "   ###   ",
    }, 4, 4);

    private CursorArt() {}

    static void draw(GuiGraphics graphics, int shape, int x, int y) {
        var art = switch (shape) {
            case Cursor.HAND_CURSOR -> HAND;
            case Cursor.TEXT_CURSOR -> TEXT;
            case Cursor.DEFAULT_CURSOR -> ARROW;
            default -> CROSS;
        };
        // About two screen pixels per art pixel at common GUI scales.
        float pixel = Minecraft.getInstance().getWindow().getGuiScale() >= 3 ? 0.5f : 1f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x - art.hotX * pixel, y - art.hotY * pixel, 0);
        pose.scale(pixel, pixel, 1);
        for (int row = 0; row < art.rows.length; row++) {
            String line = art.rows[row];
            for (int column = 0; column < line.length(); column++) {
                char c = line.charAt(column);
                if (c == '#') graphics.fill(column, row, column + 1, row + 1, 0xFF101114);
                else if (c == '.') graphics.fill(column, row, column + 1, row + 1, 0xFFFFFFFF);
            }
        }
        pose.popPose();
    }
}
