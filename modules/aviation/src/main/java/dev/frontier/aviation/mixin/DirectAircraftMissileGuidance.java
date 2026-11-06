package dev.frontier.aviation.mixin;

import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import dev.frontier.aviation.missile.ManualMissileShots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** The native AGM loft is incompatible with direct reticle fire; unrelated launches retain it. */
@Mixin(value = Agm65Entity.class, remap = false)
public abstract class DirectAircraftMissileGuidance {
    @ModifyConstant(method = "m_8119_()V", constant = @Constant(doubleValue = .4), require = 2, expect = 2, allow = 2)
    private double aviation$directReticle(double nativeLoft) {
        return ((Agm65Entity) (Object) this).getPersistentData().getBoolean(ManualMissileShots.DIRECT_AIM) ? 0 : nativeLoft;
    }
}
