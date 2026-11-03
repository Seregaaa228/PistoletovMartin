package dev.frontier.aviation.client;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import dev.frontier.aviation.LightAviation;
import dev.frontier.aviation.control.ModeRequest;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.control.client.FlightClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side of the opt-in flight benchmark (-Dfrontier.flightBench=x,y,z,yaw): once in
 * a world it asks the server for the fixed F-16 seat, then flies it straight at full
 * throttle holding the start altitude and wings level, so every run crosses the same
 * terrain at the same speed. After a settle period it measures frame intervals for a
 * fixed window, writes flight-bench-result.json into the game directory and quits.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class FlightBenchClient {
    public static final boolean ENABLED = FlightFramePerformance.BENCH;
    private static final int JOIN_WAIT = 100, SETTLE = 200, MEASURE = Integer.getInteger("frontier.flightBenchTicks", 1800);
    private static final int[] ROUTE = route();

    private static int joinedTicks, flightTicks, doneTicks = -1;

    private FlightBenchClient() {}

    private static int[] route() {
        if (!ENABLED) return null;
        String[] parts = System.getProperty("frontier.flightBench").split(",");
        if (parts.length != 4) throw new IllegalArgumentException("frontier.flightBench must be x,y,z,yaw");
        int[] route = new int[4];
        for (int i = 0; i < 4; i++) route[i] = Math.round(Float.parseFloat(parts[i].trim()));
        return route;
    }

    /** Replaces the pilot's input while the benchmark flies; otherwise returns it unchanged. */
    public static PilotCommand command(VehicleEntity vehicle, PilotCommand input) {
        if (!ENABLED || doneTicks >= 0) return input;
        float pitch = vehicle.getXRot();
        // Positive pitch is nose down: climb back to the start altitude, at most 12 degrees.
        float wanted = Mth.clamp((float) (vehicle.getY() - ROUTE[1]) * .6f, -12, 12);
        float roll = FlightClient.predictedState() == null ? 0 : FlightClient.predictedState().roll();
        return new PilotCommand(0, Mth.clamp((wanted - pitch) / 20f, -1, 1), 0, Mth.clamp(-roll / 30f, -1, 1), 1,
            false, false, false, flightTicks < 40 ? ModeRequest.ENABLE : ModeRequest.KEEP, ModeRequest.KEEP, ModeRequest.KEEP);
    }

    /** The benchmark flies without window focus. */
    public static boolean forcesActive() { return ENABLED && doneTicks < 0; }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { joinedTicks = 0; return; }
        if (doneTicks >= 0) {
            if (++doneTicks == 40) {
                // Leave like the pause menu's Disconnect, then close the client.
                minecraft.level.disconnect();
                minecraft.clearLevel();
                minecraft.stop();
            }
            return;
        }
        if (FlightClient.controlledEntity() == null) {
            if (++joinedTicks == JOIN_WAIT) minecraft.player.connection.sendCommand(
                "frontier_bench start " + ROUTE[0] + " " + ROUTE[1] + " " + ROUTE[2] + " " + ROUTE[3]);
            return;
        }
        flightTicks++;
        if (flightTicks == SETTLE) {
            FlightFramePerformance.benchReset();
            minecraft.player.connection.sendCommand("frontier_bench measure");
        }
        if (flightTicks == SETTLE + MEASURE) {
            minecraft.player.connection.sendCommand("frontier_bench finish");
            var plane = FlightClient.controlledEntity();
            String result = "{\"route\":\"" + System.getProperty("frontier.flightBench") + "\",\"renderDistance\":"
                + minecraft.options.renderDistance().get() + ",\"endX\":" + Math.round(plane.getX())
                + ",\"endY\":" + Math.round(plane.getY()) + ",\"endZ\":" + Math.round(plane.getZ())
                + ",\"frames\":" + FlightFramePerformance.benchSummary() + "}";
            System.out.println("[flight-bench] " + result);
            try {
                Files.writeString(minecraft.gameDirectory.toPath().resolve("flight-bench-result.json"), result + "\n",
                    StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) {
                System.out.println("[flight-bench] result not written: " + failure);
            }
            doneTicks = 0;
        }
    }
}
