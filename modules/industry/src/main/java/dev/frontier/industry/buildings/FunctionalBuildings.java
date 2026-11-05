package dev.frontier.industry.buildings;

import dev.frontier.industry.*;
import dev.frontier.industry.architecture.OperationalArchitecture;
import dev.frontier.industry.architecture.FinalIndustryArchitecture;
import dev.frontier.industry.engine.IndustryData;
import java.util.EnumMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

/** Local terminals reuse the native, server-authorised research/production menus. */
public final class FunctionalBuildings {
    public static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(ForgeRegistries.BLOCKS,IndustryMod.ID);
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(ForgeRegistries.ITEMS,IndustryMod.ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES,IndustryMod.ID);
    public static final RegistryObject<Block> BELT=BLOCKS.register("production_belt",ProductionBelt::new);
    public static final RegistryObject<Block> LINE_CONTROLLER=BLOCKS.register("line_controller",LineController::new);
    public static final RegistryObject<Block> PROCESS_PIPE=BLOCKS.register("process_pipe",ProcessPipe::new);
    public static final java.util.Map<String,RegistryObject<Block>> PROPS=new java.util.LinkedHashMap<>();
    public static final java.util.Map<String,RegistryObject<Block>> EQUIPMENT=new java.util.LinkedHashMap<>();
    public static final java.util.Map<String,RegistryObject<Block>> PIPES=new java.util.LinkedHashMap<>();
    public static final String[] EQUIPMENT_KINDS={"boiler_casing","turbine_casing","generator_end","pump_unit","heat_exchanger","transformer_unit","cooler_unit","tank_panel","pipe_support","lab_bench","lab_microscope","lab_analyser","lab_chamber","lab_fumehood","sample_cabinet","lab_console","airframe_jig","airframe_section","jet_engine_stand","drone_jig","electronics_bench","cnc_machine","weapons_rack","rocket_cradle","rocket_test_rig","tool_trolley","parts_cart","safety_barrier","cable_tray","port_steam","port_return","port_coolant","port_flue"};
    public static final EnumMap<SiteType,RegistryObject<Block>> SIGNS=new EnumMap<>(SiteType.class);
    public static final java.util.List<RegistryObject<Block>> LINE_SIGNS=new java.util.ArrayList<>();
    public static final RegistryObject<Block> WAREHOUSE=BLOCKS.register("output_warehouse",OutputWarehouse::new);
    public static final RegistryObject<Block> CONTAINER=BLOCKS.register("campus_output_container",OutputWarehouse::new);
    static {
        ITEMS.register("campus_output_container",()->new BlockItem(CONTAINER.get(),new Item.Properties()));
        for(String kind:new String[]{"campus_microscopy","campus_chemistry","campus_electronics","campus_green_planter"}){RegistryObject<Block> part=BLOCKS.register(kind,()->new ProcessEquipment(kind));EQUIPMENT.put(kind,part);ITEMS.register(kind,()->new BlockItem(part.get(),new Item.Properties()));}
        ITEMS.register("output_warehouse",()->new BlockItem(WAREHOUSE.get(),new Item.Properties()));
        for(String kind:new String[]{"mock_air_nose","mock_air_canopy","mock_air_body","mock_air_tail","mock_air_fin","mock_air_wing","mock_wing_tip","mock_vehicle_hull","mock_vehicle_tracks","mock_vehicle_turret","mock_gun_barrel","mock_gun_breech","mock_gun_mount","mock_drone_body","mock_drone_arm","mock_drone_rotor","mock_rocket_nose","mock_rocket_body","mock_rocket_engine","mock_rocket_fin","campus_cargo_crate","campus_storage_rack","campus_ammo_case","campus_dispatch_screen","campus_dispatch_sign","campus_road_sign","campus_poster","campus_blueprint","campus_toolboard","campus_bollard","campus_pallet_jack","campus_specimen","campus_service_tools"}){RegistryObject<Block> part=BLOCKS.register(kind,()->new ProcessEquipment(kind));EQUIPMENT.put(kind,part);ITEMS.register(kind,()->new BlockItem(part.get(),new Item.Properties()));}
        for(String kind:new String[]{"line_post","steel_column","assembly_robot","robot_arm","hydraulic_press","press_head","cable_spool","welding_cart","instrument_wall","avionics_rack","safety_cage","ventilation_fan"}){RegistryObject<Block> part=BLOCKS.register(kind,()->new ProcessEquipment(kind));EQUIPMENT.put(kind,part);ITEMS.register(kind,()->new BlockItem(part.get(),new Item.Properties()));}
        for(String kind:EQUIPMENT_KINDS){RegistryObject<Block> prop=BLOCKS.register(kind,()->new ProcessEquipment(kind));EQUIPMENT.put(kind,prop);ITEMS.register(kind,()->new BlockItem(prop.get(),new Item.Properties()));}
        for(String kind:new String[]{"steam","return","coolant","flue"}){RegistryObject<Block> pipe=BLOCKS.register(kind+"_pipe",()->new EngineeredPipe(kind));PIPES.put(kind,pipe);ITEMS.register(kind+"_pipe",()->new BlockItem(pipe.get(),new Item.Properties()));}
        for(var type:SiteType.values()) {
            RegistryObject<Block> sign=BLOCKS.register("site_sign_"+type.path(),()->new BuildingNameplate(type));SIGNS.put(type,sign);
            ITEMS.register("site_sign_"+type.path(),()->new BlockItem(sign.get(),new Item.Properties()));
        }
        ITEMS.register("production_belt",()->new BlockItem(BELT.get(),new Item.Properties()));
        ITEMS.register("line_controller",()->new BlockItem(LINE_CONTROLLER.get(),new Item.Properties()));
        ITEMS.register("process_pipe",()->new BlockItem(PROCESS_PIPE.get(),new Item.Properties()));
        for(String kind:new String[]{"research_server","operator_console","quality_console","packing_table","pallet","mounted_lamp","tool_cabinet","materials_rack","parts_bin","wall_panel","sealed_drum","floor_lamp","wall_lamp","courtyard_bench","lounge_chair","coffee_table","planter","bookcase","stair_railing","post_lantern"}) {
            RegistryObject<Block> prop=BLOCKS.register(kind,()->new WorkshopProp(kind));PROPS.put(kind,prop);
            ITEMS.register(kind,()->new BlockItem(prop.get(),new Item.Properties()));
        }
        for(int line=0;line<3;line++){final int index=line;RegistryObject<Block> sign=BLOCKS.register("line_sign_"+(char)('a'+line),()->new BuildingNameplate(null,index));LINE_SIGNS.add(sign);}
    }
    public static final RegistryObject<Block> GUN_RIFLE_DISPLAY=BLOCKS.register("campus_rifle_display",()->new GunDisplayBlock("tacz:ak47"));
    public static final RegistryObject<Block> GUN_RPG_DISPLAY=BLOCKS.register("campus_rpg_display",()->new GunDisplayBlock("tacz:rpg7"));
    public static final RegistryObject<Block> GUN_PISTOL_DISPLAY=BLOCKS.register("campus_pistol_display",()->new GunDisplayBlock("tacz:glock_17"));
    public static final RegistryObject<BlockEntityType<BuildingNameplate.SignEntity>> SIGN_ENTITY=ENTITIES.register("building_nameplate",()->
        BlockEntityType.Builder.of(BuildingNameplate.SignEntity::new,java.util.stream.Stream.concat(SIGNS.values().stream(),LINE_SIGNS.stream()).map(RegistryObject::get).toArray(Block[]::new)).build(null));
    public static final RegistryObject<BlockEntityType<GunDisplayBlock.GunDisplayEntity>> GUN_DISPLAY=ENTITIES.register("gun_display",()->
        BlockEntityType.Builder.of((pos,state)->new GunDisplayBlock.GunDisplayEntity(pos,state),GUN_RIFLE_DISPLAY.get(),GUN_RPG_DISPLAY.get(),GUN_PISTOL_DISPLAY.get()).build(null));
    private FunctionalBuildings() {}
    public static void init() {
        var bus=FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);
        MinecraftForge.EVENT_BUS.register(FunctionalBuildings.class);
    }
    public static BlockState belt(Direction direction) {
        return BELT.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,direction);
    }
    public static Component displayName(SiteType type) {
        return Component.translatable("block.frontier_industry.site_sign_"+type.path());
    }
    public static boolean terminal(BlockState state) {
        if(state.getBlock() instanceof ProcessEquipment p)return p.kind.equals("lab_console");
        if(state.getBlock() instanceof WorkshopProp p)return java.util.Set.of("research_server","operator_console","quality_console","packing_table").contains(p.kind);
        return state.is(IndustryContent.detail("server")) || state.is(IndustryContent.detail("laboratory"))
            || state.is(IndustryContent.detail("drone_bench")) || state.is(IndustryContent.detail("machine"));
    }
    public static boolean authorised(ServerPlayer player, IndustryData.Site site) {
        return site!=null && !site.constructing && IndustryEngine.owns(player,site.id) && IndustryEngine.canAccess(player,site.id);
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.RightClickBlock event) {
        if(IndustryContent.isTablet(event.getItemStack()) || event.getItemStack().is(IndustryContent.REPAIR_KIT.get()))return;
        if(event.getHand()==InteractionHand.MAIN_HAND&&event.getLevel().getBlockState(event.getPos()).is(LINE_CONTROLLER.get())) {
            event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
            if(event.getEntity() instanceof ServerPlayer player) {
                var id=IndustryEngine.siteAt(player.serverLevel(),event.getPos());var site=id==null?null:IndustryData.get(player.server).sites.get(id);
                if(authorised(player,site)) {
                    int line=event.getLevel().getBlockState(event.getPos()).getValue(LineController.LINE);
                    var data=FactoryLinesData.get(player.server);data.toggle(site.id,line);updateBelts(player.server);
                    player.displayClientMessage(Component.translatable(data.enabled(site.id,line)?"message.frontier_industry.line_enabled":"message.frontier_industry.line_disabled",String.valueOf((char)('A'+line))),true);
                }
            }
            return;
        }
        if(event.getHand()!=InteractionHand.MAIN_HAND || !terminal(event.getLevel().getBlockState(event.getPos())))return;
        if(IndustryContent.isTablet(event.getItemStack()) || event.getItemStack().is(IndustryContent.REPAIR_KIT.get()))return;
        if(event.getEntity() instanceof ServerPlayer player) {
            var id=IndustryEngine.siteAt(player.serverLevel(),event.getPos());
            var site=id==null?null:IndustryData.get(player.server).sites.get(id);
            if(site==null)return;
            event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
            if(!authorised(player,site)) {
                player.displayClientMessage(Component.translatable(site.constructing?"message.frontier_industry.unfinished_terminal":"message.frontier_industry.denied_terminal"),true);return;
            }
            IndustryMenus.open(player,site.id);
        } else {
            event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
    public static boolean running(IndustryData.Site site) {
        return site.type.isFactory() && !site.constructing && site.functional() && site.powered;
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || event.getServer().getTickCount()%20!=0)return;
        updateBelts(event.getServer());
        dev.frontier.industry.architecture.FactoryDetailUpgrade.cleanup(event.getServer());
    }
    public static void updateBelts(net.minecraft.server.MinecraftServer server) {
        for(var site:IndustryData.get(server).sites.values()) {
            if(site.blueprintVersion<5 || !site.type.isFactory())continue;
            var level=IndustryEngine.level(server,site);if(level==null)continue;
            boolean powered=running(site);
            java.util.List<BlockPos> positions=BuildingOrientation.applies(site.blueprintVersion)?FinalIndustryArchitecture.belts(site.type,site.blueprintVersion):OperationalArchitecture.belts(site.type);
            for(var local:positions) {
                BlockPos p=site.origin.offset(local);
                if(!level.hasChunkAt(p))continue;
                var state=level.getBlockState(p);
                int line=BuildingOrientation.applies(site.blueprintVersion)?FinalIndustryArchitecture.lineAt(site.type,local,site.blueprintVersion):0;
                boolean enabled=powered&&FactoryLinesData.get(server).enabled(site.id,line);
                if(state.is(BELT.get()) && state.getValue(BlockStateProperties.POWERED)!=enabled)
                    level.setBlock(p,state.setValue(BlockStateProperties.POWERED,enabled),Block.UPDATE_CLIENTS);
            }
            if(BuildingOrientation.applies(site.blueprintVersion))for(int line=0;line<3;line++) {
                BlockPos p=site.origin.offset(FinalIndustryArchitecture.lineControl(site.type,line,site.blueprintVersion));if(!level.hasChunkAt(p))continue;var state=level.getBlockState(p);
                boolean enabled=powered&&FactoryLinesData.get(server).enabled(site.id,line);
                if(state.is(LINE_CONTROLLER.get())&&state.getValue(BlockStateProperties.POWERED)!=enabled)level.setBlock(p,state.setValue(BlockStateProperties.POWERED,enabled),Block.UPDATE_CLIENTS);
            }
        }
    }
}
