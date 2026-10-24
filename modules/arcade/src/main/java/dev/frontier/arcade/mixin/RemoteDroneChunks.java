package dev.frontier.arcade.mixin;

import dev.frontier.arcade.relay.RemoteDroneView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the watched position changes; vanilla handles chunk/light delivery and release. */
@Mixin(value=ChunkMap.class,remap=false)
public abstract class RemoteDroneChunks {
    @Redirect(method={"m_140184_","m_140192_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_146903_()I"),remap=false)
    private int frontier$cameraX(ServerPlayer p){return BlockPos.containing(RemoteDroneView.center(p)).getX();}
    @Redirect(method={"m_140184_","m_140192_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_146907_()I"),remap=false)
    private int frontier$cameraZ(ServerPlayer p){return BlockPos.containing(RemoteDroneView.center(p)).getZ();}
    @Redirect(method={"m_140184_","m_140192_","m_140373_"},at=@At(value="INVOKE",target="Lnet/minecraft/core/SectionPos;m_235861_(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;"),remap=false)
    private SectionPos frontier$cameraSection(EntityAccess entity){return entity instanceof ServerPlayer p ? SectionPos.of(BlockPos.containing(RemoteDroneView.center(p))) : SectionPos.of(entity);}
}
