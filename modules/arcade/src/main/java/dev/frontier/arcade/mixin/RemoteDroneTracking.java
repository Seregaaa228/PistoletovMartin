package dev.frontier.arcade.mixin;
import dev.frontier.arcade.relay.RemoteDroneView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Native tracker supplies moving units and their updates around the camera. */
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity",remap=false)
public abstract class RemoteDroneTracking {
    @Redirect(method="m_140497_",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_20182_()Lnet/minecraft/world/phys/Vec3;"),remap=false)
    private Vec3 frontier$cameraOrigin(ServerPlayer p){return RemoteDroneView.center(p);}
}
