package dev.frontier.industry.engine;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.*;
import dev.frontier.industry.architecture.*;
import dev.frontier.industry.buildings.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Real handler/menu checks: two access points share paid output; an intruder cannot withdraw it. */
public final class CampusWarehouseChecks {
    public static Map<String,Boolean> run(net.minecraft.server.MinecraftServer server)throws Exception{
        var checks=new LinkedHashMap<String,Boolean>();var level=server.overworld();var data=IndustryData.get(server);var rows=new JsonArray();
        checks.put("v11 Engineer's Decor required registry present",net.minecraftforge.fml.ModList.get().isLoaded("engineersdecor"));
        checks.put("default blueprint dispatch includes the complete current campus",java.util.Arrays.stream(SiteType.values()).allMatch(t->Blueprint.bounds(t).equals(Blueprint.bounds(t,13))&&Blueprint.blocks(t,BlockPos.ZERO).equals(Blueprint.blocks(t,BlockPos.ZERO,13))));
        int n=0;
        for(var type:SiteType.values())if(type.isFactory())for(int q=0;q<4;q++){
            int v=13+q*100;var o=new BlockPos(-9000+n++*100,80,9500);var b=Blueprint.bounds(type,v);
            for(int x=(o.getX()+b.minX()-4)>>4;x<=((o.getX()+b.maxX()+4)>>4);x++)for(int z=(o.getZ()+b.minZ()-4)>>4;z<=((o.getZ()+b.maxZ()+4)>>4);z++)level.setChunkForced(x,z,true);
            var blocks=Blueprint.blocks(type,o,v);blocks.forEach((p,s)->level.setBlock(p,s,2));
            var player=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes(("warehouse/"+n).getBytes()),"Warehouse"+n));player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            var stranger=FakePlayerFactory.get(level,new GameProfile(UUID.nameUUIDFromBytes(("intruder/"+n).getBytes()),"Intruder"+n));
            var site=new IndustryData.Site(data,UUID.randomUUID(),player.getUUID(),type,level.dimension().location(),o);site.blueprintVersion=v;data.sites.put(site.id,site);
            var points=CampusIndustryArchitecture.warehousePoints(type,v);var p=o.offset(points.get(0));player.moveTo(p.getX()+.5,p.getY(),p.getZ()+1.5,0,0);stranger.moveTo(player.getX(),player.getY(),player.getZ(),0,0);
            var storage=OutputWarehouse.storage(player,site,p);String key="warehouse "+type+"/"+v+" ";
            checks.put(key+"two warehouse terminals exist",points.size()==2&&points.stream().allMatch(pos->blocks.get(o.offset(pos)).is(FunctionalBuildings.WAREHOUSE.get())));
            var walker=new net.minecraft.world.entity.npc.Villager(net.minecraft.world.entity.EntityType.VILLAGER,level);walker.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE).setBaseValue(128);var entrance=Blueprint.entrance(type,o,v);walker.moveTo(entrance.getX()+.5,entrance.getY(),entrance.getZ()+.5,0,0);walker.setOnGround(true);
            boolean reachable=true;for(var target:points){var local=BuildingOrientation.rotate(new BlockPos(0,0,-1),v);var path=walker.getNavigation().createPath(o.offset(target).offset(local),0);reachable&=path!=null&&path.canReach();}walker.discard();checks.put(key+"native path from entrance to both warehouse terminals",reachable);
            checks.put(key+"recognisable product mockup physically placed",blocks.values().stream().filter(s->s.getBlock() instanceof ProcessEquipment part&&(part.kind.startsWith("mock_")||part.kind.endsWith("_display"))).count()>=8);
            checks.put(key+"uses actual Engineer's Decor blocks",blocks.values().stream().anyMatch(s->net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock()).getNamespace().equals("engineersdecor")));
            if(q==0){
                var probe=o.offset(0,0,200);var current=Blueprint.bounds(type,13);
                for(int x=(probe.getX()+current.minX()-2)>>4;x<=((probe.getX()+current.maxX()+2)>>4);x++)for(int z=(probe.getZ()+current.minZ()-2)>>4;z<=((probe.getZ()+current.maxZ()+2)>>4);z++)level.setChunkForced(x,z,true);
                for(int x=current.minX()-2;x<=current.maxX()+2;x++)for(int z=current.minZ()-2;z<=current.maxZ()+2;z++)level.setBlock(probe.offset(x,-1,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),2);
                player.moveTo(probe.getX(),probe.getY(),probe.getZ()+current.minZ()-3,0,0);var obstacle=probe.offset(0,0,ReworkedIndustryArchitecture.baseBounds(type).maxZ()+8);level.setBlock(obstacle,net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(),2);
                var rejected=IndustryEngine.place(player,type,probe);checks.put(key+"deployer rejects occupied warehouse annex",rejected==null);if(rejected!=null)data.sites.remove(rejected);level.setBlock(obstacle,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);
                if(type==SiteType.VEHICLE_FACTORY){var road=probe.offset(0,0,current.minZ()+1);level.setBlock(road,net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState(),2);var denied=IndustryEngine.place(player,type,probe);checks.put(key+"deployer rejects occupied exit road",denied==null);if(denied!=null)data.sites.remove(denied);level.setBlock(road,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);}
                var accepted=IndustryEngine.place(player,type,probe);checks.put(key+"deployer accepts the same empty campus",accepted!=null);if(accepted!=null)data.sites.remove(accepted);player.moveTo(p.getX()+.5,p.getY(),p.getZ()+1.5,0,0);
            }
            checks.put(key+"owner allowed, stranger denied",storage.stillValid(player)&&!storage.stillValid(stranger)&&!OutputWarehouse.allowed(stranger,site,p));
            site.inventory.setStackInSlot(0,new ItemStack(Items.IRON_INGOT,12));var order=new IndustryData.Order("minecraft:diamond",2);order.reserved=true;order.paid.add(new ItemStack(Items.IRON_INGOT,3));site.orders.add(order);
            var output=new ItemStack(Items.DIAMOND,10);output.getOrCreateTag().putString("batch","paid-test");boolean deposited=StockTransactions.putOutputs(site.inventory,output);
            checks.put(key+"production output appears automatically",deposited&&storage.getItem(0).getCount()==10&&storage.getItem(0).getOrCreateTag().getString("batch").equals("paid-test"));
            var intruder=OutputWarehouse.storage(stranger,site,p);checks.put(key+"unauthorised extraction cannot change stock",intruder.removeItem(0,10).isEmpty()&&intruder.getItem(0).isEmpty()&&site.inventory.getStackInSlot(12).getCount()==10);
            var menu=OutputWarehouse.menu(1,player,site,p);player.getInventory().clearContent();var moved=menu.quickMoveStack(player,0);
            checks.put(key+"standard shift-click withdraws once",!moved.isEmpty()&&site.inventory.getStackInSlot(12).isEmpty()&&player.getInventory().countItem(Items.DIAMOND)==10);
            checks.put(key+"warehouse rejects inserting inventory items",!menu.getSlot(0).mayPlace(new ItemStack(Items.GOLD_INGOT))&&!storage.canPlaceItem(0,new ItemStack(Items.GOLD_INGOT)));
            menu.setCarried(new ItemStack(Items.GOLD_INGOT,8));menu.clicked(0,0,ClickType.PICKUP,player);
            checks.put(key+"server click rejects depositing carried items",menu.getCarried().getCount()==8&&site.inventory.getStackInSlot(12).isEmpty());menu.setCarried(ItemStack.EMPTY);
            player.getInventory().setItem(0,new ItemStack(Items.GOLD_INGOT,4));menu.quickMoveStack(player,45);
            checks.put(key+"shift-click cannot insert player stock",player.getInventory().getItem(0).getCount()==4&&site.inventory.getStackInSlot(12).isEmpty());
            StockTransactions.putOutputs(site.inventory,output.copyWithCount(7));var saved=IndustryData.load(data.save(new net.minecraft.nbt.CompoundTag()));var loaded=saved.sites.get(site.id);data.sites.put(site.id,loaded);
            checks.put(key+"old open menu invalid after data replacement",!storage.stillValid(player)&&storage.removeItem(0,7).isEmpty());
            var second=o.offset(points.get(1));player.moveTo(second.getX()+.5,second.getY(),second.getZ()+1.5,0,0);var next=OutputWarehouse.storage(player,loaded,second);
            checks.put(key+"both access points share persisted output",next.getItem(0).getCount()==7&&next.removeItem(0,2).getCount()==2&&loaded.inventory.getStackInSlot(12).getCount()==5);
            checks.put(key+"raw inputs and paid queue preserved",loaded.inventory.getStackInSlot(0).getCount()==12&&loaded.orders.peek()!=null&&loaded.orders.peek().reserved&&loaded.orders.peek().paid.get(0).getCount()==3);
            var crate=blocks.entrySet().stream().filter(e->e.getValue().is(FunctionalBuildings.CONTAINER.get())).findFirst().orElseThrow().getKey();player.moveTo(crate.getX()+.5,crate.getY(),crate.getZ()+1.5,0,0);
            var crateStock=OutputWarehouse.storage(player,loaded,crate);checks.put(key+"crate directly withdraws same production without duplication",crateStock.stillValid(player)&&crateStock.getItem(0).getCount()==5&&crateStock.removeItem(0,1).getCount()==1&&loaded.inventory.getStackInSlot(12).getCount()==4);
            level.setBlock(crate,FunctionalBuildings.EQUIPMENT.get("campus_storage_rack").get().defaultBlockState(),2);checks.put(key+"legacy rack is now functional too",crateStock.stillValid(player)&&crateStock.removeItem(0,1).getCount()==1&&loaded.inventory.getStackInSlot(12).getCount()==3);
            loaded.inventory.getStackInSlot(12).setCount(5);player.moveTo(second.getX()+.5,second.getY(),second.getZ()+1.5,0,0);
            level.setBlock(second,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);checks.put(key+"destroyed terminal does not drop or lose stock",!next.stillValid(player)&&next.removeItem(0,5).isEmpty()&&loaded.inventory.getStackInSlot(12).getCount()==5);
            var row=new JsonObject();row.addProperty("type",type.name());row.addProperty("version",v);row.addProperty("outputsAfterReloadAndWithdrawal",loaded.inventory.getStackInSlot(12).getCount());row.addProperty("inputCount",loaded.inventory.getStackInSlot(0).getCount());rows.add(row);data.sites.remove(site.id);
        }
        Files.writeString(Path.of("architecture-check/campus-warehouses.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));return checks;
    }
}
