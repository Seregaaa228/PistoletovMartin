package dev.frontier.arcade.mixin;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SBW keeps a cannon shell's kind (AP, HE, CM, WP) private; artillery penetration needs it. */
@Mixin(value = CannonShellEntity.class, remap = false)
public interface CannonShellTypeAccess {
    @Accessor(value = "type", remap = false)
    CannonShellEntity.Type frontier$type();
}
