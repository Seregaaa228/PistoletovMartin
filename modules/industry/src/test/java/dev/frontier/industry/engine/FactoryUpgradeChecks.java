package dev.frontier.industry.engine;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.*;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.construction.Construction;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.item.*;

public final class FactoryUpgradeChecks {
    public static Map<String,Boolean> run(net.minecraft.server.MinecraftServer server){
        var checks=new LinkedHashMap<String,Boolean>();var l=server.overworld();var data=IndustryData.get(server);
        for(var t:SiteType.values())if(t.isFactory())for(int q=0;q<4;q++){
            int v=9+q*100;var o=new BlockPos(9000+t.ordinal()*96,80,4000+q*96);var b=Blueprint.bounds(t,v);for(int x=(o.getX()+b.minX())>>4;x<=((o.getX()+b.maxX())>>4);x++)for(int z=(o.getZ()+b.minZ())>>4;z<=((o.getZ()+b.maxZ())>>4);z++)l.setChunkForced(x,z,true);
            var raw=Blueprint.blocks(t,o,v);raw.forEach((p,s)->l.setBlock(p,s,2));var site=new IndustryData.Site(data,UUID.randomUUID(),UUID.randomUUID(),t,l.dimension().location(),o);site.blueprintVersion=v;site.loaded=true;site.controller=true;site.total=site.matching=raw.size();data.sites.put(site.id,site);site.inventory.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,12));var order=new IndustryData.Order("minecraft:diamond_sword",2);order.reserved=true;order.paid.add(new ItemStack(Items.IRON_INGOT,3));site.orders.add(order);
            var target=FactoryDetailUpgrade.expected(site);var arch=o.offset(BuildingOrientation.rotate(new BlockPos(FinalIndustryArchitecture.centres(t)[0],3,FinalIndustryArchitecture.start(t)-2),v));
            site.constructing=true;int hash=Construction.plan(site).hashCode();FactoryDetailUpgrade.cleanup(server);checks.put("legacy cosmetic "+t+"/"+q+" active placement journal unchanged",hash==Construction.plan(site).hashCode()&&l.getBlockState(arch).is(Blocks.POLISHED_DEEPSLATE));site.constructing=false;
            for(int i=0;i<12;i++)FactoryDetailUpgrade.cleanup(server);
            boolean geometry=true;for(var e:raw.entrySet()){var expected=target.get(e.getKey());geometry&=expected==null?l.getBlockState(e.getKey()).isAir():Construction.equivalent(l.getBlockState(e.getKey()),expected);}
            for(var e:target.entrySet())geometry&=Construction.equivalent(l.getBlockState(e.getKey()),e.getValue());
            checks.put("legacy cosmetic "+t+"/"+q+" ready arches replaced without rebuilding shell",geometry);
            checks.put("legacy cosmetic "+t+"/"+q+" inventory and paid order retained",site.inventory.getStackInSlot(0).getCount()==12&&site.orders.peek().reserved&&site.orders.peek().paid.get(0).getCount()==3);data.sites.remove(site.id);
        }return checks;
    }
}
