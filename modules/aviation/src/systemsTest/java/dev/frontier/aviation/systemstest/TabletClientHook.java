package dev.frontier.aviation.systemstest;

import com.google.gson.JsonParser;
import dev.sbw.deepstrike.client.Su24mScreen;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client half of the tablet scenario: opens DeepStrike's tablet for the staged
 * aircraft, the way a pilot would, once the server reports it parked. Everything
 * after that is real input from the runner.
 */
final class TabletClientHook {
    private String openedFor;
    private int ticks;

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 10 != 0) return;
        var mc = Minecraft.getInstance();
        var path = FMLPaths.GAMEDIR.get().resolve("aircraft-tablet-progress.json");
        if (mc.level == null || mc.player == null || !Files.isRegularFile(path)) return;
        try {
            var progress = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String runId = System.getProperty("frontier.aircraftSystemsRunId", "");
            if (!runId.equals(progress.get("runId").getAsString()) || !progress.has("vehicle")) return;
            String stage = progress.get("stage").getAsString();
            // The pilot works the tablet while parked and in flight; camera stages need it closed.
            if (!stage.equals("parked") && !stage.equals("airborne")) return;
            String key = runId + ':' + progress.get("vehicle").getAsInt() + ':' + stage;
            if (key.equals(openedFor) && mc.screen instanceof Su24mScreen) return;
            if (mc.screen instanceof Su24mScreen) { openedFor = key; return; }
            if (key.equals(openedFor)) return;
            Su24mScreen.open(progress.get("vehicle").getAsInt());
            if (mc.screen instanceof Su24mScreen) openedFor = key;
        } catch (Exception ignored) {
            // Partially written progress file; retried on the next poll.
        }
    }
}
