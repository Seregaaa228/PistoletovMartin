package dev.frontier.industry.client.ui;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Hosts genuine LDLib widgets inside the existing Forge screen. Its local LDLib
 * container is only a focus/tooltip context: it is never installed on the player,
 * contains no slots and never sends LDLib actions. The real FactoryMenu and the
 * Industry/Operations channels retain all inventory and server authority.
 */
public final class LdlibWidgetHost {
    private final WidgetGroup root;
    private final ModularUIGuiContainer context;
    private boolean clicking;
    private Runnable deferred;

    public LdlibWidgetHost(int width, int height) {
        root = new WidgetGroup(0, 0, width, height).setClientSideWidget();
        var ui = new ModularUI(root, IUIHolder.EMPTY, Minecraft.getInstance().player);
        context = new ModularUIGuiContainer(ui, 0);
        ui.updateScreenSize(width, height);
        ui.initWidgets();
    }

    public Widget find(String id) { return root.getFirstWidgetById("^" + java.util.regex.Pattern.quote(id) + "$"); }
    public <T extends Widget> T add(T widget) { root.addWidget(widget); return widget; }
    public void clear() { context.lastFocus = null; root.clearAllWidgets(); }
    public void tick() { root.updateScreen(); }
    public boolean hasTextFocus() { return context.lastFocus instanceof com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget; }
    public void clearFocus() { if (context.lastFocus != null) context.lastFocus.setFocus(false); }

    /** Rebuilding a tree from inside WidgetGroup's event iterator would invalidate that iterator. */
    public void afterInput(Runnable action) { if (clicking) deferred = action; else action.run(); }
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        context.tooltipTexts = null;
        root.drawInBackground(graphics, mouseX, mouseY, partial);
        root.drawInForeground(graphics, mouseX, mouseY, partial);
    }
    public void tooltip(GuiGraphics graphics, int mouseX, int mouseY, int left, int top, int width, int height) {
        if (context.tooltipTexts == null || context.tooltipTexts.isEmpty()) return;
        var bounds = new TooltipBounds(left, top, width, height);
        var font = Minecraft.getInstance().font;
        var lines = context.tooltipTexts.stream().flatMap(text -> font.split(text, bounds.wrapWidth()).stream()).toList();
        graphics.renderTooltip(font, lines, (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> {
            var position = bounds.position(x, y, tooltipWidth, tooltipHeight);
            return new org.joml.Vector2i(position.x(), position.y());
        }, mouseX, mouseY);
    }
    public boolean mouseClicked(double x, double y, int button) {
        context.focused = false;
        clicking = true;
        boolean handled;
        try { handled = root.mouseClicked(x, y, button); }
        finally { clicking = false; }
        Runnable next = deferred; deferred = null;
        if (next != null) next.run();
        return handled;
    }
    public boolean mouseReleased(double x, double y, int button) { context.focused = false; return root.mouseReleased(x, y, button); }
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) { context.focused = false; return root.mouseDragged(x, y, button, dx, dy); }
    public boolean mouseScrolled(double x, double y, double delta) { return root.mouseWheelMove(x, y, delta); }
    public boolean keyPressed(int key, int scan, int modifiers) { return root.keyPressed(key, scan, modifiers); }
    public boolean keyReleased(int key, int scan, int modifiers) { return root.keyReleased(key, scan, modifiers); }
    public boolean charTyped(char value, int modifiers) { return root.charTyped(value, modifiers); }
    public void mouseMoved(double x, double y) { root.mouseMoved(x, y); }
}
