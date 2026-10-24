package dev.frontier.arcade.geranium.client;

import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import dev.frontier.arcade.geranium.GeraniumEntity;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

/** A quiet flight view: fixed-airframe attitude and the few values needed to fly and return to control. */
public final class GeraniumFlightHud {
    private static final int PANEL = 0xC010191D, TEXT = 0xFFE4EEEB, MUTED = 0xFF9AAEAF;
    private static final int ACCENT = 0xFF9DD5BF, WARNING = 0xFFFFCA79, DANGER = 0xFFFF8E82;

    private GeraniumFlightHud() { }

    public static void render(GuiGraphics graphics, float partial, GeraniumEntity target, KeyMapping stabilize) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        // Vanilla's smallest usual scaled viewport is 320x180. Narrower custom viewports use the same fitted text.
        int edge = width < 380 ? 7 : 12;
        int innerWidth = width - edge * 2;
        boolean compact = width < 440 || height < 240;
        int centerX = width / 2;
        int centerY = height / 2 - (compact ? 1 : 4);

        int battery = Mth.clamp(Math.round(100f * target.getEnergy() / GeraniumEntity.BATTERY), 0, 100);
        int signal = Mth.clamp((int) Math.round(100 * target.getSignalQuality()), 0, 100);
        double speed = Math.max(0, target.flightSpeed()) * 72;
        double range = Math.max(0, target.flightRange());
        double limit = Math.max(1, target.signalRange());
        boolean relayed = dev.frontier.arcade.relay.RelayClient.hopCount(target.getUUID()) > 0;
        int throttle = Mth.clamp(target.throttlePercent(), 0, 100);
        boolean lost = target.isSignalLost() || signal <= 0;

        graphics.fill(edge, edge, width - edge, edge + 22, PANEL);
        graphics.fill(edge, edge, edge + 2, edge + 22, ACCENT);
        graphics.drawString(mc.font, "ГЕРАНЬ-2", edge + 8, edge + 7, TEXT, false);
        String phase = lost ? "НЕТ СВЯЗИ" : battery == 0 ? "НЕТ ПИТАНИЯ"
            : !target.isLaunched() ? "ГОТОВ К ПУСКУ" : speed < 32 ? "РАЗГОН" : "ПОЛЁТ";
        int phaseColor = lost || battery == 0 ? DANGER : target.isLaunched() ? ACCENT : WARNING;
        graphics.drawString(mc.font, phase, width - edge - 8 - mc.font.width(phase), edge + 7, phaseColor, false);

        int meterY = edge + 29;
        int meterWidth = Math.min(103, Math.max(70, (innerWidth - 92) / 2));
        meter(graphics, edge, meterY, meterWidth, "АКБ", battery, battery <= 15 ? DANGER : ACCENT);
        meter(graphics, width - edge - meterWidth, meterY, meterWidth, "СВЯЗЬ", signal,
            signal <= 20 ? DANGER : signal <= 45 ? WARNING : ACCENT);
        int heading = Math.floorMod(Math.round(target.getYaw(partial)), 360);
        // Minecraft yaw: 0=south, 90=west, 180=north, 270=east.
        int bearing = Math.floorMod(heading + 180, 360);
        fittedCentered(graphics, String.format(Locale.ROOT, "%03d° %s", bearing, cardinal(bearing)), centerX,
            meterY + 2, Math.max(48, innerWidth - meterWidth * 2 - 8), TEXT);

