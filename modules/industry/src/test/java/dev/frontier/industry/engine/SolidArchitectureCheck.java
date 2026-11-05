package dev.frontier.industry.engine;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.frontier.industry.IndustryMod;
import dev.frontier.industry.SiteType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Opt-in native Forge check; never included in a release JAR. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID)
public final class SolidArchitectureCheck {
    private static Map<String,Boolean> pending;private static int lightTicks;
    @Mod.EventBusSubscriber(modid = IndustryMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void loaded(net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent event) {
            if (!Boolean.getBoolean("frontier.architectureCheck")) return;
            // The snapshot's third-party GameTest annotations use production names.
            // This is an ordinary server check, not a run of their GameTest suites.
            try {
                var flag = net.minecraftforge.gametest.ForgeGameTestHooks.class.getDeclaredField("registeredGametests");
                flag.setAccessible(true); flag.setBoolean(null, true);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (Boolean.getBoolean("frontier.showcase")) return;
        if (!Boolean.getBoolean("frontier.architectureCheck")) return;
        var server = event.getServer();
        var level = server.overworld();
        if(Boolean.getBoolean("frontier.crewStress")){ConstructionStressChecks.begin(server);return;}
        if(Boolean.getBoolean("frontier.restartProbe")){ConstructionRestartChecks.begin(server);return;}
        if(Boolean.getBoolean("frontier.constructionProbe")){
            var report=ConstructionPipelineChecks.run(server,true);Files.createDirectories(Path.of("architecture-check"));Files.writeString(Path.of("architecture-check/construction-baseline.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));server.halt(false);return;
        }
        Map<String, Boolean> checks = new LinkedHashMap<>(BlueprintLayoutChecks.run());
        checks.put("checked the patched installed Blueprint, including its critical caches",
            java.util.Arrays.stream(Blueprint.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("blocks$previous"))
            && java.util.Arrays.stream(Blueprint.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("CRITICAL_V3")));
        JsonObject export = new JsonObject();
        for (SiteType type : SiteType.values()) {
            var map = Blueprint.blocks(type, BlockPos.ZERO, 4);
            int[] room = switch (type) {
                case POWER_STATION -> new int[]{-13,-10,3,3,7};
                case SUBSTATION -> new int[]{-4,4,4,7,4};
                case DRONE_FACTORY -> new int[]{-8,-7,8,8,6};
                case VEHICLE_FACTORY -> new int[]{-11,-9,11,10,8};
                case EQUIPMENT_FACTORY -> new int[]{-9,-7,9,8,7};
                case MISSILE_FACTORY -> new int[]{-9,-11,9,12,10};
                case RESEARCH_CENTER -> new int[]{-8,-7,8,8,5};
            };
            boolean walls = true, roof = true;
            for (int x = room[0]; x <= room[2]; x++) for (int z = room[1]; z <= room[3]; z++) {
                boolean perimeter = x==room[0] || x==room[2] || z==room[1] || z==room[3];
                // Front door and power hall's internal rear passage are intentional.
                boolean door = z==room[1] && Math.abs(x) <= (type==SiteType.VEHICLE_FACTORY?4:type==SiteType.MISSILE_FACTORY?3:type==SiteType.SUBSTATION?1:2);
                boolean internal = type==SiteType.POWER_STATION && z==room[3] && x>=-2 && x<=1;
                if (perimeter && !door && !internal) for (int y=1;y<=room[4];y++) {
                    BlockState s=map.get(new BlockPos(x,y,z));
                    walls &= s!=null && Block.isShapeFullBlock(s.getCollisionShape(level,BlockPos.ZERO));
                }
                BlockState s=map.get(new BlockPos(x,room[4]+1,z));
                boolean stairwell = type==SiteType.RESEARCH_CENTER && x==-6 && z>=1 && z<=6;
                roof &= stairwell || s!=null && Block.isShapeFullBlock(s.getCollisionShape(level,BlockPos.ZERO));
            }
            checks.put(type+" walls have full-block collision and no seams",walls);
            checks.put(type+" roof has a continuous solid or glazed deck",roof);
            checks.put(type+" v3 footprint still loads unchanged",Blueprint.savedVersion(3)==3 && Blueprint.bounds(type,3).width() < Blueprint.bounds(type,4).width());
            JsonArray blocks = new JsonArray();
            BlockPos origin = new BlockPos(type.ordinal()*48, 80, 0);
            for (var entry : map.entrySet()) {
                var p=entry.getKey(); var row=new JsonArray();
                row.add(p.getX());row.add(p.getY());row.add(p.getZ());
                row.add(ForgeRegistries.BLOCKS.getKey(entry.getValue().getBlock()).toString());
                blocks.add(row);
                level.setBlock(origin.offset(p),entry.getValue(),Block.UPDATE_CLIENTS);
            }
            export.add(type.name(),blocks);
        }
        Path out=Path.of("architecture-check");Files.createDirectories(out);
        checks.putAll(OperationalBuildingsChecks.run(server));
        checks.putAll(FinalBuildingsChecks.run(server));
        checks.putAll(InteriorDecorationsChecks.run(server));
        checks.putAll(DistinctBuildingsChecks.run(server));
        checks.putAll(ReworkedBuildingsChecks.run(server));
        checks.putAll(WorkshopSmokeChecks.run(server));
        checks.putAll(FactoryUpgradeChecks.run(server));
        checks.putAll(CampusWarehouseChecks.run(server));
        checks.putAll(LaboratoryLayoutChecks.run(server));
        checks.putAll(EngineRuntimeChecks.run());
        var construction=ConstructionPipelineChecks.run(server,false);
        Files.writeString(out.resolve("construction-checks.json"),new GsonBuilder().setPrettyPrinting().create().toJson(construction));
        for(var row:construction.getAsJsonArray("sites")){var r=row.getAsJsonObject();checks.put("real construction "+r.get("type").getAsString()+"/"+r.get("version").getAsInt()+" complete at 300 ticks",r.get("complete").getAsBoolean()&&r.get("missing").getAsInt()==0&&r.get("activeTicks").getAsInt()==300&&r.get("calls").getAsInt()==300&&r.get("recoveryAndPaidStockPreserved").getAsBoolean());}
        Files.writeString(out.resolve("layouts.json"),new GsonBuilder().setPrettyPrinting().create().toJson(export));
        pending=checks;lightTicks=0;
    }
    @SubscribeEvent public static void afterLighting(net.minecraftforge.event.TickEvent.ServerTickEvent event)throws Exception {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END||pending==null||++lightTicks<240)return;
        if(lightTicks<1200&&event.getServer().overworld().getChunkSource().getLightEngine().hasLightWork())return;
        var server=event.getServer();var checks=pending;pending=null;checks.putAll(DistinctBuildingsChecks.lighting(server));checks.putAll(ReworkedBuildingsChecks.lighting(server));
        Path out=Path.of("architecture-check");
        var gson=new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(out.resolve("checks.json"),gson.toJson(checks));
        long failed=checks.values().stream().filter(v->!v).count();
        System.out.println("SOLID_ARCHITECTURE_CHECK: "+checks.size()+" checks, "+failed+" failed");
        checks.forEach((k,v)->{if(!v)System.out.println("FAILED: "+k);});
        server.halt(false);
    }
}
