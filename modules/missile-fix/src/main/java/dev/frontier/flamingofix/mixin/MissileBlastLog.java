package dev.frontier.flamingofix.mixin;

import com.mojang.logging.LogUtils;
import dev.sbw.deepstrike.game.MissileKind;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One line per missile blast: where it went off and how many blocks it was to destroy. */
@Mixin(targets="dev.sbw.deepstrike.game.MissileExplosion",remap=false)
public abstract class MissileBlastLog {
    @Unique private static final Logger frontierFlamingo$LOG=LogUtils.getLogger();
    @Shadow @Final private MissileKind kind;
    @Shadow @Final private String warhead;
    @Shadow @Final private Vec3 center;

    @Inject(method="m_46061_()V",at=@At("TAIL"),remap=false,require=1)
    private void frontierFlamingo$logBlast(CallbackInfo callback) {
        frontierFlamingo$LOG.info("[frontier-missile] {} warhead={} at {} {} {} power={} blocks={}", kind.id, warhead,
                Math.round(center.x), Math.round(center.y), Math.round(center.z), kind.blastPower,
                ((Explosion)(Object)this).getToBlow().size());
    }
}
