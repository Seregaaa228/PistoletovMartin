package dev.frontier.industry.client.ui;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.ProgressWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Shared LDLib controls. Only callbacks send intentions through the pre-existing Industry channels. */
public final class IndustryWidgets {
    public static final int INK = 0xFFEAF1F8, MUTED = 0xFFA2B0BF, GREEN = 0xFF8AD5AE, GOLD = 0xFFF0CA82;
    private IndustryWidgets() {}

    public static final class ActionButton extends ButtonWidget {
        private String title;
        private final TextTexture label;
        private String reason = "";
        public ActionButton(int x, int y, int width, int height, String title, Runnable action) {
            super(x, y, width, height, data -> { if (data.button == 0) action.run(); });
            this.title = title;
            setId(title);
            label = new LiteralTextTexture(title, INK).setWidth(width - 8).setType(TextTexture.TextType.ROLL).setDropShadow(false);
            setButtonTexture(new GuiTextureGroup(new ColorRectTexture(0xFF314754), label));
            setHoverTexture(new ColorBorderTexture(1, 0xFF8AD5AE));
            setClickedTexture(new GuiTextureGroup(new ColorRectTexture(0xFF47685D), label));
            setHoverTooltips(Component.literal(title));
        }
        public void message(String value) { title = value; label.updateText(value); updateTooltip(); }
        public ActionButton enabled(boolean enabled, String reason) {
            setActive(enabled); this.reason = reason == null ? "" : reason;
            label.setColor(enabled ? INK : MUTED); updateTooltip(); return this;
        }
        private void updateTooltip() {
            if (reason.isBlank()) setHoverTooltips(Component.literal(title));
            else setHoverTooltips(Component.literal(title), Component.literal(reason));
        }
        @Override public boolean mouseClicked(double x, double y, int button) {
            return isActive() && button == 0 && super.mouseClicked(x, y, button);
        }
        // Disabled controls still explain why they cannot be used.
        @Override public void drawInForeground(GuiGraphics graphics, int x, int y, float partial) { drawTooltipTexts(x, y); }
    }

    public static ImageWidget text(int x, int y, int width, int height, Supplier<String> value, int color) {
        var texture = new LiteralTextTexture(value, color).setWidth(width).setType(TextTexture.TextType.LEFT_HIDE).setDropShadow(false);
        var widget = new ImageWidget(x, y, width, height, texture) {
            @Override public void updateScreen() { super.updateScreen(); setHoverTooltips(Component.literal(value.get())); }
        };
        widget.setHoverTooltips(Component.literal(value.get()));
        return widget;
    }
    public static ProgressWidget progress(int x, int y, int width, int height, DoubleSupplier value) {
        return new ProgressWidget(() -> Math.max(0, Math.min(1, value.getAsDouble())), x, y, width, height)
            .setProgressTexture(new ColorRectTexture(0xFF405261), new ColorRectTexture(0xFF80CEA9));
    }
    public static TextFieldWidget search(int x, int y, int width, Supplier<String> value, java.util.function.Consumer<String> change) {
        return new TextFieldWidget(x, y, width, 18, value, change).setMaxStringLength(128).setCurrentString(value.get());
    }
    public static Widget icon(int x, int y, Supplier<ItemStack> stack) {
        return new Widget(x, y, 16, 16) {
            @Override public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
                ItemStack item = stack.get();
                graphics.renderItem(item, getPositionX(), getPositionY());
                graphics.renderItemDecorations(Minecraft.getInstance().font, item, getPositionX(), getPositionY());
            }
        };
    }
    public static WidgetGroup row(int x, int y, int width, int height) { return new WidgetGroup(x, y, width, height); }
}
