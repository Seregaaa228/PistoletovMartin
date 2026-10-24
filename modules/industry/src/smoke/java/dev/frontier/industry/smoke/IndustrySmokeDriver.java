package dev.frontier.industry.smoke;

import com.google.gson.*;
import dev.frontier.industry.*;
import dev.frontier.industry.combat.CombatSmoke;
import dev.frontier.industry.engine.*;
import dev.frontier.industry.vehicle.VehicleSmoke;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Opt-in ordinary-tick integration driver. Present only in the isolated smoke artifact. */
@Mod.EventBusSubscriber(modid=IndustryMod.ID)
public final class IndustrySmokeDriver {
 private static int phase, age, started; private static boolean done;
 private static ServerPlayer player; private static MinecraftServer server;
 private static final JsonObject REPORT=new JsonObject();
 private static UUID plantId,subId,factoryId,workerId; private static int savedProgress;
 private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
 private static Path root(){return server.getServerDirectory().toPath();}
 private static void check(String name,boolean ok){REPORT.addProperty(name,ok);System.out.println("INDUSTRY_INTEGRATION "+(ok?"PASS ":"FAIL ")+name);}
 private static void write(String name,JsonObject value)throws Exception{Files.writeString(root().resolve(name),GSON.toJson(value));}
 private static void prepare(BlockPos pos,int radius){
  player.teleportTo(player.serverLevel(),pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
  player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();
  for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)player.serverLevel().getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);
 }
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
  if(event.phase!=TickEvent.Phase.END||!Boolean.getBoolean("frontier.industrySmokeTest")||done)return;
  server=event.getServer(); if(server.getPlayerList().getPlayers().isEmpty())return;
  if(player==null){player=server.getPlayerList().getPlayers().get(0);started=server.getTickCount();}
  if(server.getTickCount()-started<60)return;
  try{
   age++;
   if(phase==0){
    if(Boolean.getBoolean("frontier.industrySmokeCombatOnly")){
     if(!Boolean.getBoolean("frontier.industrySmokeSkipVehicle")){prepare(new BlockPos(4096,188,4096),3);EngineSmoke.run(player);EngineSmoke.cleanup();REPORT.add("armyWithSuperbRecruits",GSON.toJsonTree(EngineSmoke.results()));}
     prepare(new BlockPos(4224,188,4096),6);catalogChecks();phase=11;age=0;return;
    }
    if(Boolean.getBoolean("frontier.industrySmokeReload")&&!Boolean.getBoolean("frontier.industrySmokeRecheckEngine")){readReload();phase=5;age=0;return;}
    prepare(new BlockPos(4096,188,4096),3);EngineSmoke.run(player);phase=1;age=0;
   }else if(phase==11&&age>=40){
    CombatSmoke.run(player.serverLevel(),player);phase=9;age=0;
   }else if(phase==9){
    CombatSmoke.tick(player.serverLevel());if(!CombatSmoke.finished())return;
    REPORT.add("combat",GSON.toJsonTree(CombatSmoke.results()));check("combat all native assertions",CombatSmoke.passed());
    if(Boolean.getBoolean("frontier.industrySmokeSkipVehicle")){server.saveEverything(true,true,true);finish("industry-smoke-combat-only.json");return;}
    BlockPos arena=new BlockPos(4096,180,4352);prepare(arena.offset(-10,2,-5),3);VehicleSmoke.start(player,arena);phase=10;age=0;
   }else if(phase==10){
    VehicleSmoke.tick();if(!VehicleSmoke.done())return;
    REPORT.add("vehicles",GSON.toJsonTree(VehicleSmoke.results()));server.saveEverything(true,true,true);finish("industry-smoke-combat-final.json");
   }else if(phase==1){
    EngineSmoke.tick(server);if(!EngineSmoke.finished())return;
    REPORT.add("engine",GSON.toJsonTree(EngineSmoke.results()));write("industry-smoke-progress.json",REPORT);
    if(Boolean.getBoolean("frontier.industrySmokeReload")){
     BlockPos arena=new BlockPos(4096,180,4352);prepare(arena.offset(-10,2,-5),3);VehicleSmoke.start(player,arena);phase=7;age=0;return;
    }
    prepare(new BlockPos(4224,188,4096),6);CombatSmoke.run(player.serverLevel(),player);phase=2;age=0;
   }else if(phase==2){
    CombatSmoke.tick(player.serverLevel());if(!CombatSmoke.finished())return;
    REPORT.add("combat",GSON.toJsonTree(CombatSmoke.results()));check("combat all native assertions",CombatSmoke.passed());write("industry-smoke-progress.json",REPORT);
    BlockPos arena=new BlockPos(4096,180,4352);prepare(arena.offset(-10,2,-5),10);VehicleSmoke.start(player,arena);phase=3;age=0;
   }else if(phase==3){
    VehicleSmoke.tick();if(!VehicleSmoke.done())return;
    REPORT.add("vehicles",GSON.toJsonTree(VehicleSmoke.results()));write("industry-smoke-progress.json",REPORT);
    prepareSaveFixture();phase=4;age=0;
   }else if(phase==4){
    var factory=IndustryData.get(server).sites.get(factoryId);
    if(!factory.orders.isEmpty()&&factory.orders.peek().progress>=45){
     check("GUI real C2S order accepted and paid",factory.orders.peek().reserved);
     savedProgress=factory.orders.peek().progress;
     player.serverLevel().setBlock(Blueprint.controller(SiteType.POWER_STATION,IndustryData.get(server).sites.get(plantId).origin),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
     phase=6;age=0;
    }else if(age>400){check("GUI order roundtrip within400ticks",false);finish("industry-smoke-first.json");}
   }else if(phase==6&&age>=25){
    var data=IndustryData.get(server);var factory=data.sites.get(factoryId);
    check("saved paid queue pauses on destroyed power controller",factory.orders.peek()!=null&&factory.orders.peek().progress==savedProgress);
    JsonObject receipt=new JsonObject();receipt.addProperty("plant",plantId.toString());receipt.addProperty("substation",subId.toString());receipt.addProperty("factory",factoryId.toString());receipt.addProperty("worker",workerId.toString());receipt.addProperty("progress",savedProgress);
    receipt.addProperty("inputs",factory.inventory.serializeNBT().toString());write("industry-reload-receipt.json",receipt);
    server.saveEverything(true,true,true);finish("industry-smoke-first.json");
   }else if(phase==7){
    VehicleSmoke.tick();if(!VehicleSmoke.done())return;
    REPORT.add("vehicles",GSON.toJsonTree(VehicleSmoke.results()));write("industry-smoke-progress.json",REPORT);readReload();phase=5;age=0;
   }else if(phase==5){
    var data=IndustryData.get(server);var factory=data.sites.get(factoryId);var plant=data.sites.get(plantId);
    if(age==10){System.setProperty("frontier.industrySmokeGuiPreview","true");IndustryMenus.open(player,factoryId);}
    if(age==40){
     check("actual screen typing inventory hotkey keeps factory open",Boolean.getBoolean("frontier.industrySmokeGuiKeyGuard"));
     check("actual screen Tab selects recipe catalog entry",Boolean.getBoolean("frontier.industrySmokeGuiTab"));
     check("fresh process preserves paused queue progress",factory.orders.peek()!=null&&factory.orders.peek().progress==savedProgress&&factory.orders.peek().reserved);
     check("fresh process restores native worker entity",player.serverLevel().getEntity(workerId) instanceof WorkerEntity);
     check("fresh process preserves physical damage",player.serverLevel().getBlockState(Blueprint.controller(plant.type,plant.origin)).isAir());
     player.serverLevel().setBlock(Blueprint.controller(plant.type,plant.origin),IndustryContent.controller(plant.type).defaultBlockState(),Block.UPDATE_ALL);
    }
    if(age>45&&factory.orders.isEmpty()){
     int count=0;for(int i=12;i<24;i++)if(factory.inventory.getStackInSlot(i).is(Items.IRON_SWORD))count+=factory.inventory.getStackInSlot(i).getCount();
     check("fresh process completes exactlyone paid product",count==1);
     check("fresh process consumes no second material payment",java.util.stream.IntStream.range(0,12).allMatch(i->factory.inventory.getStackInSlot(i).isEmpty()));
     StockTransactions.refundInputs(factory.inventory,IndustryCatalog.resolve("minecraft:iron_sword",SiteType.EQUIPMENT_FACTORY).cost());
     IndustryEngine.action(player,factoryId,"order","minecraft:iron_sword",1);phase=8;age=0;
    }else if(age>950){check("fresh process resumes real production",false);finish("industry-smoke-reload.json");}
   }else if(phase==8){
    var factory=IndustryData.get(server).sites.get(factoryId);
    if(age==30){
     check("factory starts another real paid order before drone strike",factory.functional()&&factory.orders.peek()!=null&&factory.orders.peek().progress>0&&factory.orders.peek().reserved);
     savedProgress=factory.orders.peek().progress;
     var type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation("uncomplicatedfpv:shahed136"));
     var drone=type.create(player.serverLevel());drone.setPos(factory.origin.getX()+.5,factory.origin.getY()+2.5,factory.origin.getZ()+.5);player.serverLevel().addFreshEntity(drone);
     player.teleportTo(factory.origin.getX()+40.5,factory.origin.getY()+3,factory.origin.getZ()+.5);
     drone.getClass().getMethod("explode").invoke(drone);
    }
    if(age==60){
     check("actual installed Shahed blast damages factory blueprint",factory.matching<factory.total*3/4);
     check("actual Shahed strike stops factory and preserves paid queue",!factory.functional()&&factory.orders.peek()!=null&&factory.orders.peek().progress==savedProgress&&factory.orders.peek().reserved);
     server.saveEverything(true,true,true);finish("industry-smoke-reload.json");
    }
   }
  }catch(Throwable failure){failure.printStackTrace();check("unhandled integration exception "+failure,false);try{finish("industry-smoke-exception.json");}catch(Exception ignored){done=true;}}
 }
 private static void prepareSaveFixture(){
  BlockPos base=new BlockPos(4608,208,4608);prepare(base,3);var data=IndustryData.get(server);
  plantId=IndustryEngine.place(player,SiteType.POWER_STATION,base);player.teleportTo(base.getX()+12.5,base.getY()+1,base.getZ()+.5);
  subId=IndustryEngine.place(player,SiteType.SUBSTATION,base.offset(12,0,0));
  check("persistent plant substation link",IndustryEngine.link(player,plantId,subId));
  player.teleportTo(base.getX()+24.5,base.getY()+1,base.getZ()+.5);factoryId=IndustryEngine.place(player,SiteType.EQUIPMENT_FACTORY,base.offset(24,0,0));
  check("persistent substation factory link",IndustryEngine.link(player,subId,factoryId));
  data.sites.get(plantId).inventory.setStackInSlot(0,new ItemStack(Items.COAL,4));
  var recipe=IndustryCatalog.resolve("minecraft:iron_sword",SiteType.EQUIPMENT_FACTORY);
  if(recipe==null)throw new IllegalStateException("iron sword catalog absent");
  StockTransactions.refundInputs(data.sites.get(factoryId).inventory,recipe.cost());
  player.getInventory().add(new ItemStack(Items.EMERALD,8));IndustryEngine.action(player,factoryId,"hire","production",1);
  workerId=data.workers.values().stream().filter(w->factoryId.equals(w.site)).findFirst().orElseThrow().id;
  System.setProperty("frontier.industrySmokeGuiOrder","true");IndustryMenus.open(player,factoryId);
 }
 private static void readReload()throws Exception{
  JsonObject r=JsonParser.parseString(Files.readString(root().resolve("industry-reload-receipt.json"))).getAsJsonObject();
  plantId=UUID.fromString(r.get("plant").getAsString());subId=UUID.fromString(r.get("substation").getAsString());factoryId=UUID.fromString(r.get("factory").getAsString());workerId=UUID.fromString(r.get("worker").getAsString());savedProgress=r.get("progress").getAsInt();
  var data=IndustryData.get(server);var factory=data.sites.get(factoryId);check("fresh process SavedData contains sites",factory!=null&&data.sites.containsKey(plantId)&&data.sites.containsKey(subId));
  prepare(factory.origin.offset(0,1,0),3);check("fresh process links persist",subId.equals(factory.upstream)&&plantId.equals(data.sites.get(subId).upstream));
  check("fresh process exact inventory NBT persists",r.get("inputs").getAsString().equals(factory.inventory.serializeNBT().toString()));
 }
 private static void catalogChecks()throws Exception{
  var catalog=IndustryCatalog.recipes();JsonArray rows=new JsonArray();
  for(var recipe:catalog){JsonObject r=new JsonObject();r.addProperty("id",recipe.id());r.addProperty("name",recipe.title());r.addProperty("factory",recipe.factory().title());r.addProperty("secondsPerWorker",recipe.ticks()/20);r.addProperty("fePerTick",recipe.fePerTick());JsonArray costs=new JsonArray();for(var stack:recipe.cost())costs.add(stack.getCount()+" "+net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()));r.add("materials",costs);rows.add(r);}
  JsonObject export=new JsonObject();export.add("recipes",rows);write("industry-production-catalog.json",export);
  for(String id:new String[]{"fpv_drone","mavic_drone_with_drop","mavic_drone_no_drop","shahed136"})check("catalog drone factory supports "+id,IndustryCatalog.resolve("uncomplicatedfpv:"+id,SiteType.DRONE_FACTORY)!=null);
  for(String id:new String[]{"plane","large_plane","cargo_plane","helicopter"})check("catalog vehicle factory supports "+id,IndustryCatalog.resolve("simpleplanes:"+id,SiteType.VEHICLE_FACTORY)!=null);
  check("catalog tank deployer is in vehicle factory",IndustryCatalog.resolve("trajanstanks:panzer2_item",SiteType.VEHICLE_FACTORY)!=null);
  check("catalog tank ammo is in equipment factory",IndustryCatalog.resolve("trajanscore:standard_shell",SiteType.EQUIPMENT_FACTORY)!=null);
  for(String id:List.of("bmp_2","bradley","lav_25")){
   var recipe=IndustryCatalog.resolve("superbwarfare:"+id,SiteType.VEHICLE_FACTORY);
   check("catalog produces native armored vehicle container "+id,recipe!=null&&recipe.output().hasTag()&&("superbwarfare:"+id).equals(recipe.output().getTag().getCompound("BlockEntityTag").getString("EntityType")));
  }
  check("catalog blueprints and guidebooks are not vehicles",IndustryCatalog.resolve("trajanstanks:panzer_two_blueprint",SiteType.VEHICLE_FACTORY)==null&&IndustryCatalog.resolve("simpleplanes:plane_book",SiteType.VEHICLE_FACTORY)==null);
  check("catalog excludes blank parameterized legacy SBW containers",catalog.stream().noneMatch(r->Set.of("superbwarfare:container","superbwarfare:small_container","superbwarfare:vehicle_gun").contains(r.id())));
  check("catalog contains actual tagged TaCZ gun variants",catalog.stream().anyMatch(r->r.id().startsWith("tacz:")&&r.output().hasTag()&&!r.output().getTag().isEmpty()));
  check("catalog contains actual tagged TaCZ ammunition",catalog.stream().anyMatch(r->r.id().startsWith("ammo/tacz:")&&r.output().hasTag()));
  for(String id:new String[]{"ballistic_missile","interceptor_ammo","missile_launcher","s300_launcher","s400_launcher","patriot_launcher"})check("catalog missile factory supports "+id,IndustryCatalog.resolve("frontier_industry:"+id,SiteType.MISSILE_FACTORY)!=null);
 }
 private static void finish(String name)throws Exception{done=true;System.setProperty("frontier.industrySmokeHoldRepair","false");write(name,REPORT);System.setProperty("frontier.industrySmokeDone","true");System.out.println("INDUSTRY_INTEGRATION COMPLETE "+name);}
}
