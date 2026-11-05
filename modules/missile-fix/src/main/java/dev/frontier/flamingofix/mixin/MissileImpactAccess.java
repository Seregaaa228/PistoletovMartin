package dev.frontier.flamingofix.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.sbw.deepstrike.game.GuidedMissileEntity", remap = false)
public interface MissileImpactAccess {
    @Accessor("impactPosition")
    void flamingo$setImpactPosition(Vec3 position);
}
