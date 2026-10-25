package dev.frontier.industry.acceptance;

import dev.frontier.flamingofix.DeferredMissileMotion;
import dev.sbw.deepstrike.game.FlightCollision;
import dev.sbw.deepstrike.game.GuidedMissileEntity;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.model.MissileGeometry;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Explicit fresh-world proof that empty/unknown terrain never builds model cubes. */
public final class DeferredTerrainChecks {
    public static Map<String, Boolean> run(ServerLevel level) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        // A small part of the already forced lab, before other fixtures start.
        for (int x=-1;x<=1;x++) for(int z=-2;z<=4;z++) level.getChunk(x,z);
        Vec3 airFrom = new Vec3(.5,240.5,0), movement = new Vec3(0,0,60);
        Vec3 unknownFrom = new Vec3(256004.5,240.5,256004.5);
        BlockPos solid = new BlockPos(0,240,30);
        var original = level.getBlockState(solid);
        try {
            for (MissileKind kind : MissileKind.values()) {
                level.setBlockAndUpdate(solid, Blocks.AIR.defaultBlockState());
                long started=DeferredMissileMotion.deferredCount(), built=DeferredMissileMotion.materializedCount();
                int misses=0;
                for(int i=0;i<20;i++) {
                    var sweep=sweep(level,kind,airFrom,movement,i);
                    if(sweep.hit().getType()==HitResult.Type.MISS && sweep.missingTerrain().isEmpty())misses++;
                }
                check(results,kind+" 20 loaded air sweeps build no transformed meshes",misses==20
                        && DeferredMissileMotion.deferredCount()-started==20
                        && DeferredMissileMotion.materializedCount()==built);

                boolean absent=FlightCollision.availableChunk(level,16000,16000)==null;
                started=DeferredMissileMotion.deferredCount(); built=DeferredMissileMotion.materializedCount();
                int unknown=0;
                for(int i=0;i<20;i++) {
                    var sweep=sweep(level,kind,unknownFrom,movement,i);
                    if(sweep.hit().getType()==HitResult.Type.MISS && !sweep.missingTerrain().isEmpty()
                            && !sweep.terrainKnownThroughContact())unknown++;
                }
                check(results,kind+" 20 absent-terrain sweeps build no mesh or load chunks",absent && unknown==20
                        && FlightCollision.availableChunk(level,16000,16000)==null
                        && DeferredMissileMotion.deferredCount()-started==20
                        && DeferredMissileMotion.materializedCount()==built);

                level.setBlockAndUpdate(solid,Blocks.STONE.defaultBlockState());
                started=DeferredMissileMotion.deferredCount(); built=DeferredMissileMotion.materializedCount();
                int hits=0;
                for(int i=0;i<20;i++) {
                    var sweep=sweep(level,kind,airFrom,movement,i);
                    if(sweep.hit() instanceof BlockHitResult block && block.getType()==HitResult.Type.BLOCK
                            && block.getBlockPos().equals(solid) && sweep.terrainKnownThroughContact())hits++;
                }
                check(results,kind+" 20 actual stone contacts each build exactly one native mesh",hits==20
                        && DeferredMissileMotion.deferredCount()-started==20
                        && DeferredMissileMotion.materializedCount()-built==20);
            }
        } finally { level.setBlockAndUpdate(solid,original); }
        return Map.copyOf(results);
    }

    private static FlightCollision.SweepResult sweep(ServerLevel level,MissileKind kind,Vec3 from,Vec3 movement,int shot) {
        var missile=new GuidedMissileEntity(level,kind);
        missile.moveTo(from.x,from.y,from.z,0,0);
        missile.tickCount=shot%2==0?0:150; // native folded fins / released booster
        missile.setDeltaMovement(movement);
        var body=MissileGeometry.worldBounds(kind,from,movement,0,0);
        return FlightCollision.sweepSegment(level,missile,from,from.add(movement),body,entity->false);
    }

    private static void check(Map<String,Boolean> results,String label,boolean value) {
        results.put(label,value);
        com.mojang.logging.LogUtils.getLogger().info("COMBAT_DEFERRED {} = {}",label,value);
    }
    private DeferredTerrainChecks() {}
}
