package dev.frontier.nations.flag;

import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.world.RecruitsFaction;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
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
    private static final int BACKGROUND = 0xFF171B20;
    private static final int PANEL = 0xFF242C34;
    private static final int TEXT = 0xFFE7EBEF;
    private static final int MUTED = 0xFFAAB4BE;

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
    private final List<Button> editButtons=new ArrayList<>();
    private Button closeButton;
    private FlagDefinition awaiting;
    private long sentAt;
    private String status="";

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
        editButtons.clear();
        int sideWidth = canEdit ? 120 : 0;
        int heightCell = Math.max(3, (height - 98) / 17);
        int widthCell = Math.max(3, (width - sideWidth - 40) / CANVAS);
        cell = Math.min(16, Math.min(heightCell, widthCell));
        int canvasPixels = cell * CANVAS;
        int totalWidth = canvasPixels + (canEdit ? sideWidth + 16 : 0);
        originX = Math.max(8, (width - totalWidth) / 2);
        originY = 50;
        if (canEdit) {
            FlagPreset[] presets = {FlagPreset.HORIZONTAL_TRICOLOR, FlagPreset.NORDIC_CROSS,
                    FlagPreset.SUN_DISC, FlagPreset.DIAGONAL, FlagPreset.CHEVRON};
            String[] labels = {"Триколор", "Крест", "Круг / солнце", "Диагональ", "Шеврон"};
            for (int index = 0; index < presets.length; index++) {
                FlagPreset value = presets[index];
                editButtons.add(addRenderableWidget(Button.builder(Component.literal(labels[index]), b -> applyPreset(value))
                        .bounds(originX + canvasPixels + 16, originY + index * 20, sideWidth, 16).build()));
            }
            editButtons.add(addRenderableWidget(Button.builder(Component.literal("Сохранить"), b -> save())
                    .bounds(width / 2 - 124, height - 26, 120, 20).build()));
        }
        closeButton=addRenderableWidget(Button.builder(Component.literal(canEdit ? "Отмена" : "Закрыть"), b -> onClose())
                .bounds(canEdit ? width / 2 + 4 : width / 2 - 60, height - 26, 120, 20).build());
        updateButtons();
    }

    private boolean hasAuthority(){
        var own=ClientManager.ownFaction;
        return canEdit&&own!=null&&factionId!=null&&factionId.equals(own.getStringID())&&FlagLeadership.isLocalLeader(own);
    }
    private boolean editable(){return awaiting==null&&hasAuthority();}
    private void updateButtons(){
        for(Button button:editButtons)button.active=editable();
        if(closeButton!=null)closeButton.setMessage(Component.literal(awaiting!=null?"← Карта":canEdit?"Отмена":"Закрыть"));
    }
    @Override public void tick(){
        if(awaiting!=null){
            if(awaiting.equals(FlagCache.flag(factionId))){onClose();return;}
            if(!hasAuthority()){awaiting=null;status="Права изменились. Рисунок не отправляется повторно";}
            else if(System.nanoTime()-sentAt>10_000_000_000L){awaiting=null;status="Сервер не подтвердил флаг. Рисунок сохранён в редакторе";}
        }
        updateButtons();
    }

    private void applyPreset(FlagPreset next) {
        if(!editable())return;
        preset = next;
        byte[] built = FlagPresets.build(next, presetPrimary, presetSecondary, presetAccent).pixels();
        System.arraycopy(built, 0, pixels, 0, FlagCodec.PIXELS);
    }

    private void save() {
        if (!editable()) return;
        FlagDefinition proposed=new FlagDefinition(Arrays.copyOf(pixels,pixels.length));
        if(proposed.equals(FlagCache.flag(factionId))){onClose();return;}
        awaiting=proposed;sentAt=System.nanoTime();status="Флаг отправлен. Выход не отменяет сохранение";updateButtons();
        try{FlagNet.sendToServer(factionId,proposed);}
        catch(RuntimeException unavailable){awaiting=null;status="Не удалось отправить флаг. Рисунок остаётся в редакторе";updateButtons();}
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.drawString(font, "Флаг: " + factionName, 12, 12, TEXT, false);
        graphics.drawString(font, hasAuthority() ? "ЛКМ — рисовать, ПКМ — ластик (цвет 0)"
                : "Только просмотр: флаг меняет лидер страны", 12, 26, MUTED, false);
        if(!status.isEmpty())graphics.drawString(font,font.plainSubstrByWidth(status,width-24),12,37,MUTED,false);
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
        if (editable() && mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= paletteY && mouseY < paletteY + paletteSize) {
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
            if (x >= 0 && x < CANVAS && y >= 0 && y < CANVAS && editable()) {
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
        if (editable() && mouseX >= originX && mouseX < originX + canvasPixels && mouseY >= originY && mouseY < originY + canvasPixels
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
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
