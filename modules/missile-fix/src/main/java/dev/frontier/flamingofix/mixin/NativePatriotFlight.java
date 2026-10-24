package dev.frontier.flamingofix.mixin;

import java.util.UUID;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the prepared visual pose without touching flight history or smoothing. */
@Mixin(targets = "dev.sbw.deepstrike.client.PatriotRemoteEffects$Flight", remap = false)
public interface NativePatriotFlight {
    @Accessor("uuid") UUID flamingo$uuid();
    @Accessor("visualPosition") Vec3 flamingo$position();
    @Accessor("visualYaw") float flamingo$yaw();
    @Accessor("visualPitch") float flamingo$pitch();
    @Accessor("warhead") int flamingo$warhead();
    @Accessor("nativeBody") MissileProjectile flamingo$nativeBody();
}
