package dev.frontier.industry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class IndustryContent {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IndustryMod.ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IndustryMod.ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, IndustryMod.ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IndustryMod.ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IndustryMod.ID);
    private static final EnumMap<SiteType, RegistryObject<Block>> CONTROLLERS = new EnumMap<>(SiteType.class);
    private static final EnumMap<SiteType, RegistryObject<Item>> DEPLOYERS = new EnumMap<>(SiteType.class);
    private static final Map<String, RegistryObject<Block>> DETAILS = new LinkedHashMap<>();
    private static final Map<String, RegistryObject<Block>> STRUCTURE = new LinkedHashMap<>();
    static {
        for (String name : new String[]{"brick", "glass", "iron", "copper", "light", "floor", "roof", "concrete", "steel", "blue", "red", "coal", "insulator"}) {
            STRUCTURE.put(name, BLOCKS.register("structure_" + name, () -> {
                var properties = BlockBehaviour.Properties.of().strength(name.equals("glass") ? .3f : 2f, 5f)
                    .noLootTable().lightLevel(state -> name.equals("light") ? 12 : 0);
                if (name.equals("glass")) properties.noOcclusion();
                return new Block(properties);
            }));
        }
        STRUCTURE.put("roof_stairs", BLOCKS.register("structure_roof_stairs", () -> new StairBlock(
            () -> structure("roof").defaultBlockState(), BlockBehaviour.Properties.of().strength(2f, 5f).noLootTable())));
        for(String name:dev.frontier.industry.architecture.IndustrialDetailBlock.PARTS)
            DETAILS.put(name,BLOCKS.register("detail_"+name,()->name.equals("press")
                ? new dev.frontier.industry.repair.PressWorkstationBlock()
                : new dev.frontier.industry.architecture.IndustrialDetailBlock(name)));
        for (SiteType type : SiteType.values()) {
            CONTROLLERS.put(type, BLOCKS.register(type.path(), () -> new Controller(type)));
            DEPLOYERS.put(type, ITEMS.register(type.path() + "_deployer", () -> new SiteDeployer(type)));
        }
    }
    public static final RegistryObject<Item> TABLET = ITEMS.register("dispatch_tablet", Tablet::new);
    public static final RegistryObject<BlockEntityType<dev.frontier.industry.energy.ControllerBlockEntity>> CONTROLLER_ENTITY = BLOCK_ENTITIES.register("industry_controller", () ->
        BlockEntityType.Builder.of(dev.frontier.industry.energy.ControllerBlockEntity::new,
            CONTROLLERS.values().stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));
    public static final RegistryObject<Item> REPAIR_KIT = ITEMS.register("repair_kit", RepairKit::new);
    public static final RegistryObject<EntityType<WorkerEntity>> WORKER = ENTITIES.register("worker", () ->
        EntityType.Builder.of(WorkerEntity::new, MobCategory.CREATURE).sized(.6f, 1.95f)
            .clientTrackingRange(10).build(IndustryMod.ID + ":worker"));
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("industry", () -> CreativeModeTab.builder()
        .title(Component.literal("Промышленность и снабжение"))
        .icon(() -> new ItemStack(preferredTablet()))
        .displayItems((parameters, output) -> {
            for (var item : ForgeRegistries.ITEMS.getValues()) {
                var id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null && id.getNamespace().equals(IndustryMod.ID) && !IndustryCatalog.legacyCombat(id.toString())
                        && (item != TABLET.get() || preferredTablet() == TABLET.get())) output.accept(item);
            }
            if (preferredTablet() != TABLET.get()) output.accept(preferredTablet());
        }).build());
    public static Block detail(String name){return DETAILS.get(name).get();}
    public static Block controller(SiteType type) { return CONTROLLERS.get(type).get(); }
    public static Item deployer(SiteType type) { return DEPLOYERS.get(type).get(); }
    public static SiteType deployedType(ItemStack stack){for(var type:SiteType.values())if(stack.is(deployer(type)))return type;return null;}
    public static Item preferredTablet() {
        var nativeItem = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("deepstrike", "jagm_camera_tablet"));
        return nativeItem == null || nativeItem == Items.AIR ? TABLET.get() : nativeItem;
    }
    public static boolean isTablet(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(TABLET.get()) || stack.is(preferredTablet()));
    }
    public static Block structure(String id) { return STRUCTURE.get(id).get(); }
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); TABS.register(bus); BLOCK_ENTITIES.register(bus);
        IndustryMenus.TYPES.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(WORKER.get(), WorkerEntity.attributes().build()));
    }

    public static final class Controller extends Block implements EntityBlock {
        Controller(SiteType type) { super(BlockBehaviour.Properties.of().strength(2f,5f).sound(SoundType.METAL).noLootTable().noOcclusion().lightLevel(s->s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)?7:0));registerDefaultState(stateDefinition.any().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT,false)); }
        @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block,BlockState> builder){builder.add(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);}
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new dev.frontier.industry.energy.ControllerBlockEntity(pos, state); }
        @Override public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s,net.minecraft.world.level.BlockGetter l,BlockPos p,net.minecraft.world.phys.shapes.CollisionContext c){return Block.box(3,0,3,13,16,13);}
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                                InteractionHand hand, BlockHitResult hit) {
            if (isTablet(player.getItemInHand(hand)) || player.getItemInHand(hand).is(REPAIR_KIT.get())) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server && hand == InteractionHand.MAIN_HAND) {
                UUID id = IndustryEngine.siteAt(server.serverLevel(), pos);
                if (id != null && IndustryEngine.owns(server, id)) IndustryMenus.open(server, id);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
    private static final class SiteDeployer extends Item {
        private final SiteType type;
        SiteDeployer(SiteType type) { super(new Properties().stacksTo(1)); this.type = type; }
        @Override public InteractionResult useOn(UseOnContext ctx) {
            if (!dev.frontier.industry.construction.ConstructionAccess.canBuild(ctx.getPlayer())) return InteractionResult.FAIL;
            if (ctx.getPlayer() instanceof ServerPlayer player) {
                UUID id = IndustryEngine.place(player, type, ctx.getClickedPos().relative(ctx.getClickedFace()));
                if (id == null) return InteractionResult.FAIL;
                if (!player.isCreative()) ctx.getItemInHand().shrink(1);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.SUCCESS;
        }
    }
    private static final class Tablet extends Item {
        Tablet() { super(new Properties().stacksTo(1)); }
        @Override public InteractionResult useOn(UseOnContext ctx) {
            if (!(ctx.getPlayer() instanceof ServerPlayer p)) return InteractionResult.SUCCESS;
            UUID id = IndustryEngine.siteAt(p.serverLevel(), ctx.getClickedPos());
            if (id == null) id = IndustryEngine.nearestOwned(p, 16);
            if (id == null || !IndustryEngine.owns(p, id)) return InteractionResult.FAIL;
            var tag = ctx.getItemInHand().getOrCreateTag();
            if (p.isShiftKeyDown()) {
                if (tag.hasUUID("LinkSource")) {
                    if (IndustryEngine.link(p, tag.getUUID("LinkSource"), id)) {
                        tag.putUUID("LinkSource", id);
                        p.displayClientMessage(Component.literal("Линия подключена. Выбери следующий объект или отпусти Shift."), true);
                    }
                } else {
                    tag.putUUID("LinkSource", id);
                    p.displayClientMessage(Component.literal("Начало линии выбрано. Shift + ПКМ по подстанции или заводу."), true);
                }
            } else { tag.remove("LinkSource"); IndustryMenus.open(p, id); }
            return InteractionResult.CONSUME;
        }
        @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
            if (player instanceof ServerPlayer p) return IndustryEngine.selectWorker(p, target, stack) ? InteractionResult.CONSUME : InteractionResult.FAIL;
            return InteractionResult.SUCCESS;
        }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            if (player instanceof ServerPlayer p) {
                OperationsNet.open(p);
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
    }
    private static final class RepairKit extends Item {
        RepairKit() { super(new Properties().stacksTo(16)); }
        @Override public int getUseDuration(ItemStack stack) { return 1800; }
        @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.BLOCK; }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (player instanceof ServerPlayer p && !IndustryEngine.beginRepair(p, stack)) return InteractionResultHolder.fail(stack);
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        @Override public void onUseTick(Level level, LivingEntity living, ItemStack stack, int remaining) {
            if (living instanceof ServerPlayer p) IndustryEngine.repairTick(p, stack, remaining);
        }
        @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity living) {
            if (living instanceof ServerPlayer p) IndustryEngine.finishRepair(p, stack);
            return stack;
        }
        @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
            if (living instanceof ServerPlayer p) IndustryEngine.cancelRepair(p, stack);
        }
    }
}
