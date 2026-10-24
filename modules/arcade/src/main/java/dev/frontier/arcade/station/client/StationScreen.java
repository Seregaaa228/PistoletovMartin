package dev.frontier.arcade.station.client;

import dev.frontier.arcade.control.ControlSessions.DroneEntry;
import dev.frontier.arcade.station.StationMenu;
import dev.frontier.arcade.station.StationNetwork;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** One selection and one connect action, with normal Minecraft-sized text throughout. */
public final class StationScreen extends AbstractContainerScreen<StationMenu> {
    private static final int BACK = 0xFF172125, SURFACE = 0xFF202E32, ROW = 0xFF1C282C;
    private static final int SELECTED = 0xFF30463F, HOVER = 0xFF2B3B3E, LINE = 0xFF3B4D50;
    private static final int TEXT = 0xFFE4EEEB, MUTED = 0xFF9AAEAF, ACCENT = 0xFF9DD5BF;
    private static final int WARNING = 0xFFFFCA79, DANGER = 0xFFFF8E82;
    private UUID selected;
    private int scroll, visibleRows, rowHeight, listTop;
    private boolean compact;
    private StationButton connect;

    public StationScreen(StationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        inventoryLabelY = -999;
        selected = menu.entries().stream().filter(DroneEntry::online).map(DroneEntry::id).findFirst()
            .orElse(menu.entries().isEmpty() ? null : menu.entries().get(0).id());
    }

