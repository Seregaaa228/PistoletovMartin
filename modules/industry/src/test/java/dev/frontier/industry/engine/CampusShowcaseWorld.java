package dev.frontier.industry.engine;

import dev.frontier.industry.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Generates a separate, saved showcase; never shipped in the gameplay JAR. */
@Mod.EventBusSubscriber(modid=IndustryMod.ID)
public final class CampusShowcaseWorld {
    @SubscribeEvent public static void started(ServerStartedEvent event)throws Exception {
        if(!Boolean.getBoolean("frontier.showcase"))return;
        var server=event.getServer();var level=server.overworld();var data=IndustryData.get(server);
        var owner=UUID.nameUUIDFromBytes("frontier-showcase-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final int version=Blueprint.CURRENT_VERSION;
        if(Boolean.getBoolean("frontier.furnitureChecks")) FurnitureLayoutChecks.run(server);
        if(Boolean.getBoolean("frontier.warehouseProbe")) WarehouseV16RuntimeChecks.run(server);
        var export=new com.google.gson.JsonArray();
        var notes=new StringBuilder("Frontier Industry v"+version+" — все 7 зданий\n\n");
        for(var type:SiteType.values()){
            int n=type.ordinal();var origin=new BlockPos((n%4)*110,-60,(n/4)*120);
            var bounds=Blueprint.bounds(type,version);
            for(int x=(origin.getX()+bounds.minX()-3)>>4;x<=((origin.getX()+bounds.maxX()+3)>>4);x++)
                for(int z=(origin.getZ()+bounds.minZ()-3)>>4;z<=((origin.getZ()+bounds.maxZ()+3)>>4);z++)level.getChunk(x,z);
            var blocks=Blueprint.blocks(type,origin,version);
            blocks.forEach((p,s)->level.setBlock(p,s,Block.UPDATE_CLIENTS));
            var site=new IndustryData.Site(data,UUID.randomUUID(),owner,type,level.dimension().location(),origin);
            site.blueprintVersion=version;site.constructing=false;site.kitPaid=true;site.energy=20000;
            data.sites.put(site.id,site);
            var missing=blocks.entrySet().stream().filter(e->level.getBlockState(e.getKey()).getBlock()!=e.getValue().getBlock()).toList();
            if(!missing.isEmpty())throw new IllegalStateException("Incomplete showcase "+type+": "+missing.subList(0,Math.min(5,missing.size())));
            for(var cell:blocks.entrySet()){var row=new com.google.gson.JsonArray();row.add(cell.getKey().getX());row.add(cell.getKey().getY());row.add(cell.getKey().getZ());row.add(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(cell.getValue().getBlock()).toString());export.add(row);}
            if(type==SiteType.EQUIPMENT_FACTORY){
                var gunIds=blocks.values().stream().filter(s->s.getBlock() instanceof dev.frontier.industry.buildings.GunDisplayBlock display)
                    .map(s->((dev.frontier.industry.buildings.GunDisplayBlock)s.getBlock()).gunId()).collect(java.util.stream.Collectors.toSet());
                if(!gunIds.containsAll(java.util.Set.of("tacz:ak47","tacz:rpg7","tacz:glock_17")))throw new IllegalStateException("TaCZ GunId display stands missing: "+gunIds);
            }
            if(type.isFactory()&&blocks.values().stream().filter(s->s.is(dev.frontier.industry.buildings.FunctionalBuildings.CONTAINER.get())).count()<60)throw new IllegalStateException("Decorative warehouse in showcase");
            notes.append(type.title()).append(": ").append(origin.toShortString()).append("\n");
        }
        data.setDirty();level.setDefaultSpawnPos(new BlockPos(0,-59,-45),0);
        level.setDayTime(6000);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        server.setDefaultGameType(GameType.CREATIVE);
        Files.writeString(Path.of("SHOWCASE.txt"),notes.toString());Files.writeString(Path.of("SHOWCASE_BLOCKS.json"),export.toString());
        System.out.println("CAMPUS_SHOWCASE_COMPLETE: "+data.sites.size()+" buildings verified");
        server.halt(false);
    }
}
