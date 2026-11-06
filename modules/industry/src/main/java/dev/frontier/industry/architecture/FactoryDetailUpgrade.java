package dev.frontier.industry.architecture;
import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.engine.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Cosmetic overlay only for completed sites: old placement journals retain their original order. */
public final class FactoryDetailUpgrade {
    public static void change(Map<BlockPos,BlockState> map,SiteType t,int v){
        if(!t.isFactory()||v%100<6)return;int start=FinalIndustryArchitecture.start(t),h=t==SiteType.MISSILE_FACTORY?10:t==SiteType.VEHICLE_FACTORY?9:7;
        for(int line=0;line<3;line++){
            int c=FinalIndustryArchitecture.centres(t)[line];
            for(int x=c-2;x<=c+2;x++)for(int y=1;y<=3;y++)if(Math.abs(x-c)==2||y==3){var p=BuildingOrientation.rotate(new BlockPos(x,y,start-2),v);var s=map.get(p);if(s!=null&&s.is(Blocks.POLISHED_DEEPSLATE))map.remove(p);}
            map.remove(BuildingOrientation.rotate(new BlockPos(c,3,start-3),v));
            for(int y:new int[]{2,3})map.put(BuildingOrientation.rotate(new BlockPos(c+3,y,start-2),v),BuildingOrientation.rotate(FunctionalBuildings.EQUIPMENT.get("line_post").get().defaultBlockState(),v));
            map.put(BuildingOrientation.rotate(new BlockPos(c+3,3,start-3),v),BuildingOrientation.rotate(FunctionalBuildings.LINE_SIGNS.get(line).get().defaultBlockState(),v));
        }
        if(t==SiteType.VEHICLE_FACTORY||t==SiteType.MISSILE_FACTORY){var b=FinalIndustryArchitecture.baseBounds(t);int z1=b.minZ()+3,z2=b.maxZ()-3;var zs=new TreeSet<Integer>();for(int z=z1+3;z<=z2-2;z+=7)zs.add(z);zs.add(z2-2);
            for(int x:new int[]{b.minX()+3,b.maxX()-3})for(int z:zs)for(int y=1;y<h-1;y++){var p=BuildingOrientation.rotate(new BlockPos(x,y,z),v);var s=map.get(p);if(s!=null&&s.is(Blocks.POLISHED_DEEPSLATE))map.put(p,BuildingOrientation.rotate(FunctionalBuildings.EQUIPMENT.get("steel_column").get().defaultBlockState(),v));}
        }
    }
    public static Map<BlockPos,BlockState> expected(IndustryData.Site site){return expected(site.type,site.origin,site.blueprintVersion);}
    public static Map<BlockPos,BlockState> expected(SiteType type,BlockPos origin,int version){
        var raw=Blueprint.blocks(type,origin,version);if(!type.isFactory()||version%100<6||version%100>=10)return raw;
        var local=new LinkedHashMap<BlockPos,BlockState>();raw.forEach((p,s)->local.put(p.subtract(origin),s));change(local,type,version);var result=new LinkedHashMap<BlockPos,BlockState>();local.forEach((p,s)->result.put(origin.offset(p),s));return result;
    }
    public static void cleanup(MinecraftServer server){
        if(server.getTickCount()%40!=0)return;int budget=192;var data=IndustryData.get(server);
        for(var site:data.sites.values())if(!site.constructing&&site.type.isFactory()&&site.blueprintVersion%100>=6&&site.blueprintVersion%100<10){
            var l=IndustryEngine.level(server,site);if(l==null||!IndustryEngine.loaded(l,site))continue;var raw=Blueprint.blocks(site.type,site.origin,site.blueprintVersion);var target=expected(site);var all=new HashSet<BlockPos>(raw.keySet());all.addAll(target.keySet());boolean changed=false;
            for(var p:all){var before=raw.get(p);var after=target.get(p);if(Objects.equals(before,after))continue;if(budget<=0)return;var actual=l.getBlockState(p);boolean ours=before!=null&&actual.is(before.getBlock()),empty=before==null&&actual.isAir();
                if((ours||empty)&&ClaimPermissions.allows(l,site.owner,site.team,p)&&ClaimPermissions.allowsBreaking(l,site.owner,site.team,p)){l.setBlock(p,after==null?Blocks.AIR.defaultBlockState():after,Block.UPDATE_ALL);budget--;changed=true;}
            }if(changed){IndustryEngine.poll(l,site);data.setDirty();}
        }
    }
}
