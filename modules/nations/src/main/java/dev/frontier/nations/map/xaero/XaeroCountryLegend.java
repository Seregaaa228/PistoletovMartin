package dev.frontier.nations.map.xaero;

import dev.frontier.nations.map.client.PoliticalMapSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Compact collapsible country list replacing the wide fallback sidebar.
 * Drawn next to the HUD (minimap companion) and on top of the Xaero world map.
 */
public final class XaeroCountryLegend {
    private static final int PANEL = 0xCC202E36;
    private static final int TEXT = 0xFFE9E8D8;
    private static final int MUTED = 0xFFA6B5BC;
    private static volatile boolean collapsed;

    private XaeroCountryLegend() {}

    public static boolean isCollapsed() {
        return collapsed;
    }

    public static boolean toggleCollapsed() {
        collapsed = !collapsed;
        return collapsed;
    }

    public static void renderHud(GuiGraphics graphics, PoliticalMapSnapshot snapshot) {
        Font font = Minecraft.getInstance().font;
        if (collapsed) {
            String line = "Страны: " + snapshot.countries.size() + " • T — слой • M-карта + Tab — список";
            graphics.fill(8, 8, 8 + font.width(line) + 12, 24, PANEL);
            graphics.drawString(font, line, 12, 13, MUTED, false);
            return;
        }
        int rows = Math.min(5, snapshot.countries.size());
        int bottom = 12 + 12 + rows * 12 + 12;
        graphics.fill(8, 8, 168, bottom, PANEL);
        graphics.drawString(font, "Страны • T — слой", 14, 12, TEXT, false);
        int y = 26;
        for (int i = 0; i < rows; i++) {
            PoliticalMapSnapshot.Country country = snapshot.countries.get(i);
            graphics.fill(14, y, 21, y + 7, 0xFF000000 | (country.color() & 0x00FFFFFF));
            graphics.drawString(font,
                    font.plainSubstrByWidth(country.name() + " · " + country.chunks(), 130),
                    25, y - 1, TEXT, false);
            y += 12;
        }
        String more = snapshot.countries.size() > rows
                ? "+" + (snapshot.countries.size() - rows) + " на M-карте"
                : "M — карта • Tab — свернуть";
        graphics.drawString(font, more, 14, y, MUTED, false);
    }

    public static void renderOnMap(GuiGraphics graphics, Screen screen, PoliticalMapSnapshot snapshot) {
        Font font = Minecraft.getInstance().font;
        if (collapsed) {
            String line = "Страны: " + snapshot.countries.size() + " • Tab — список • T — слой";
            graphics.fill(8, 8, 8 + font.width(line) + 12, 24, PANEL);
            graphics.drawString(font, line, 12, 13, TEXT, false);
            return;
        }
        int rows = Math.min(8, snapshot.countries.size());
        int bottom = 12 + 12 + rows * 12 + 12;
        if (bottom > screen.height - 8) {
            rows = Math.max(1, (screen.height - 44) / 12);
            bottom = 12 + 12 + rows * 12 + 12;
        }
        graphics.fill(8, 8, 172, bottom, PANEL);
        graphics.drawString(font, "Страны • Tab — свернуть", 14, 12, TEXT, false);
        int y = 26;
        for (int i = 0; i < rows && i < snapshot.countries.size(); i++) {
            PoliticalMapSnapshot.Country country = snapshot.countries.get(i);
            graphics.fill(14, y, 21, y + 7, 0xFF000000 | (country.color() & 0x00FFFFFF));
            graphics.drawString(font,
                    font.plainSubstrByWidth(country.name() + " · " + country.chunks(), 134),
                    25, y - 1, TEXT, false);
            y += 12;
        }
        graphics.drawString(font, "T — слой территорий", 14, y, MUTED, false);
    }
}
