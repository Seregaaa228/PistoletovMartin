package dev.frontier.aviation.systemstest;

import com.google.gson.JsonObject;
import dev.frontier.aviation.client.F16ControlSurfaceManager;
import dev.frontier.aviation.control.client.FlightClient;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod.EventBusSubscriber(modid = "frontier_aircraft_systems_test", value = Dist.CLIENT)
public final class F16VisualProbe {
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("frontier.aircraftSystemsTest")) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !Files.exists(FMLPaths.GAMEDIR.get().resolve("aircraft-visual-brake.txt"))) return;
        if (!Files.exists(FMLPaths.GAMEDIR.get().resolve("aircraft-payload-bounds.json"))) writePayloadBounds();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Su24mEntity plane)) continue;
            var out = new JsonObject();
            out.addProperty("visualBrake", FlightClient.visualAirBrake(plane));
            out.addProperty("brakePose", F16ControlSurfaceManager.airBrake(plane, 1));
            out.addProperty("pilot", FlightClient.hasVisualPilot(plane));
            out.addProperty("payloadBits", dev.frontier.aviation.client.F16PayloadVisual.bits(plane.getUUID()));
            try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-visual-client.json"), out.toString()); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            break;
        }
    }

    private static void writePayloadBounds() {
        var result = new JsonObject();
        for (String name : new String[]{"agm_65", "ru_9m100_missile", "ru_9m336_missile", "wire_guide_missile"}) {
            var key = new net.minecraft.resources.ResourceLocation("superbwarfare", "models/bedrock/projectile/" + name + ".geo.json");
            var model = com.atsuishio.superbwarfare.resource.model.ProjectileModelReloadListener.INSTANCE.getModel(key);
            if (model == null) continue;
            var instance = model.createInstance();
            int flare = model.getIndex("flare");
            var chunks = new com.google.gson.JsonArray();
            for (var chunk : model.chunks()) {
                if (chunk.attachBoneIndex() == flare) continue;
                var transform = chunk.attachBoneIndex() < 0 ? new org.joml.Matrix4f() : instance.getGlobalTransform(chunk.attachBoneIndex());
                var box = new JsonObject();
                double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
                int count = 0;
                for (float[] points : new float[][]{chunk.quads().positions(), chunk.vertices().positions()}) {
                    for (int i = 0; i + 2 < points.length; i += 3) {
                        var point = transform.transformPosition(new org.joml.Vector3f(points[i], points[i+1], points[i+2]));
                        double[] coords = {point.x, point.y, point.z};
                        for (int k = 0; k < 3; k++) { min[k] = Math.min(min[k], coords[k]); max[k] = Math.max(max[k], coords[k]); }
                        count++;
                    }
                }
                if (count == 0) continue;
                box.addProperty("bone", chunk.attachBoneIndex());
                box.addProperty("sources", String.join(",", chunk.sourceBones()));
                box.add("min", new com.google.gson.Gson().toJsonTree(min));
                box.add("max", new com.google.gson.Gson().toJsonTree(max));
                chunks.add(box);
            }
            result.add(name, chunks);
        }
        try { Files.writeString(FMLPaths.GAMEDIR.get().resolve("aircraft-payload-bounds.json"), result.toString()); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
