package dev.frontier.aviation.control.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.control.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Small read-only flight status panel for the local pilot. */
public final class FlightHud {
    private static boolean registered;

    private FlightHud() {}

    public static void register() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(FlightHud.class);
            registered = true;
        }
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !FlightClient.isLocalPilot()) return;
        FlightTelemetry telemetry = FlightClient.telemetry();
        VehicleEntity vehicle = FlightClient.controlledEntity();
        if (telemetry == null || vehicle == null) return;

        FlightProfile profile = FlightProfiles.find(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType()))).orElse(null);
        if (profile == null) return;
        FlightState state = FlightClient.predictedState();
        if (state == null) state = telemetry.state();

        List<Component> lines = new ArrayList<>(7);
        lines.add(engineLine(state, profile));
        float maxPower = telemetry.maximumPower();
        int powerPercent = maxPower > 0 ? Math.round(Math.max(0, state.effectivePower() / maxPower) * 100) : 0;
        lines.add(Component.translatable("flight.frontier_aviation.throttle", percent(state.targetThrottle()))
            .append(Component.literal("  "))
            .append(Component.translatable("flight.frontier_aviation.power", powerPercent)));
        lines.add(Component.translatable("flight.frontier_aviation.speed",
            String.format(java.util.Locale.ROOT, "%.2f blocks/tick", telemetry.speed())));
        lines.add(Component.translatable("flight.frontier_aviation.brake")
            .append(Component.literal(telemetry.brake() || telemetry.secondaryBrake() ? "  ●" : "  ○")));
        if (profile.kind() == FlightKind.HELICOPTER) {
            lines.add(Component.translatable("flight.frontier_aviation.hover")
                .append(Component.literal(state.hover() ? "  ●" : "  ○")));
        } else {
            lines.add(Component.translatable(state.gearDown() ? "flight.frontier_aviation.gear.down" : "flight.frontier_aviation.gear.up"));
        }
        lines.add(Component.translatable("key.frontier_aviation.engine").append(Component.literal(": ")).append(FlightKeys.ENGINE.getTranslatedKeyMessage())
            .append(Component.literal("   ")).append(Component.translatable("key.frontier_aviation.brake")).append(Component.literal(": ")).append(FlightKeys.BRAKE.getTranslatedKeyMessage()));
        lines.add(Component.translatable(profile.kind() == FlightKind.HELICOPTER ? "key.frontier_aviation.hover" : "key.frontier_aviation.gear")
            .append(Component.literal(": ")).append((profile.kind() == FlightKind.HELICOPTER ? FlightKeys.HOVER : FlightKeys.GEAR).getTranslatedKeyMessage()));

        Component denial = denial(telemetry.status());
        if (denial != null) lines.add(denial);
        drawPanel(event.getGuiGraphics(), lines);
    }

    private static Component engineLine(FlightState state, FlightProfile profile) {
        return switch (state.enginePhase()) {
            case OFF -> Component.translatable("flight.frontier_aviation.engine.off");
            case STARTING -> Component.translatable("flight.frontier_aviation.engine.starting")
                .append(Component.literal(" ")).append(Component.literal(percent((float) state.phaseTicks() / profile.startupTicks()) + "%"));
            case RUNNING -> Component.translatable("flight.frontier_aviation.engine.running");
            case STOPPING -> Component.translatable("flight.frontier_aviation.engine.stopping");
        };
    }

    private static Component denial(ControlStatus status) {
        return switch (status) {
            case NO_ENERGY -> Component.translatable("flight.frontier_aviation.denied.no_energy");
            case DAMAGED -> Component.translatable("flight.frontier_aviation.denied.damaged");
            case NOT_PILOT -> Component.translatable("flight.frontier_aviation.denied.pilot");
            case TIMEOUT -> Component.translatable("flight.frontier_aviation.denied.timeout");
            case UNSUPPORTED_MODE -> Component.translatable("flight.frontier_aviation.denied.unsupported_mode");
            case BUSY -> Component.translatable("flight.frontier_aviation.denied.busy");
            default -> null;
        };
    }

    private static int percent(float fraction) {
        return Math.round(Math.max(0, Math.min(1, fraction)) * 100);
    }

    private static void drawPanel(GuiGraphics graphics, List<Component> lines) {
        Minecraft mc = Minecraft.getInstance();
        int width = 0;
        for (Component line : lines) width = Math.max(width, mc.font.width(line));
        int x = 8, y = 8, lineHeight = mc.font.lineHeight + 2;
        graphics.fill(x - 4, y - 4, x + width + 5, y + lines.size() * lineHeight + 2, 0xA010191D);
        for (int i = 0; i < lines.size(); i++) graphics.drawString(mc.font, lines.get(i), x, y + i * lineHeight, 0xFFE5F0EF, false);
    }
}
