package dev.frontier.arcade.mixin;

import dev.frontier.arcade.relay.RemoteDroneView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The watched position follows the remote camera, and while it does the chunk radius sent to that
 * player is limited to what its client renders (1.20.1 always sends the server view distance).
 * Vanilla still handles chunk/light delivery and release: move() compares each chunk with the old
 * centre (ordinals 0 and 2, the radius the chunks were sent with) and the new one (1 and 3, the
 * radius wanted now), so entering or leaving the camera neither leaves holes nor resends chunks.
 */
@Mixin(value=ChunkMap.class,remap=false)
public abstract class RemoteDroneChunks {
    private static final String IN_RANGE = "Lnet/minecraft/server/level/ChunkMap;m_200878_(IIIII)Z";
    private static final int OUT_OF_REACH = 1 << 20;

    @Redirect(method={"m_140184_","m_140192_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_146903_()I"),remap=false)
    private int frontier$cameraX(ServerPlayer p){return BlockPos.containing(RemoteDroneView.center(p)).getX();}
    @Redirect(method={"m_140184_","m_140192_"},at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_146907_()I"),remap=false)
    private int frontier$cameraZ(ServerPlayer p){return BlockPos.containing(RemoteDroneView.center(p)).getZ();}
    @Redirect(method={"m_140184_","m_140192_","m_140373_"},at=@At(value="INVOKE",target="Lnet/minecraft/core/SectionPos;m_235861_(Lnet/minecraft/world/level/entity/EntityAccess;)Lnet/minecraft/core/SectionPos;"),remap=false)
    private SectionPos frontier$cameraSection(EntityAccess entity){return entity instanceof ServerPlayer p ? SectionPos.of(BlockPos.containing(RemoteDroneView.center(p))) : SectionPos.of(entity);}

    @Redirect(method="m_140184_",at=@At(value="INVOKE",target=IN_RANGE,ordinal=0),remap=false)
    private boolean frontier$oldNear(int x,int z,int cx,int cz,int view,ServerPlayer p){return ChunkMap.isChunkInRange(x,z,cx,cz,RemoteDroneView.sentRadius(p,view));}
    @Redirect(method="m_140184_",at=@At(value="INVOKE",target=IN_RANGE,ordinal=1),remap=false)
    private boolean frontier$newNear(int x,int z,int cx,int cz,int view,ServerPlayer p){return ChunkMap.isChunkInRange(x,z,cx,cz,RemoteDroneView.wantedRadius(p,view));}
    @Redirect(method="m_140184_",at=@At(value="INVOKE",target=IN_RANGE,ordinal=2),remap=false)
    private boolean frontier$oldFar(int x,int z,int cx,int cz,int view,ServerPlayer p){return ChunkMap.isChunkInRange(x,z,cx,cz,RemoteDroneView.sentRadius(p,view));}
    @Redirect(method="m_140184_",at=@At(value="INVOKE",target=IN_RANGE,ordinal=3),remap=false)
    private boolean frontier$newFar(int x,int z,int cx,int cz,int view,ServerPlayer p){return ChunkMap.isChunkInRange(x,z,cx,cz,RemoteDroneView.wantedRadius(p,view));}
    @Inject(method="m_140184_",at=@At("RETURN"),remap=false)
    private void frontier$moved(ServerPlayer p,CallbackInfo ci){RemoteDroneView.radiusSent(p,true);}

    /**
     * Chunks that finish loading (and block changes) go to getPlayers(), which checks the global view
     * distance against the player's last section: outside the camera radius that section is moved out of reach.
     */
    @Redirect(method="m_183262_",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;m_8965_()Lnet/minecraft/core/SectionPos;"),remap=false)
    private SectionPos frontier$cameraReach(ServerPlayer p,ChunkPos chunk,boolean edge){
        SectionPos last=p.getLastSectionPos();int radius=RemoteDroneView.cameraRadius(p);
        return radius<0 || ChunkMap.isChunkInRange(chunk.x,chunk.z,last.x(),last.z(),radius) ? last : SectionPos.of(last.x()+OUT_OF_REACH,last.y(),last.z());
    }

    /** Adding a player sends the wanted radius; removing it releases what was actually sent. */
    @Redirect(method="m_140192_",at=@At(value="INVOKE",target=IN_RANGE),remap=false)
    private boolean frontier$status(int x,int z,int cx,int cz,int view,ServerPlayer p,boolean track){
        return ChunkMap.isChunkInRange(x,z,cx,cz,track ? RemoteDroneView.wantedRadius(p,view) : RemoteDroneView.sentRadius(p,view));
    }
    @Inject(method="m_140192_",at=@At("RETURN"),remap=false)
    private void frontier$statusDone(ServerPlayer p,boolean track,CallbackInfo ci){RemoteDroneView.radiusSent(p,track);}
}