    @Override protected void init() {
        compact = height < 260 || width < 380;
        rowHeight = compact ? 22 : 28;
        listTop = compact ? 37 : 55;
        imageWidth = Math.min(430, width - 16);
        visibleRows = Math.max(1, Math.min(8, (height - 16 - listTop - 40) / rowHeight));
        imageHeight = listTop + visibleRows * rowHeight + 40;
        super.init();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, menu.entries().size() - visibleRows)));
        for (int index = 0; index < menu.entries().size(); index++) {
            if (!menu.entries().get(index).id().equals(selected)) continue;
            if (index < scroll) scroll = index;
            else if (index >= scroll + visibleRows) scroll = index - visibleRows + 1;
            break;
        }
        int buttonY = topPos + imageHeight - 30;
        int secondary = compact ? 76 : 87, gap = 6;
        int connectWidth = imageWidth - 24 - secondary * 2 - gap * 2;
        connect = addRenderableWidget(new StationButton(leftPos + 12, buttonY, connectWidth, 20,
            text("connect"), ignored -> connectSelected(), true));
        addRenderableWidget(new StationButton(leftPos + 12 + connectWidth + gap, buttonY, secondary, 20,
            text("refresh"), ignored -> StationNetwork.refresh(menu), false));
        addRenderableWidget(new StationButton(leftPos + imageWidth - 12 - secondary, buttonY, secondary, 20,
            text("close"), ignored -> onClose(), false));
        updateConnect();
    }

    private static Component text(String suffix) { return Component.translatable("gui.frontier_arcade_vehicles.station." + suffix); }

    private DroneEntry selectedEntry() {
        return selected == null ? null : menu.entries().stream().filter(entry -> selected.equals(entry.id())).findFirst().orElse(null);
    }

    private void updateConnect() {
        var entry = selectedEntry();
        connect.active = entry != null && entry.online();
    }

    private void connectSelected() {
        var entry = selectedEntry();
        if (entry == null || !connect.active) return;
        StationNetwork.connect(menu, entry.id());
        // Avoid repeated clicks while the authoritative connection or refreshed list is arriving.
        connect.active = false;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partial);
    }

    @Override protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
        int x = leftPos, y = topPos, right = x + imageWidth;
        graphics.fill(x - 1, y - 1, right + 1, y + imageHeight + 1, LINE);
        graphics.fill(x, y, right, y + imageHeight, BACK);
        graphics.fill(x, y, right, y + 2, ACCENT);
        graphics.fill(x + 12, y + 13, x + 15, y + 25, ACCENT);
        graphics.drawString(font, text("title"), x + 22, y + 15, TEXT, false);
        long available = menu.entries().stream().filter(DroneEntry::online).count();
        Component count = Component.translatable("gui.frontier_arcade_vehicles.station.count", available, menu.entries().size());
        graphics.drawString(font, count, right - 12 - font.width(count), y + 15, ACCENT, false);
        if (!compact) {
            graphics.drawString(font, text("subtitle"), x + 12, y + 34, MUTED, false);
            graphics.fill(x + 12, y + 47, right - 12, y + 48, LINE);
        }

        int listY = y + listTop;
        if (menu.entries().isEmpty()) {
            graphics.fill(x + 12, listY, right - 12, listY + visibleRows * rowHeight, ROW);
            centered(graphics, text("empty"), x + imageWidth / 2, listY + visibleRows * rowHeight / 2 - 9, TEXT);
            if (imageWidth > 330) centered(graphics, text("empty_hint"), x + imageWidth / 2,
                listY + visibleRows * rowHeight / 2 + 5, MUTED);
        } else {
            for (int slot = 0; slot < visibleRows && slot + scroll < menu.entries().size(); slot++) {
                var entry = menu.entries().get(scroll + slot);
                renderRow(graphics, entry, x + 12, listY + slot * rowHeight, imageWidth - 24,
                    mouseX, mouseY);
            }
            if (menu.entries().size() > visibleRows) {
                int totalHeight = visibleRows * rowHeight - 2;
                int thumb = Math.max(12, totalHeight * visibleRows / menu.entries().size());
                int thumbY = listY + (totalHeight - thumb) * scroll / Math.max(1, menu.entries().size() - visibleRows);
                graphics.fill(right - 7, listY, right - 5, listY + totalHeight, LINE);
                graphics.fill(right - 7, thumbY, right - 5, thumbY + thumb, MUTED);
            }
        }
    }

    private void renderRow(GuiGraphics graphics, DroneEntry entry, int x, int y, int rowWidth, int mouseX, int mouseY) {
        boolean chosen = entry.id().equals(selected);
        boolean hovered = mouseX >= x && mouseX < x + rowWidth && mouseY >= y && mouseY < y + rowHeight - 2;
        graphics.fill(x, y, x + rowWidth, y + rowHeight - 2, chosen ? SELECTED : hovered ? HOVER : ROW);
        if (chosen) graphics.fill(x, y, x + 2, y + rowHeight - 2, ACCENT);
        int top = y + (compact ? 2 : 4), second = top + (compact ? 10 : 11);
        int rightWidth = compact ? 128 : 136;
        String name = entry.name().isBlank() ? kindName(entry.kind()) : entry.name();
        graphics.drawString(font, clip(name, rowWidth - rightWidth - 15), x + 8, top, entry.online() ? TEXT : MUTED, false);
        graphics.drawString(font, clip(kindName(entry.kind()), rowWidth - rightWidth - 15), x + 8, second, MUTED, false);
        String distance = Component.translatable("gui.frontier_arcade_vehicles.station.distance", Math.round(entry.distance())).getString();
        graphics.drawString(font, distance, x + rowWidth - 8 - font.width(distance), top, MUTED, false);
        String telemetry = entry.online() ? Component.translatable("gui.frontier_arcade_vehicles.station.telemetry",
            entry.battery(), entry.signal()).getString() : text("offline").getString();
        int color = !entry.online() ? MUTED : entry.signal() <= 20 || entry.battery() <= 15 ? DANGER
            : entry.signal() <= 45 ? WARNING : ACCENT;
        graphics.drawString(font, telemetry, x + rowWidth - 8 - font.width(telemetry), second, color, false);
    }

    private String clip(String value, int available) {
        return font.width(value) <= available ? value : font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
    }

    private static String kindName(String kind) {
        String normalized = kind.toLowerCase(Locale.ROOT);
        if (normalized.contains("relay") || normalized.contains("ретранс")) return text("kind_relay").getString();
        if (normalized.contains("geranium")) return text("kind_geranium").getString();
        if (normalized.contains("mavic") || normalized.contains("recon")) return text("kind_recon").getString();
        return kind;
    }

    private void centered(GuiGraphics graphics, Component value, int center, int y, int color) {
        graphics.drawString(font, value, center - font.width(value) / 2, y, color, false);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= leftPos + 12 && mouseX < leftPos + imageWidth - 12
            && mouseY >= topPos + listTop && mouseY < topPos + listTop + visibleRows * rowHeight) {
            int index = scroll + (int) (mouseY - topPos - listTop) / rowHeight;
            if (index < menu.entries().size()) { selected = menu.entries().get(index).id(); updateConnect(); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= leftPos && mouseX < leftPos + imageWidth && mouseY >= topPos + listTop
            && mouseY < topPos + listTop + visibleRows * rowHeight) {
            scroll = Math.max(0, Math.min(Math.max(0, menu.entries().size() - visibleRows), scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && !menu.entries().isEmpty()) {
            int current = 0;
            for (int i = 0; i < menu.entries().size(); i++) if (menu.entries().get(i).id().equals(selected)) current = i;
            int next = Math.max(0, Math.min(menu.entries().size() - 1, current + (key == GLFW.GLFW_KEY_UP ? -1 : 1)));
            selected = menu.entries().get(next).id();
            if (next < scroll) scroll = next;
            if (next >= scroll + visibleRows) scroll = next - visibleRows + 1;
            updateConnect(); return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !(getFocused() instanceof Button) && connect.active) {
            connectSelected(); return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private final class StationButton extends Button {
        private final boolean primary;
        private StationButton(int x, int y, int width, int height, Component label, OnPress action, boolean primary) {
            super(x, y, width, height, label, action, DEFAULT_NARRATION);
            this.primary = primary;
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY();
            graphics.fill(x, y, x + getWidth(), y + getHeight(), !active ? SURFACE : isHoveredOrFocused() ? HOVER : primary ? SELECTED : SURFACE);
            graphics.fill(x, y + getHeight() - 1, x + getWidth(), y + getHeight(), active && primary ? ACCENT : LINE);
            int color = active ? primary ? ACCENT : TEXT : 0xFF697C7D;
            String label = clip(getMessage().getString(), getWidth() - 8);
            graphics.drawString(font, label, x + (getWidth() - font.width(label)) / 2, y + (getHeight() - 8) / 2, color, false);
        }
    }
}
