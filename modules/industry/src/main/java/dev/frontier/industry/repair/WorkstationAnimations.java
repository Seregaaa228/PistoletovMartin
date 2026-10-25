package dev.frontier.industry.repair;

import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.engine.IndustryData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class WorkstationAnimations {
    private WorkstationAnimations() {}
    public static void sync(MinecraftServer server) {
        for (var site : IndustryData.get(server).sites.values()) {
            if (!site.type.isFactory() || site.blueprintVersion < 3) continue;
            var level = IndustryEngine.level(server, site);
            if (level == null) continue;
            // v3 press locations are bounded and stable. This also attaches a cosmetic entity to old chunks.
            int[] offsets = switch (site.type) {
                case EQUIPMENT_FACTORY -> new int[]{-4, 2, 4, 2};
                case VEHICLE_FACTORY -> new int[]{-5, 3};
                default -> new int[0];
            };
            for (int n = 0; n < offsets.length; n += 2) {
                BlockPos pos = site.origin.offset(offsets[n], 1, offsets[n + 1]);
                // Level.getBlockState/getBlockEntity create an UNKNOWN chunk ticket.
                // Cosmetic updates must never keep a departing site's chunks loaded.
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null || !chunk.getBlockState(pos).is(IndustryContent.detail("press"))) continue;
                BlockEntity existing = chunk.getBlockEntity(pos);
                if (existing == null) {
                    existing = new PressWorkstationEntity(pos, chunk.getBlockState(pos));
                    chunk.addAndRegisterBlockEntity(existing);
                    chunk.setUnsaved(true);
                }
                if (existing instanceof PressWorkstationEntity press) press.setOperating(site.operating && site.loaded && site.functional());
            }
        }
    }
}
