package dev.frontier.nations.flag;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.world.RecruitsFaction;
import java.util.Arrays;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Flag sub-screen opened from the political map ("Мой флаг" section).
 * 16x16 canvas: left click paints, right click erases to the selected color's
 * complement slot; 16 dye colors; 5 preset buttons. Leaders may save, everyone
 * else gets a read-only preview of their own faction flag.
 */
public final class FlagEditorScreen extends Screen {
    private static final int CANVAS = 16;
    private static final int BACKGROUND = 0xFF17232B;
    private static final int PANEL = 0xFF202E36;
    private static final int TEXT = 0xFFE9E8D8;
    private static final int MUTED = 0xFFA6B5BC;

    private final Screen parent;
    private final String factionId;
    private final String factionName;
    private final boolean canEdit;
    private final byte[] pixels = new byte[FlagCodec.PIXELS];
    private int selected = 14;
    private int cell = 16;
    private int originX;
    private int originY;
    private FlagPreset preset = FlagPreset.HORIZONTAL_TRICOLOR;
    private int presetPrimary = 14;
    private int presetSecondary = 0;
    private int presetAccent = 11;

    public FlagEditorScreen(Screen parent, String factionId, String factionName, boolean canEdit) {
        super(Component.literal("Флаг страны"));
        this.parent = parent;
        this.factionId = factionId;
        this.factionName = factionName;
        this.canEdit = canEdit;
        FlagDefinition current = factionId == null ? null : FlagCache.flag(factionId);
        byte[] source = current == null ? FlagPresets.build(preset, presetPrimary, presetSecondary, presetAccent).pixels()
                : current.pixels();
        System.arraycopy(source, 0, pixels, 0, FlagCodec.PIXELS);
    }

    /** Entry point used by the map screen: resolves the visible player's own faction. */
    public static void openFromMap(Screen parent) {
        RecruitsFaction own = ClientManager.ownFaction;
        if (own == null) return;
        String id = own.getStringID();
        String name = own.getTeamDisplayName();
        boolean leader = FlagLeadership.isLocalLeader(own);
        net.minecraft.client.Minecraft.getInstance().setScreen(new FlagEditorScreen(parent, id,
                name == null || name.isBlank() ? id : name, leader));
    }

