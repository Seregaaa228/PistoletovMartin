package dev.frontier.industry.engine;

import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.OperationalArchitecture;
import dev.frontier.industry.buildings.*;
import dev.frontier.industry.research.Research;
import dev.frontier.industry.research.ResearchData;
import dev.frontier.industry.research.Technology;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** Native interaction, transport and NPC access checks in the isolated server world. */
public final class OperationalBuildingsChecks {
    public static Map<String,Boolean> run(MinecraftServer server) {
        Map<String,Boolean> checks=new LinkedHashMap<>();var level=server.overworld();var data=IndustryData.get(server);
        var owner=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"WorkshopTester"));
        var stranger=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"OtherTester"));
        for(var type:SiteType.values()) {
            BlockPos origin=new BlockPos(type.ordinal()*48,80,0);
            var map=Blueprint.blocks(type,origin,5);var bounds=Blueprint.bounds(type,5);
            checks.put(type+" v5 fits its declared bounds",map.keySet().stream().allMatch(p->bounds.contains(p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ())));
            for(var e:map.entrySet())level.setBlock(e.getKey(),e.getValue(),Block.UPDATE_CLIENTS);
            var site=new IndustryData.Site(data,UUID.randomUUID(),owner.getUUID(),type,level.dimension().location(),origin);
            site.blueprintVersion=5;site.loaded=site.controller=site.powered=site.machineryIntact=true;site.total=site.matching=map.size();site.energy=10000;data.sites.put(site.id,site);
            int expected=type==SiteType.RESEARCH_CENTER||type==SiteType.MISSILE_FACTORY?10:type.isFactory()?8:0;
            checks.put(type+" planned workplace count",OperationalArchitecture.workplaces(type).size()==expected);
            boolean stands=true;
            Set<BlockPos> walkable=new HashSet<>();
            int minX=Blueprint.bounds(type,5).minX(),maxX=Blueprint.bounds(type,5).maxX();
            int minZ=Blueprint.bounds(type,5).minZ(),maxZ=Blueprint.bounds(type,5).maxZ();
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
                BlockPos feet=origin.offset(x,1,z);
                boolean open=level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()
                    &&level.getBlockState(feet.above()).getCollisionShape(level,feet.above()).isEmpty()
                    &&!level.getBlockState(feet.below()).getCollisionShape(level,feet.below()).isEmpty();
                if(open)walkable.add(new BlockPos(x,1,z));
            }
            Set<BlockPos> reachable=new HashSet<>();
            ArrayDeque<BlockPos> todo=new ArrayDeque<>();
            BlockPos localEntry=Blueprint.entrance(type,origin,5).subtract(origin);
            if(walkable.contains(localEntry)){reachable.add(localEntry);todo.add(localEntry);}
            while(!todo.isEmpty()) {
                BlockPos current=todo.removeFirst();
                for(Direction direction:Direction.Plane.HORIZONTAL) {
                    BlockPos next=current.relative(direction);
                    if(walkable.contains(next)&&reachable.add(next))todo.addLast(next);
                }
            }
            var npc=new Villager(EntityType.VILLAGER,level);
            var entry=Blueprint.entrance(type,origin,5);npc.moveTo(entry.getX()+.5,entry.getY(),entry.getZ()+.5,0,0);
            level.addFreshEntity(npc);
            for(var job:OperationalArchitecture.workplaces(type)) {
                BlockPos p=origin.offset(job.stand());
                boolean clear=level.noCollision(npc,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+1.95,p.getZ()+.8))
                    && !level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty();
                stands &= clear;
                boolean routeReachable=clear&&reachable.contains(job.stand());
                checks.put(type+" workplace "+job.part()+" at "+job.stand()+" has clear NPC route",routeReachable);
            }
            checks.put(type+" NPC standing spaces have support and two-block clearance",stands);
            checks.put(type+" all workplace cells flood-fill from entrance",OperationalArchitecture.workplaces(type).stream().allMatch(job->reachable.contains(job.stand())));
            npc.discard();
            checks.put(type+" one Russian nameplate",map.values().stream().filter(s->s.is(FunctionalBuildings.SIGNS.get(type).get())).count()==1);
            if(type==SiteType.RESEARCH_CENTER) {
                checks.put("research stair has a solid top landing",level.getBlockState(origin.offset(-6,6,6)).is(Blocks.SMOOTH_QUARTZ));
                BlockPos terminal=origin.offset(-3,7,5);owner.moveTo(terminal.getX()+.5,terminal.getY(),terminal.getZ()+1.5,0,0);
                checks.put("research server is interactive",FunctionalBuildings.terminal(level.getBlockState(terminal)));
                var click=new PlayerInteractEvent.RightClickBlock(owner,InteractionHand.MAIN_HAND,terminal,new BlockHitResult(new Vec3(terminal.getX()+.5,terminal.getY()+.5,terminal.getZ()+.5),Direction.NORTH,terminal,false));
                FunctionalBuildings.interact(click);
                // Dedicated-server smoke verifies the authoritative click gate. The actual
                // NetworkHooks GUI handshake requires a real client connection and is covered
                // by the client acceptance checklist, not by FakePlayer.
                checks.put("server click authorizes native research menu",click.isCanceled()&&FunctionalBuildings.authorised(owner,site));
                owner.closeContainer();stranger.moveTo(terminal.getX()+.5,terminal.getY(),terminal.getZ()-1,0,0);
                checks.put("foreign player cannot use the terminal",!FunctionalBuildings.authorised(stranger,site));
                site.constructing=true;checks.put("unfinished research terminal is denied",!FunctionalBuildings.authorised(owner,site));site.constructing=false;
                owner.closeContainer();
                owner.moveTo(origin.getX()+.5,origin.getY()+1,origin.getZ()+.5,0,0);
                owner.getInventory().add(new ItemStack(Items.EMERALD,12));
                owner.getInventory().add(new ItemStack(Items.REDSTONE,8));
                IndustryEngine.action(owner,site.id,"research_supply",Technology.SMALL_ARMS.id,0);
                boolean emeralds=false,redstone=false;
                for(int slot=0;slot<IndustryRules.INPUTS;slot++) {
                    var stack=site.inventory.getStackInSlot(slot);
                    emeralds|=stack.is(Items.EMERALD)&&stack.getCount()>=IndustryConfig.emeralds(Technology.SMALL_ARMS);
                    redstone|=stack.is(Items.REDSTONE)&&stack.getCount()>=IndustryConfig.redstone(Technology.SMALL_ARMS);
                }
                checks.put("research terminal transfers selected technology materials",emeralds&&redstone);
                IndustryEngine.staff(owner,data,site,"scientist");
                var scientist=data.workers.values().stream().filter(w->w.owner.equals(owner.getUUID())&&site.id.equals(w.site)&&w.role.equals("scientist")).findFirst().orElse(null);
                var scientistEntity=scientist==null?null:level.getEntity(scientist.id);
                checks.put("research terminal creates a funded scientist workplace",scientistEntity!=null&&scientistEntity.isAlive());
                if(scientistEntity!=null)scientistEntity.moveTo(origin.getX()-3.5,origin.getY()+1,origin.getZ()-4.5,0,0);
                IndustryEngine.action(owner,site.id,"research_start",Technology.SMALL_ARMS.id,0);
                var country=ResearchData.get(server).country("player:"+owner.getUUID());
                boolean started=Technology.SMALL_ARMS.id.equals(country.active);
                checks.put("research terminal starts the selected paid technology",started);
                if(started)Research.tick(server);
                checks.put("powered research advances with a live scientist",started&&country.progress==1&&site.energy==9984);
                owner.closeContainer();
            }
            if(type.isFactory()) {
                FunctionalBuildings.updateBelts(server);
                var p=origin.offset(OperationalArchitecture.belts(type).get(0));
                checks.put(type+" powered site starts belt",level.getBlockState(p).getValue(BlockStateProperties.POWERED));
                var item=new ItemEntity(level,p.getX()+.5,p.getY()+.76,p.getZ()+.2,new ItemStack(Items.IRON_INGOT,4));
                double start=item.getZ();level.addFreshEntity(item);
                for(int i=0;i<20;i++){item.tick();var at=item.blockPosition();if(level.getBlockState(at).is(FunctionalBuildings.BELT.get()))ProductionBelt.carry(level.getBlockState(at),level,at,item);}
                checks.put(type+" belt actually carries items without duplicating them",item.getZ()>start+.5&&item.getItem().getCount()==4);item.discard();
                site.powered=false;FunctionalBuildings.updateBelts(server);checks.put(type+" power loss stops belt",!level.getBlockState(p).getValue(BlockStateProperties.POWERED));
                site.powered=true;site.constructing=true;FunctionalBuildings.updateBelts(server);checks.put(type+" construction keeps belt stopped",!level.getBlockState(p).getValue(BlockStateProperties.POWERED));site.constructing=false;
            }
            if(type==SiteType.POWER_STATION) {
                for(int x:new int[]{-6,0})checks.put("power chimney "+x+" has an open outlet with a complete rim",
                    level.getBlockState(origin.offset(x,26,7)).isAir() && level.getBlockState(origin.offset(x,27,7)).isAir()
                    &&level.getBlockState(origin.offset(x-1,26,7)).is(Blocks.POLISHED_DEEPSLATE)&&level.getBlockState(origin.offset(x+1,26,7)).is(Blocks.POLISHED_DEEPSLATE));
            }
        }
        checks.put("saved v4 remains v4 after upgrading",Blueprint.savedVersion(4)==4&&Blueprint.bounds(SiteType.POWER_STATION,4).maxY()==24);
        return checks;
    }
}
