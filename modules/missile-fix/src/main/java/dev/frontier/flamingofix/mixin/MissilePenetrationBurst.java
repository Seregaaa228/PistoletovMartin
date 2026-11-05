package dev.frontier.flamingofix.mixin;

import dev.frontier.flamingofix.MissileImpactConfig;
import dev.frontier.flamingofix.MissilePenetration;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A penetrating warhead bursts below the roof it hit (frontier-missiles-server.toml [penetration]). */
@Mixin(targets="dev.sbw.deepstrike.game.MissileExplosion",remap=false)
public abstract class MissilePenetrationBurst {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private ServerLevel world;
    @org.spongepowered.asm.mixin.Unique
    private static final org.slf4j.Logger flamingo$LOG=com.mojang.logging.LogUtils.getLogger();
    @Inject(method="detonate(Lnet/minecraft/server/level/ServerLevel;Ldev/sbw/deepstrike/game/GuidedMissileEntity;Ljava/lang/String;)V",
            at=@At("HEAD"),remap=false,require=1)
    private static void flamingo$penetrate(ServerLevel level,GuidedMissileEntity missile,String warhead,CallbackInfo callback) {
        if(missile.isIntercepted() || !"he".equals(warhead) && !"penetrating".equals(warhead))return;
        double depth=MissileImpactConfig.penetration(missile.kind().id);
        if(depth<=0)return;
        var result=MissilePenetration.resolve(missile.impactPosition(),missile.getDeltaMovement(),depth,
                pos->flamingo$solid(level,pos),flamingo$hard(level));
        for(BlockPos pos:result.channel()) {
            flamingo$clear(level,pos);
            for(var side:net.minecraft.core.Direction.Plane.HORIZONTAL)flamingo$clear(level,pos.relative(side));
        }
        missile.setPos(result.burst());
        ((MissileImpactAccess)(Object)missile).flamingo$setImpactPosition(result.burst());
        if(!result.channel().isEmpty()) {
            double radius=missile.kind().craterRadius*MissileImpactConfig.blastScale(missile.kind().id);
            flamingo$CONFINED.set(new Confined(missile.impactPosition().y,result.burst(),radius));
        }
    }

    @Inject(method="detonate(Lnet/minecraft/server/level/ServerLevel;Ldev/sbw/deepstrike/game/GuidedMissileEntity;Ljava/lang/String;)V",
            at=@At("RETURN"),remap=false,require=1)
    private static void flamingo$forget(ServerLevel level,GuidedMissileEntity missile,String warhead,CallbackInfo callback) {
        flamingo$CONFINED.remove();
    }

    @org.spongepowered.asm.mixin.Unique
    private record Confined(double roofY, net.minecraft.world.phys.Vec3 burst, double radius) { }
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Confined> flamingo$CONFINED=new ThreadLocal<>();
    @org.spongepowered.asm.mixin.Unique
    private static final int flamingo$CONFINED_LIMIT=10000;

    /**
     * A burst inside a building vents up its entry channel: the native crater is a bowl below the
     * burst, so add a ragged sphere around it and a column up to the roof it came through.
     */
    @Inject(method="m_46061_()V",at=@At("TAIL"),remap=false,require=1)
    private void flamingo$gutBuilding(CallbackInfo callback) {
        Confined confined=flamingo$CONFINED.get();
        if(confined==null)return;
        flamingo$CONFINED.remove();
        var explosion=(net.minecraft.world.level.Explosion)(Object)this;
        ServerLevel level=world;
        var blow=explosion.getToBlow();
        var known=new java.util.HashSet<>(blow);
        var random=level.getRandom();
        double sphere=confined.radius()*0.8, column=confined.radius()*0.6;
        var burst=confined.burst();
        int reach=(int)Math.ceil(sphere);
        int bottom=(int)Math.floor(burst.y-sphere*0.3), top=(int)Math.ceil(Math.max(confined.roofY()+1,burst.y+sphere));
        int added=0;
        for(int y=bottom;y<=top && added<flamingo$CONFINED_LIMIT;y++)
            for(int x=-reach;x<=reach && added<flamingo$CONFINED_LIMIT;x++)
                for(int z=-reach;z<=reach;z++) {
                    double flat=Math.sqrt(x*x+z*z), dy=y+0.5-burst.y;
                    double inSphere=sphere-Math.sqrt(flat*flat+dy*dy);
                    double inColumn=y<=confined.roofY()+1?column-flat:-1;
                    double depth=Math.max(inSphere/sphere,inColumn/column);
                    // Solid inside, ragged over the outer 30 %.
                    if(depth<=0 || depth<0.3 && random.nextDouble()>depth/0.3)continue;
                    BlockPos pos=BlockPos.containing(burst.x+x,y,burst.z+z);
                    if(known.contains(pos) || !flamingo$solid(level,pos) || flamingo$hard(level).test(pos)
                            || level.getBlockEntity(pos)!=null)continue;
                    blow.add(pos);known.add(pos);
                    if(++added>=flamingo$CONFINED_LIMIT)break;
                }
        flamingo$LOG.info("[frontier-missile] confined burst at y {} under roof y {}, +{} blocks",
                Math.round(burst.y),Math.round(confined.roofY()),added);
    }

    /** Leaves do not stop the warhead; loaded collision blocks do. */
    @org.spongepowered.asm.mixin.Unique
    private static boolean flamingo$solid(ServerLevel level,BlockPos pos) {
        if(!level.isLoaded(pos))return false;
        var state=level.getBlockState(pos);
        return !state.is(net.minecraft.tags.BlockTags.LEAVES) && !state.getCollisionShape(level,pos).isEmpty();
    }

    @org.spongepowered.asm.mixin.Unique
    private static java.util.function.Predicate<BlockPos> flamingo$hard(ServerLevel level) {
        return pos->!level.isLoaded(pos) || level.getBlockState(pos).getDestroySpeed(level,pos)<0;
    }

    /** A 5-block cross hole; containers and other block entities are the blast's to break. */
    @org.spongepowered.asm.mixin.Unique
    private static void flamingo$clear(ServerLevel level,BlockPos pos) {
        if(!flamingo$solid(level,pos) || flamingo$hard(level).test(pos) || level.getBlockEntity(pos)!=null)return;
        level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
    }
}
