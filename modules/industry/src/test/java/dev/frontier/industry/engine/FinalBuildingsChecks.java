package dev.frontier.industry.engine;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.FinalIndustryArchitecture;
import dev.frontier.industry.buildings.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

public final class FinalBuildingsChecks {
    public static Map<String,Boolean> run(MinecraftServer server)throws Exception {
        Map<String,Boolean> checks=new LinkedHashMap<>();var level=server.overworld();var data=IndustryData.get(server);
        var player=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:CodexVerifier".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"CodexVerifier"));
        JsonArray scenes=new JsonArray(),jobsExport=new JsonArray();
        checks.put("final production orientation dispatcher loaded",Arrays.stream(Blueprint.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("blocks$beforeV6")));
        for(var t:SiteType.values())for(int quarter=0;quarter<4;quarter++) {
            int version=6+100*quarter;String prefix=t+"/"+quarter+" ";
            BlockPos origin=new BlockPos(1024+t.ordinal()*96,80,quarter*96+7);
            var map=Blueprint.blocks(t,origin,version);var b=Blueprint.bounds(t,version);
            checks.put(prefix+"all generated blocks are in rotated bounds",map.keySet().stream().allMatch(p->b.contains(p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ())));
            var entry=Blueprint.entrance(t,origin,version);var controller=Blueprint.controller(t,origin,version);
            checks.put(prefix+"controller survives rotation",map.get(controller)!=null&&map.get(controller).is(IndustryContent.controller(t)));
            checks.put(prefix+"entrance has two clear blocks",!map.containsKey(entry)&&!map.containsKey(entry.above()));
            for(var e:map.entrySet())level.setBlock(e.getKey(),e.getValue(),Block.UPDATE_CLIENTS);
            data.sites.values().removeIf(s->s.origin.equals(origin));
            var site=new IndustryData.Site(data,UUID.randomUUID(),player.getUUID(),t,level.dimension().location(),origin);
            site.blueprintVersion=version;site.loaded=site.controller=site.powered=site.machineryIntact=true;site.matching=site.total=map.size();site.energy=20000;data.sites.put(site.id,site);
            player.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);
            checks.put(prefix+"owner can use local terminal",FunctionalBuildings.authorised(player,site));
            var stranger=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes("stranger".getBytes()),"OtherVerifier"));stranger.moveTo(player.position());
            checks.put(prefix+"foreign player cannot use local terminal",!FunctionalBuildings.authorised(stranger,site));
            site.constructing=true;checks.put(prefix+"unfinished terminal is locked",!FunctionalBuildings.authorised(player,site));site.constructing=false;
            var jobs=FinalIndustryArchitecture.workplaces(t,version);
            checks.put(prefix+"stable unique workplace ids",jobs.stream().map(j->j.id()).distinct().count()==jobs.size());
            checks.put(prefix+"documented staffing count",jobs.size()==(t.isFactory()?15:t==SiteType.RESEARCH_CENTER?16:t==SiteType.POWER_STATION?6:4));
            var npc=new Villager(EntityType.VILLAGER,level);npc.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(96);npc.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);
            npc.setOnGround(true);
            boolean clear=true,reach=true;List<String> failures=new ArrayList<>();
            for(var job:jobs) {
                var p=origin.offset(job.stand());boolean free=level.noCollision(npc,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8))&&!level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty();
                clear &= free;var path=npc.getNavigation().createPath(p,0);boolean reachable=path!=null&&path.canReach();reach &= reachable;if(!free||!reachable)failures.add(job.id());
                if(quarter==0){var j=new JsonObject();j.addProperty("site",t.name());j.addProperty("id",job.id());j.addProperty("role",job.role());j.addProperty("line",job.line());j.addProperty("equipment",job.equipment().toShortString());j.addProperty("stand",job.stand().toShortString());j.addProperty("facing",job.facing().getName());j.addProperty("capacity",job.capacity());jobsExport.add(j);}
            }
            checks.put(prefix+"all standing volumes have support and clearance",clear);checks.put(prefix+"native navigation reaches every workplace at follow-range96",reach);
            if(!failures.isEmpty())System.out.println("WORKPLACE_FAILURE "+prefix+failures);npc.discard();
            var critical=Blueprint.criticalBlocks(t,origin,version);checks.put(prefix+"critical machinery matches the real map",critical.entrySet().stream().allMatch(e->e.getValue()!=null&&e.getValue().equals(map.get(e.getKey()))));
            long windows=map.values().stream().filter(s->s.is(Blocks.GLASS)||s.is(Blocks.LIGHT_BLUE_STAINED_GLASS)).count();
            checks.put(prefix+"solid material dominates glazing",windows*3<map.size());
            checks.put(prefix+"one correctly specialised main sign",map.values().stream().filter(s->s.is(FunctionalBuildings.SIGNS.get(t).get())).count()==1);
            var scene=new JsonObject();scene.addProperty("type",t.name());scene.addProperty("version",version);scene.addProperty("site",site.id.toString());scene.addProperty("x",origin.getX());scene.addProperty("y",origin.getY());scene.addProperty("z",origin.getZ());scene.addProperty("blocks",map.size());scenes.add(scene);
            if(t.isFactory()) {
                checks.put(prefix+"exactly three separate controls",map.values().stream().filter(s->s.is(FunctionalBuildings.LINE_CONTROLLER.get())).count()==3);
                checks.put(prefix+"three physical line markers",map.values().stream().filter(s->s.getBlock() instanceof BuildingNameplate plate&&plate.line>=0).count()==3);
                int total=FinalIndustryArchitecture.belts(t,version).size(),length=FinalIndustryArchitecture.end(t)-FinalIndustryArchitecture.start(t)+1;
                checks.put(prefix+"three full width lines at least16 long",length>=16&&total==9*length);
                var switches=FactoryLinesData.get(server);FunctionalBuildings.updateBelts(server);
                boolean allTransport=true;
                for(int line=0;line<3;line++) {
                    int cx=FinalIndustryArchitecture.centres(t)[line];var local=BuildingOrientation.rotate(new BlockPos(cx,1,FinalIndustryArchitecture.start(t)+1),version);var p=origin.offset(local);
                    var facing=BuildingOrientation.rotate(Direction.SOUTH,version);var item=new ItemEntity(level,p.getX()+.5,p.getY()+.76,p.getZ()+.5,new ItemStack(Items.COPPER_INGOT,3));
                    var initial=item.position();level.addFreshEntity(item);
                    for(int n=0;n<20;n++){item.tick();var at=item.blockPosition();if(level.getBlockState(at).is(FunctionalBuildings.BELT.get()))ProductionBelt.carry(level.getBlockState(at),level,at,item);}
                    allTransport &= item.position().subtract(initial).dot(new net.minecraft.world.phys.Vec3(facing.getStepX(),0,facing.getStepZ()))>.5&&item.getItem().getCount()==3;item.discard();
                }
                checks.put(prefix+"real items move on all three lines without duplication",allTransport);
                switches.toggle(site.id,1);FunctionalBuildings.updateBelts(server);boolean independent=true;
                for(int line=0;line<3;line++){var p=origin.offset(BuildingOrientation.rotate(new BlockPos(FinalIndustryArchitecture.centres(t)[line],1,FinalIndustryArchitecture.start(t)+1),version));independent &=level.getBlockState(p).getValue(BlockStateProperties.POWERED)==(line!=1);}
                checks.put(prefix+"B can stop while A and C keep moving",independent);
                var roundTrip=FactoryLinesData.load(switches.save(new CompoundTag()));checks.put(prefix+"independent line switches survive NBT reload",roundTrip.enabled(site.id,0)&&!roundTrip.enabled(site.id,1)&&roundTrip.enabled(site.id,2));switches.toggle(site.id,1);
                site.powered=false;FunctionalBuildings.updateBelts(server);checks.put(prefix+"loss of power stops every lane",FinalIndustryArchitecture.belts(t,version).stream().noneMatch(p->level.getBlockState(origin.offset(p)).getValue(BlockStateProperties.POWERED)));site.powered=true;
            }
            if(t==SiteType.POWER_STATION){checks.put(prefix+"three real open flues",FinalIndustryArchitecture.smokePoints(version).stream().allMatch(p->level.getBlockState(origin.offset(p)).isAir()&&level.getBlockState(origin.offset(p).below()).isAir()));}
            UUID worker=UUID.randomUUID(),second=UUID.randomUUID();var reservations=WorkstationReservations.get(server);String job=jobs.get(0).id();
            boolean first=reservations.reserve(server,site.id,job,worker);checks.put(prefix+"one reservation per physical workstation",first&&!reservations.reserve(server,site.id,job,second));
            var restoredReservations=WorkstationReservations.load(reservations.save(new CompoundTag()));checks.put(prefix+"workplace reservation survives NBT reload",!restoredReservations.reserve(server,site.id,job,second));reservations.release(site.id,job,worker);
            checks.put(prefix+"rotated version is preserved on load",Blueprint.savedVersion(version)==version);
        }
        // Actual L junction and a blocked destination, on a separate disposable fixture.
        BlockPos corner=new BlockPos(1900,80,500);
        for(int x=0;x<=4;x++)for(int z=-1;z<=2;z++)level.setBlock(corner.offset(x,0,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
        level.setBlock(corner.offset(0,1,0),FunctionalBuildings.belt(Direction.SOUTH).setValue(BlockStateProperties.POWERED,true),2);
        for(int x=0;x<=2;x++)level.setBlock(corner.offset(x,1,1),FunctionalBuildings.belt(Direction.EAST).setValue(BlockStateProperties.POWERED,true),2);
        for(int y=1;y<=2;y++)level.setBlock(corner.offset(3,y,1),Blocks.STONE.defaultBlockState(),2);
        var item=new ItemEntity(level,corner.getX()+.5,corner.getY()+1.76,corner.getZ()+.5,new ItemStack(Items.COPPER_INGOT,7));level.addFreshEntity(item);
        for(int n=0;n<120;n++){item.tick();var p=item.blockPosition();var state=level.getBlockState(p);if(state.is(FunctionalBuildings.BELT.get()))ProductionBelt.carry(state,level,p,item);}
        checks.put("belt L junction changes physical item direction",item.getX()>corner.getX()+2&&item.getZ()>corner.getZ()+1&&item.getZ()<corner.getZ()+2);
        checks.put("blocked belt end keeps the same item stack",item.isAlive()&&item.getItem().getCount()==7&&item.getX()<corner.getX()+3);item.discard();
        data.setDirty();Path out=Path.of("architecture-check");Files.createDirectories(out);var gson=new GsonBuilder().setPrettyPrinting().create();Files.writeString(out.resolve("final-scenes.json"),gson.toJson(scenes));Files.writeString(out.resolve("workstations.json"),gson.toJson(jobsExport));
        return checks;
    }
}