        int metricY = centerY - 16;
        int metricWidth = compact ? 67 : 91;
        metric(graphics, edge, metricY, metricWidth, "СКОРОСТЬ", Math.round(speed) + " км/ч");
        String heightAboveGround = "—";
        if (target.level().hasChunkAt(target.blockPosition())) {
            int terrain = target.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.blockPosition().getX(), target.blockPosition().getZ());
            heightAboveGround = Math.round(Math.max(0, target.getY() - terrain)) + " м";
        }
        metric(graphics, width - edge - metricWidth, metricY, metricWidth, "ВЫСОТА", heightAboveGround);
        fittedCentered(graphics, "ГАЗ " + throttle + "%", edge + metricWidth / 2, metricY + 35, metricWidth, MUTED);
        fittedCentered(graphics, relayed ? Math.round(range) + " м" : Math.round(range) + " / " + Math.round(limit) + " м",
            width - edge - metricWidth / 2, metricY + 35, metricWidth, relayed ? (signal <= 20 ? WARNING : ACCENT) : range > limit * .8 ? WARNING : MUTED);
        if (!compact) {
            fittedCentered(graphics, "ОТ ОПЕРАТОРА", width - edge - metricWidth / 2, metricY + 48, metricWidth, MUTED);
        }

        attitude(graphics, centerX, centerY, target.getBodyPitch(partial), target.getRoll(partial), compact);
        int footerY = height - edge - 27;
        if (!target.isLaunched()) {
            String launch = keyName(ModKeyMappings.MOVE_SPACE) + " — ПУСК ВПЕРЁД";
            int launchWidth = Math.min(innerWidth, mc.font.width(launch) + 18);
            graphics.fill(centerX - launchWidth / 2, footerY - 19, centerX + launchWidth / 2, footerY - 4, PANEL);
            fittedCentered(graphics, launch, centerX, footerY - 15, launchWidth - 10, WARNING);
        } else if (signal <= 25 || (!relayed && range > limit * .85) || battery <= 15) {
            String warning = lost ? "СВЯЗЬ ПОТЕРЯНА" : battery <= 15 ? "НИЗКИЙ ЗАРЯД"
                : !relayed && range > limit * .85 ? "ПРЕДЕЛ ДАЛЬНОСТИ" : "СЛАБЫЙ СИГНАЛ";
            fittedCentered(graphics, warning, centerX, footerY - 15, innerWidth - 20, DANGER);
        }

        graphics.fill(edge, footerY, width - edge, height - edge, PANEL);
        String control = keyName(ModKeyMappings.MOVE_FORWARD) + "/" + keyName(ModKeyMappings.MOVE_BACKWARD)
            + " газ  ·  " + keyName(ModKeyMappings.MOVE_LEFT) + "/" + keyName(ModKeyMappings.MOVE_RIGHT)
            + " курс  ·  Мышь наклон";
        String actions = keyName(stabilize) + " ровно  ·  " + keyName(mc.options.keyUse)
            + " выход  ·  " + keyName(ModKeyMappings.FIRE) + " подрыв";
        fittedCentered(graphics, control, centerX, footerY + 4, innerWidth - 12, TEXT);
        fittedCentered(graphics, actions, centerX, footerY + 15, innerWidth - 12, MUTED);
    }

    private static void meter(GuiGraphics graphics, int x, int y, int width, String label, int percent, int color) {
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y - 2, x + width, y + 16, PANEL);
        graphics.drawString(font, label, x + 4, y + 1, MUTED, false);
        String value = percent + "%";
        graphics.drawString(font, value, x + width - 4 - font.width(value), y + 1, color, false);
        graphics.fill(x + 4, y + 12, x + width - 4, y + 13, 0xFF3B494C);
        graphics.fill(x + 4, y + 12, x + 4 + Math.round((width - 8) * percent / 100f), y + 13, color);
    }

    private static void metric(GuiGraphics graphics, int x, int y, int width, String label, String value) {
        graphics.fill(x, y - 3, x + width, y + 29, PANEL);
        fittedCentered(graphics, label, x + width / 2, y + 1, width - 8, MUTED);
        fittedCentered(graphics, value, x + width / 2, y + 15, width - 6, TEXT);
    }

    private static void attitude(GuiGraphics graphics, int centerX, int centerY, float pitch, float roll, boolean compact) {
        int half = compact ? 25 : 36;
        int shift = Math.round(Mth.clamp(pitch, -25, 25) * (compact ? .55f : .8f));
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY + shift, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-roll));
        graphics.fill(-half, 0, -9, 1, 0xC9C4D7D1);
        graphics.fill(10, 0, half + 1, 1, 0xC9C4D7D1);
        graphics.pose().popPose();
        // Only a small fixed nose marker, so the view remains clear during bank and descent.
        graphics.fill(centerX - 7, centerY, centerX - 2, centerY + 1, ACCENT);
        graphics.fill(centerX + 3, centerY, centerX + 8, centerY + 1, ACCENT);
        graphics.fill(centerX, centerY + 3, centerX + 1, centerY + 7, ACCENT);
    }

    private static String cardinal(int bearing) {
        return switch ((bearing + 22) / 45 % 8) {
            case 0 -> "С"; case 1 -> "СВ"; case 2 -> "В"; case 3 -> "ЮВ";
            case 4 -> "Ю"; case 5 -> "ЮЗ"; case 6 -> "З"; default -> "СЗ";
        };
    }

    private static void fittedCentered(GuiGraphics graphics, String text, int centerX, int y, int available, int color) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        float scale = Math.min(1f, Math.max(1, available) / (float) Math.max(1, textWidth));
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, -textWidth / 2, 0, color, false);
        graphics.pose().popPose();
    }

    private static String keyName(KeyMapping mapping) {
        var key = mapping.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return switch (key.getValue()) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> "ЛКМ";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> "ПКМ";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "СКМ";
                default -> "М" + (key.getValue() + 1);
            };
        }
        if (key.getValue() == GLFW.GLFW_KEY_SPACE) return "SPACE";
        return mapping.getTranslatedKeyMessage().getString();
    }
}
