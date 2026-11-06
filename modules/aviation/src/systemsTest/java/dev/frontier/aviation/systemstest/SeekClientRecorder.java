package dev.frontier.aviation.systemstest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client half of the seeker self-lock check: samples the target SBW's own vehicle seeker
 * picked this client tick (ClientEventHandler.nearestEntityVehicle) while the pilot sits in
 * a jet with the AAM selected, per stage and camera mode. Reads only; never sets a lock.
 */
final class SeekClientRecorder {
    private final Map<String, int[]> counts = new LinkedHashMap<>();
    private final Map<String, String> others = new LinkedHashMap<>();
    private Field nearest;
    private String error;
    private int ticks;

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        var path = FMLPaths.GAMEDIR.get().resolve("aircraft-seek-request.json");
        if (mc.player == null || mc.level == null || !Files.isRegularFile(path)) return;
        try {
            var request = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!System.getProperty("frontier.aircraftSystemsRunId", "").equals(request.get("runId").getAsString())) return;
            if (nearest == null) nearest = Class.forName("com.atsuishio.superbwarfare.event.ClientEventHandler").getField("nearestEntityVehicle");
            Entity picked = (Entity) nearest.get(null);
            String stage = request.get("stage").getAsString();
            int self = request.get("self").getAsInt(), target = request.get("target").getAsInt();
            boolean first = mc.options.getCameraType().isFirstPerson();
            for (String key : new String[]{stage, stage + "/" + (first ? "first" : "third")}) {
                int[] c = counts.computeIfAbsent(key, ignored -> new int[5]);
                c[0]++;
                if (picked == null) c[4]++;
                else if (picked.getId() == self || picked == mc.player.getVehicle()) c[1]++;
                else if (picked.getId() == target) c[2]++;
                else { c[3]++; others.put(key, String.valueOf(picked.getType())); }
            }
            if (++ticks % 5 == 0) write(mc.player.getVehicle() == null ? -1 : mc.player.getVehicle().getId());
        } catch (Exception failure) {
            error = failure.toString();
        }
    }

    private void write(int vehicle) throws java.io.IOException {
        var root = new JsonObject();
        root.addProperty("runId", System.getProperty("frontier.aircraftSystemsRunId"));
        root.addProperty("clientVehicleId", vehicle);
        if (error != null) root.addProperty("error", error);
        var stages = new JsonObject();
        for (var entry : counts.entrySet()) {
            var s = new JsonObject();
            int[] c = entry.getValue();
            s.addProperty("samples", c[0]);
            s.addProperty("self", c[1]);
            s.addProperty("target", c[2]);
            s.addProperty("other", c[3]);
            s.addProperty("none", c[4]);
            if (others.containsKey(entry.getKey())) s.addProperty("otherType", others.get(entry.getKey()));
            stages.add(entry.getKey(), s);
        }
        root.add("stages", stages);
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-seek-client.json"), root.toString());
    }
}
