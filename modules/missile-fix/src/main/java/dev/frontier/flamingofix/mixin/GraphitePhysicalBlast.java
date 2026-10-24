package dev.frontier.flamingofix.mixin;

import dev.sbw.deepstrike.game.MissileKind;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** User-requested explosive graphite FP5: retain EMP effects and use DSM's native physical blast. */
@Mixin(targets="dev.sbw.deepstrike.game.MissileExplosion",remap=false)
public abstract class GraphitePhysicalBlast {
    @Shadow @Final private MissileKind kind;
    @Shadow @Final private String warhead;
    @Shadow private void addGraphiteEmpEffects() { throw new AssertionError(); }

    @Inject(method="m_46061_()V",at=@At("HEAD"),remap=false,require=1)
    private void frontierFlamingo$retainGraphiteEffects(CallbackInfo callback) {
        if (kind == MissileKind.FP5 && "graphite".equals(warhead)) addGraphiteEmpEffects();
    }

    // Only the first comparison selects the EMP-only branch. The remaining native
    // HE calculation, crater, structural damage, Forge events and finalization run once.
    @Redirect(method="m_46061_()V",
            at=@At(value="INVOKE",target="Ljava/lang/String;equals(Ljava/lang/Object;)Z",ordinal=0,remap=false),
            remap=false,require=1)
    private boolean frontierFlamingo$physicalGraphite(String expected, Object actual) {
        if (kind == MissileKind.FP5 && "graphite".equals(actual)) return false;
        return expected.equals(actual);
    }
}
