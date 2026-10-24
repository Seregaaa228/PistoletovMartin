package dev.frontier.industry;

import dev.frontier.industry.engine.Blueprint;
import dev.frontier.industry.engine.IndustryData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;

/** Chimneys visibly burn the plant's real fuel; an idle, unloaded or broken station stays quiet. */
public final class IndustrialEffects {
    private IndustrialEffects() {}
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        for (var site : IndustryData.get(server).sites.values()) {
            if (site.type != SiteType.POWER_STATION || site.blueprintVersion < 2 || site.burn <= 0 || !site.functional()) continue;
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, site.dimension));
            if (level == null || !level.hasChunkAt(site.origin)) continue;
            for (int dx : new int[]{-6, 0}) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                site.origin.getX() + dx + .5, site.origin.getY() + 25.1, site.origin.getZ() + 7.5, 3, .35, .1, .35, .035);
        }
    }
}
