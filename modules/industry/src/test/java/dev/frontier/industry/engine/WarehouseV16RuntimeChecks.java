package dev.frontier.industry.engine;

import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.buildings.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Test-only live server checks of pagination, ownership and output transactions. */
public final class WarehouseV16RuntimeChecks {
 public static void run(net.minecraft.server.MinecraftServer server)throws Exception{
  WarehouseInventoryContractChecks.run();
  for(var type:SiteType.values())for(int q=0;q<4;q++){
   int version=16+100*q;var blocks=Blueprint.blocks(type,BlockPos.ZERO,version);var bounds=Blueprint.bounds(type,version);
   if(Blueprint.savedVersion(version)!=version||Blueprint.savedVersion(version-1)!=version-1)throw new IllegalStateException("blueprint version compatibility");
   var critical=Blueprint.criticalBlocks(type,BlockPos.ZERO,version);
   if((type==SiteType.POWER_STATION||type==SiteType.SUBSTATION)&&critical.size()!=2)throw new IllegalStateException("missing power cores "+type);
   for(var e:critical.entrySet())if(e.getValue()==null||!e.getValue().equals(blocks.get(e.getKey())))throw new IllegalStateException("incorrect power core "+type+" "+version+" "+e.getKey());
   for(var p:blocks.keySet())if(!bounds.contains(p.getX(),p.getY(),p.getZ()))throw new IllegalStateException("outside footprint "+type+" "+p);
   for(var job:dev.frontier.industry.architecture.FinalIndustryArchitecture.workplaces(type,version)){
    for(var p:List.of(job.stand(),job.stand().above())){
     var state=blocks.getOrDefault(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
     if(!state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,p).isEmpty())throw new IllegalStateException("blocked worker "+type+" "+job.id());
    }
   }
   if(type.isFactory()&&blocks.values().stream().filter(s->s.is(FunctionalBuildings.CONTAINER.get())).count()<60)throw new IllegalStateException("container count");
  }
  System.out.println("V16_LAYOUTS_COMPLETE: 7 buildings, four orientations, saved versions and workers");
  var level=server.overworld();var data=IndustryData.get(server);var owner=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes("warehouse-owner".getBytes()),"WarehouseOwner"));
  var guest=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes("warehouse-guest".getBytes()),"WarehouseGuest"));
  var origin=new BlockPos(-2000,80,-2000);var point=origin.offset(0,1,0);level.getChunk(point);level.setBlock(point,FunctionalBuildings.WAREHOUSE.get().defaultBlockState(),2);
  var site=new IndustryData.Site(data,UUID.randomUUID(),owner.getUUID(),SiteType.EQUIPMENT_FACTORY,level.dimension().location(),origin);site.blueprintVersion=16;site.constructing=true;data.sites.put(site.id,site);
  owner.moveTo(point.getX()+.5,point.getY(),point.getZ()+1.5,0,0);guest.moveTo(owner.getX(),owner.getY(),owner.getZ(),0,0);
  var originalBlocks=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
  try{
   if(!OutputWarehouse.allowed(owner,site,point)||OutputWarehouse.allowed(guest,site,point))throw new IllegalStateException("owner/guest access failed");
   OutputWarehouse.ensureInventories(site);var keys=new ArrayList<>(site.containerInventories.keySet());if(keys.size()<6)throw new IllegalStateException("missing physical containers");
   for(long key:keys){var pos=site.origin.offset(BuildingOrientation.rotate(BlockPos.of(key),site.blueprintVersion));level.getChunk(pos);originalBlocks.put(pos,level.getBlockState(pos));}
   if(OutputWarehouse.outputFits(level,site,new ItemStack(Items.IRON_INGOT,1)))throw new IllegalStateException("absent containers accepted output");
   for(var pos:originalBlocks.keySet())level.setBlock(pos,FunctionalBuildings.CONTAINER.get().defaultBlockState(),2);

   site.containerInventories.get(keys.get(0)).setStackInSlot(0,new ItemStack(Items.DIAMOND,3));
   site.containerInventories.get(keys.get(5)).setStackInSlot(0,new ItemStack(Items.EMERALD,7));
   var menu=OutputWarehouse.menu(1,owner,site,point);
   if(menu.getSlot(0).getItem().getCount()!=3||!menu.getSlot(0).getItem().is(Items.DIAMOND))throw new IllegalStateException("first container absent");
   menu.clicked(53,0,ClickType.PICKUP,owner);
   if(menu.getSlot(0).getItem().getCount()!=7||!menu.getSlot(0).getItem().is(Items.EMERALD))throw new IllegalStateException("next page missing container");
   if(!menu.getSlot(0).remove(2).is(Items.EMERALD)||site.containerInventories.get(keys.get(5)).getStackInSlot(0).getCount()!=5)throw new IllegalStateException("overview extraction not authoritative");
   menu.clicked(45,0,ClickType.PICKUP,owner);
   if(menu.quickMoveStack(owner,0).getCount()!=3||!site.containerInventories.get(keys.get(0)).getStackInSlot(0).isEmpty()||owner.getInventory().countItem(Items.DIAMOND)!=3)throw new IllegalStateException("shift-click duplicated or lost output");
   if(!menu.quickMoveStack(guest,0).isEmpty())throw new IllegalStateException("guest could shift-click");
   for(int i=0;i<30;i++)menu.clicked(53,0,ClickType.PICKUP,owner);
   int lastRow=(keys.size()-1)%5;
   site.containerInventories.get(keys.get(keys.size()-1)).setStackInSlot(8,new ItemStack(Items.GOLD_INGOT,4));
   if(menu.getSlot(lastRow*9+8).getItem().getCount()!=4)throw new IllegalStateException("last container absent");
   BlockPos manual=null;
   for(int x=-8;x<=8&&manual==null;x++)for(int z=-8;z<=8&&manual==null;z++){var pos=origin.offset(x,1,z);if(!site.containerInventories.containsKey(OutputWarehouse.relativeKey(site,pos)))manual=pos;}
   if(manual==null)throw new IllegalStateException("manual fixture not found");
   level.getChunk(manual);originalBlocks.put(manual,level.getBlockState(manual));level.setBlock(manual,FunctionalBuildings.CONTAINER.get().defaultBlockState(),2);
   if(!site.containerInventories.containsKey(OutputWarehouse.relativeKey(site,manual)))throw new IllegalStateException("manually placed container not indexed");
   site.containerInventories.get(OutputWarehouse.relativeKey(site,manual)).setStackInSlot(8,new ItemStack(Items.LAPIS_LAZULI,11));
   var added=OutputWarehouse.menu(3,owner,site,point);boolean found=false;
   for(int page=0;page<30;page++){for(int slot=0;slot<45;slot++)if(added.getSlot(slot).getItem().is(Items.LAPIS_LAZULI))found=true;added.clicked(53,0,ClickType.PICKUP,owner);}
   if(!found)throw new IllegalStateException("manual container missing from overview");
   site.inventory.setStackInSlot(IndustryRules.INPUTS,new ItemStack(Items.COPPER_INGOT,6));
   var legacy=OutputWarehouse.menu(2,owner,site,point);
   if(!legacy.getSlot(0).getItem().is(Items.COPPER_INGOT)||legacy.getSlot(0).remove(1).getCount()!=1||site.inventory.getStackInSlot(IndustryRules.INPUTS).getCount()!=5)throw new IllegalStateException("legacy output not recoverable");
   if(!OutputWarehouse.putOutputs(level,site,new ItemStack(Items.IRON_INGOT,10)))throw new IllegalStateException("output insertion failed");
   var saved=IndustryData.load(data.save(new net.minecraft.nbt.CompoundTag())).sites.get(site.id);
   if(saved==null||saved.containerInventories.get(keys.get(5)).getStackInSlot(0).getCount()!=5)throw new IllegalStateException("container save failed");
   System.out.println("WAREHOUSE_V16_CHECKS_COMPLETE: owner, guest, unfinished site, 2 pages, withdrawal, output and persistence");
   Files.writeString(Path.of("warehouse-v16-check.json"),"{\"passed\":true,\"containerCount\":"+keys.size()+"}");
  }finally{data.sites.remove(site.id);originalBlocks.forEach((pos,state)->level.setBlock(pos,state,2));level.setBlock(point,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);data.setDirty();}
 }
}
