package dev.frontier.industry.client.ui;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Server labels are literal text, never translation format strings. LDLib 1.x
 * TextTexture.updateText localizes its argument, so a visible percent sign can
 * become "Format error". Its public text/width fields and setWidth() let us
 * retain the original hide/roll renderer and font splitting without that step.
 */
public final class LiteralTextTexture extends TextTexture {
    public LiteralTextTexture(String value, int color) {
        super("", color);
        updateText(value);
    }
    public LiteralTextTexture(Supplier<String> value, int color) {
        this(value.get(), color);
        setSupplier(value);
    }
    @Override public void updateText(String value) {
        text = Objects.requireNonNullElse(value, "");
        setWidth(width);
    }
}
