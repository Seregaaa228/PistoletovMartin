package dev.frontier.aviation.client;

import dev.frontier.aviation.LightAviation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Plain textures are read from disk the first time something is drawn with them, which put
 * 140-200 ms stalls into flights (an aircraft, a HUD icon, a mob seen for the first time).
 * Every texture first loaded while in a world is remembered in config/frontier-texture-warmup.txt,
 * and the next login loads those during the terrain screen instead. Only textures that the
 * plain lookup creates are recorded, so special ones (GeckoLib's animated textures) keep
 * their own loaders. Off with -Dfrontier.textureWarmup=false.
 */
@Mod.EventBusSubscriber(modid = LightAviation.ID, value = Dist.CLIENT)
public final class TextureWarmup {
    public static final boolean ENABLED = !"false".equals(System.getProperty("frontier.textureWarmup"));
    private static final int LIMIT = 4096;
    private static final Set<ResourceLocation> LATE = new LinkedHashSet<>();
    private static boolean warming;

    private TextureWarmup() {}

    /** Called when a texture is about to be created by the plain lookup. */
    public static void lateLoad(ResourceLocation id) {
        if (ENABLED && !warming && Minecraft.getInstance().level != null && LATE.size() < LIMIT) LATE.add(id);
    }

    private static Path file(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("config/frontier-texture-warmup.txt");
    }

    @SubscribeEvent
    public static void loggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ENABLED) return;
        Minecraft minecraft = Minecraft.getInstance();
        Path file = file(minecraft);
        if (!Files.isRegularFile(file)) return;
        long start = System.nanoTime();
        int loaded = 0;
        warming = true;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                ResourceLocation id = ResourceLocation.tryParse(line.trim());
                if (id == null || minecraft.getResourceManager().getResource(id).isEmpty()) continue;
                minecraft.getTextureManager().getTexture(id);
                loaded++;
            }
        } catch (IOException | RuntimeException failure) {
            System.out.println("[frontier-aviation] texture warmup stopped: " + failure);
        } finally {
            warming = false;
        }
        System.out.println("[frontier-aviation] texture warmup: " + loaded + " textures in "
            + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!ENABLED || LATE.isEmpty()) return;
        Path file = file(Minecraft.getInstance());
        try {
            Set<String> all = new LinkedHashSet<>();
            if (Files.isRegularFile(file)) all.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            for (ResourceLocation id : LATE) if (all.size() < LIMIT) all.add(id.toString());
            Files.createDirectories(file.getParent());
            Files.write(file, all, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            System.out.println("[frontier-aviation] texture warmup list not saved: " + failure);
        }
        LATE.clear();
    }
}
