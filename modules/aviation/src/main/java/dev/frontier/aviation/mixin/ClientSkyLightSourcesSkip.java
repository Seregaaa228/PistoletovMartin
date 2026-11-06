package dev.frontier.aviation.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every chunk the client receives rebuilds its sky light sources (the lowest
 * light-blocking block of each column), which took ~57% of chunk packet handling on the
 * render thread in a flight profile. Their only reader is vanilla's sky light engine, and
 * Starlight replaces both vanilla light engines with its own, which takes the light the
 * server sent in the same packet. With Starlight loaded the rebuild is skipped; without
 * it, or with -Dfrontier.skipClientSkyLightSources=false, vanilla behaviour stays.
 */
@Mixin(value = LevelChunk.class, remap = false)
public abstract class ClientSkyLightSourcesSkip {
    @Unique private static Boolean aviation$skip;

    @Redirect(method = "m_187971_(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/nbt/CompoundTag;Ljava/util/function/Consumer;)V",
            remap = false, require = 1,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/level/chunk/LevelChunk;m_284190_()V"))
    private void aviation$skipWithStarlight(LevelChunk chunk) {
        if (aviation$skip == null) aviation$skip = !"false".equals(System.getProperty("frontier.skipClientSkyLightSources"))
            && ModList.get().isLoaded("starlight");
        if (!aviation$skip) chunk.initializeLightSources();
    }
}