    @Override
    protected void init() {
        cell = Math.max(10, Math.min(20, (height - 190) / CANVAS));
        int canvasPixels = cell * CANVAS;
        originX = Math.max(12, width / 2 - canvasPixels - 120);
        originY = Math.max(48, 70);
        if (canEdit) {
            addRenderableWidget(Button.builder(Component.literal("Триколор"), b -> applyPreset(FlagPreset.HORIZONTAL_TRICOLOR))
                    .bounds(originX + canvasPixels + 16, originY, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Крест"), b -> applyPreset(FlagPreset.NORDIC_CROSS))
                    .bounds(originX + canvasPixels + 16, originY + 24, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Круг / солнце"), b -> applyPreset(FlagPreset.SUN_DISC))
                    .bounds(originX + canvasPixels + 16, originY + 48, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Диагональ"), b -> applyPreset(FlagPreset.DIAGONAL))
                    .bounds(originX + canvasPixels + 16, originY + 72, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Шеврон"), b -> applyPreset(FlagPreset.CHEVRON))
                    .bounds(originX + canvasPixels + 16, originY + 96, 148, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Сохранить"), b -> save())
                    .bounds(width / 2 - 158, height - 30, 150, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(canEdit ? "Отмена" : "Закрыть"), b -> onClose())
                .bounds(width / 2 + 8, height - 30, 150, 20).build());
    }

    private void applyPreset(FlagPreset next) {
        preset = next;
        byte[] built = FlagPresets.build(next, presetPrimary, presetSecondary, presetAccent).pixels();
        System.arraycopy(built, 0, pixels, 0, FlagCodec.PIXELS);
    }

    private void save() {
        if (!canEdit || factionId == null) return;
        FlagNet.sendToServer(factionId, new FlagDefinition(Arrays.copyOf(pixels, pixels.length)));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.drawString(font, "Флаг: " + factionName, 12, 12, TEXT, false);
        graphics.drawString(font, canEdit ? "ЛКМ — рисовать, ПКМ — ластик (цвет 0)"
                : "Только просмотр: флаг меняет лидер страны", 12, 26, MUTED, false);
        int canvasPixels = cell * CANVAS;
        graphics.fill(originX - 2, originY - 2, originX + canvasPixels + 2, originY + canvasPixels + 2, 0xFF0D1419);
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                graphics.fill(originX + x * cell, originY + y * cell,
                        originX + (x + 1) * cell, originY + (y + 1) * cell,
                        FlagPalette.color(pixels[y * CANVAS + x]));
            }
        }
        graphics.hLine(originX - 2, originX + canvasPixels + 1, originY - 2, 0xFFE2DDC8);
        graphics.hLine(originX - 2, originX + canvasPixels + 1, originY + canvasPixels + 1, 0xFFE2DDC8);
        graphics.vLine(originX - 2, originY - 2, originY + canvasPixels + 1, 0xFFE2DDC8);
        graphics.vLine(originX + canvasPixels + 1, originY - 2, originY + canvasPixels + 1, 0xFFE2DDC8);
        int paletteY = originY + canvasPixels + 10;
        for (int index = 0; index < FlagPalette.ARGB.length; index++) {
            int x = originX + index * (canvasPixels / 16);
            int size = canvasPixels / 16;
            graphics.fill(x, paletteY, x + size, paletteY + size, FlagPalette.color(index));
            if (index == selected) {
                graphics.hLine(x, x + size, paletteY, 0xFFFFDD7A);
                graphics.hLine(x, x + size, paletteY + size, 0xFFFFDD7A);
                graphics.vLine(x, paletteY, paletteY + size, 0xFFFFDD7A);
                graphics.vLine(x + size, paletteY, paletteY + size, 0xFFFFDD7A);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= paletteY && mouseY < paletteY + canvasPixels / 16) {
            int hovered = (mouseX - originX) / Math.max(1, canvasPixels / 16);
            if (hovered >= 0 && hovered < 16) graphics.renderTooltip(font, Component.literal(FlagPalette.name(hovered)), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        int canvasPixels = cell * CANVAS;
        int paletteSize = canvasPixels / 16;
        int paletteY = originY + canvasPixels + 10;
        if (canEdit && mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= paletteY && mouseY < paletteY + paletteSize) {
            int picked = (int) ((mouseX - originX) / Math.max(1, paletteSize));
            if (picked >= 0 && picked < 16) {
                selected = picked;
                presetPrimary = picked;
                presetSecondary = (picked + 8) & 0xF;
                return true;
            }
        }
        if (mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= originY && mouseY < originY + canvasPixels) {
            int x = (int) ((mouseX - originX) / cell);
            int y = (int) ((mouseY - originY) / cell);
            if (x >= 0 && x < CANVAS && y >= 0 && y < CANVAS && canEdit) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) pixels[y * CANVAS + x] = (byte) selected;
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) pixels[y * CANVAS + x] = 0;
                else return false;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        int canvasPixels = cell * CANVAS;
        if (canEdit && mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= originY && mouseY < originY + canvasPixels
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            int x = (int) ((mouseX - originX) / cell);
            int y = (int) ((mouseY - originY) / cell);
            if (x >= 0 && x < CANVAS && y >= 0 && y < CANVAS) {
                pixels[y * CANVAS + x] = (byte) (button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? selected : 0);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            if (parent instanceof PoliticalMapScreen) minecraft.setScreen(parent);
            else minecraft.setScreen((Screen) null);
            if (minecraft.screen == null && parent != null) minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
