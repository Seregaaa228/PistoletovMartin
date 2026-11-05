package dev.frontier.industry.buildings;

import dev.frontier.industry.*;
import dev.frontier.industry.engine.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/** Assign physical workers fairly; large building reservations are routing obstacles, not teleport targets. */
public final class ConstructionCrew {
    private static final String RANGE="frontierConstructionOriginalRange";
    private record Watch(int cursor,BlockPos position,int tick){}
    private static final Map<UUID,Watch> WATCH=new HashMap<>();
    private static final Map<UUID,Integer> COOLDOWN=new HashMap<>();
    private ConstructionCrew(){}
    public static WorkerEntity current(MinecraftServer server,IndustryData data,IndustryData.Site site){
        for(var w:data.workers.values())if(w.active()&&site.id.equals(w.site)&&site.owner.equals(w.owner)&&w.role.equals("builder")){
            var e=IndustryEngine.find(server,w.id);
            if(e instanceof WorkerEntity npc&&usable(npc,site))return npc;
        }return null;
    }
    private static boolean usable(WorkerEntity npc,IndustryData.Site site){return npc.isAlive()&&!npc.isPassenger()&&!IndustryEngine.busy(npc)&&npc.level().dimension().location().equals(site.dimension)&&IndustryEngine.owned(npc,site.owner)&&npc.getPersistentData().getString("frontierIndustryRole").equals("builder");}
    private static void range(WorkerEntity npc){var a=npc.getAttribute(Attributes.FOLLOW_RANGE);if(a==null)return;var tag=npc.getPersistentData();if(!tag.contains(RANGE))tag.putDouble(RANGE,a.getBaseValue());a.setBaseValue(Math.max(128,a.getBaseValue()));}
    public static void restore(WorkerEntity npc){var tag=npc.getPersistentData();if(tag.contains(RANGE)){var a=npc.getAttribute(Attributes.FOLLOW_RANGE);if(a!=null)a.setBaseValue(tag.getDouble(RANGE));tag.remove(RANGE);}}
    private static void free(MinecraftServer server,IndustryData data,IndustryData.Worker w){w.site=null;var e=IndustryEngine.find(server,w.id);if(e instanceof WorkerEntity npc){restore(npc);IndustryEngine.initialize(npc,w);if(!IndustryEngine.busy(npc))npc.getNavigation().stop();}data.setDirty();}
    public static boolean assign(MinecraftServer server,IndustryData data,IndustryData.Site site,boolean anyFree){
        if(current(server,data,site)!=null)return true;var level=IndustryEngine.level(server,site);if(level==null)return false;var target=workPoint(level,site);if(target==null)return false;
        var candidates=new ArrayList<IndustryData.Worker>();
        for(var w:data.workers.values())if(w.active()&&w.site==null&&w.owner.equals(site.owner)&&w.dimension.equals(site.dimension)&&(anyFree||w.role.equals("builder"))&&COOLDOWN.getOrDefault(w.id,0)<=server.getTickCount())candidates.add(w);
        candidates.sort(Comparator.comparingDouble(w->{var e=IndustryEngine.find(server,w.id);return e==null?Double.MAX_VALUE:e.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5);}));
        for(var w:candidates){var e=IndustryEngine.find(server,w.id);if(!(e instanceof WorkerEntity npc)||!npc.isAlive()||npc.isPassenger()||IndustryEngine.busy(npc)||!IndustryEngine.owned(npc,site.owner)||npc.blockPosition().distSqr(target)>1024*1024)continue;
            w.site=site.id;w.role="builder";range(npc);IndustryEngine.initialize(npc,w);data.setDirty();return true;
        }return false;
    }
    public static void dispatch(MinecraftServer server,IndustryData data){
        if(server.getTickCount()%10!=0)return;
        var occupied=new HashSet<UUID>();
        for(var w:data.workers.values())if(w.role.equals("builder")&&w.site!=null){
            var site=data.sites.get(w.site);var e=IndustryEngine.find(server,w.id);
            if(site==null||!site.constructing||!w.active()||!site.owner.equals(w.owner)||e instanceof WorkerEntity npc&&(npc.isPassenger()||IndustryEngine.busy(npc)))free(server,data,w);
            else if(e instanceof WorkerEntity npc&&usable(npc,site)){if(!occupied.add(w.site))free(server,data,w);}
            else {var l=IndustryEngine.level(server,site);if(l!=null&&IndustryEngine.loaded(l,site))free(server,data,w);}
        }
        WATCH.keySet().removeIf(id->!data.sites.containsKey(id)||!data.sites.get(id).constructing);COOLDOWN.entrySet().removeIf(e->e.getValue()<server.getTickCount());
        var waiting=new ArrayList<>(data.sites.values());waiting.sort(Comparator.comparing(s->s.id));
        if(!waiting.isEmpty())Collections.rotate(waiting,-Math.floorMod(server.getTickCount()/10,waiting.size()));
        for(var site:waiting)if(site.constructing){
            var level=IndustryEngine.level(server,site);if(level==null||!IndustryEngine.loaded(level,site))continue;
            var npc=current(server,data,site);var p=workPoint(level,site);
            if(npc!=null&&p!=null){
                range(npc);double distance=npc.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5);var before=WATCH.get(site.id);
                if(before==null||before.cursor()!=site.constructionCursor||npc.blockPosition().distSqr(before.position())>=1)WATCH.put(site.id,new Watch(site.constructionCursor,npc.blockPosition(),server.getTickCount()));
                else if(distance>9&&server.getTickCount()-before.tick()>160){var w=data.workers.get(npc.getUUID());if(w!=null){free(server,data,w);COOLDOWN.put(w.id,server.getTickCount()+100);}WATCH.remove(site.id);npc=null;}
            }
            if(npc==null)assign(server,data,site,false);
        }
    }
    public static BlockPos workPoint(ServerLevel level,IndustryData.Site site){
        int version=site.blueprintVersion;var bounds=Blueprint.bounds(site.type,version);
        int z=BuildingOrientation.applies(version)?dev.frontier.industry.architecture.FinalIndustryArchitecture.bounds(site.type,version%100).minZ()-2:bounds.minZ()-2;
        for(int x:new int[]{0,-3,3,-6,6})for(int y:new int[]{0,1,-1,2,-2}){var local=BuildingOrientation.applies(version)?BuildingOrientation.rotate(new BlockPos(x,y,z),version):new BlockPos(x,y,z);var p=site.origin.offset(local);if(safe(level,p))return p;}return null;
    }
    public static boolean safe(ServerLevel level,BlockPos p){return level.hasChunkAt(p)&&level.getWorldBorder().isWithinBounds(p)&&level.getBlockState(p).getCollisionShape(level,p).isEmpty()&&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()&&level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP);}
    public static boolean navigate(WorkerEntity npc){
        if(!(npc.level() instanceof ServerLevel level))return false;var data=IndustryData.get(level.getServer());var w=data.workers.get(npc.getUUID());if(w==null||!w.role.equals("builder"))return false;var site=w.site==null?null:data.sites.get(w.site);if(site==null||!site.constructing){restore(npc);return false;}
        if(npc.tickCount%15!=0)return true;var target=workPoint(level,site);if(target==null)return true;range(npc);
        if(npc.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5)<=4){npc.getNavigation().stop();return true;}
        var waypoint=route(level,data,npc.blockPosition(),target);if(waypoint==null)return true;
        var path=npc.getNavigation().createPath(waypoint,0);if(path!=null&&path.canReach())npc.getNavigation().moveTo(path,1.1);return true;
    }
    private record Rect(int x1,int z1,int x2,int z2){boolean contains(double x,double z){return x>x1&&x<x2&&z>z1&&z<z2;}}
    private static boolean crosses(BlockPos a,BlockPos b,Rect r){
        double lo=0,hi=1;double[] from={a.getX(),a.getZ()},delta={b.getX()-a.getX(),b.getZ()-a.getZ()},min={r.x1,r.z1},max={r.x2,r.z2};
        for(int axis=0;axis<2;axis++){if(delta[axis]==0){if(from[axis]<=min[axis]||from[axis]>=max[axis])return false;}else{double p=(min[axis]-from[axis])/delta[axis],q=(max[axis]-from[axis])/delta[axis];lo=Math.max(lo,Math.min(p,q));hi=Math.min(hi,Math.max(p,q));if(hi<=lo)return false;}}return hi>0&&lo<1;
    }
    private static BlockPos route(ServerLevel level,IndustryData data,BlockPos start,BlockPos target){
        var obstacles=new ArrayList<Rect>();
        for(var site:data.sites.values())if(site.dimension.equals(level.dimension().location())&&site.origin.distSqr(start)<1024*1024){var b=Blueprint.bounds(site.type,site.blueprintVersion);var r=new Rect(site.origin.getX()+b.minX()-1,site.origin.getZ()+b.minZ()-1,site.origin.getX()+b.maxX()+1,site.origin.getZ()+b.maxZ()+1);if(!r.contains(start.getX(),start.getZ()))obstacles.add(r);}
        var nodes=new ArrayList<BlockPos>();nodes.add(start);nodes.add(target);
        for(var r:obstacles)for(int x:new int[]{r.x1-1,r.x2+1})for(int z:new int[]{r.z1-1,r.z2+1}){
            var p=new BlockPos(x,start.getY(),z);if(obstacles.stream().anyMatch(o->o.contains(x,z)))continue;
            for(int dy:new int[]{0,1,-1,2,-2})if(safe(level,p.offset(0,dy,0))){nodes.add(p.offset(0,dy,0));break;}
        }
        int n=nodes.size();var cost=new double[n];Arrays.fill(cost,Double.MAX_VALUE);cost[0]=0;var previous=new int[n];Arrays.fill(previous,-1);var visited=new boolean[n];
        for(int pass=0;pass<n;pass++){int at=-1;for(int i=0;i<n;i++)if(!visited[i]&&(at<0||cost[i]<cost[at]))at=i;if(at<0||cost[at]==Double.MAX_VALUE)break;if(at==1)break;visited[at]=true;
            for(int next=0;next<n;next++)if(!visited[next]){var a=nodes.get(at);var b=nodes.get(next);boolean blocked=false;for(var r:obstacles)if(crosses(a,b,r)){blocked=true;break;}if(blocked)continue;double candidate=cost[at]+Math.sqrt(a.distSqr(b));if(candidate<cost[next]){cost[next]=candidate;previous[next]=at;}}
        }
        if(previous[1]<0)return target;int next=1;while(previous[next]>0)next=previous[next];var end=nodes.get(next);double distance=Math.sqrt(start.distSqr(end));
        if(distance>48){double f=32/distance;var p=new BlockPos((int)Math.round(start.getX()+(end.getX()-start.getX())*f),start.getY(),(int)Math.round(start.getZ()+(end.getZ()-start.getZ())*f));for(int dy:new int[]{0,1,-1,2,-2})if(safe(level,p.offset(0,dy,0)))return p.offset(0,dy,0);}
        return end;
    }
}
