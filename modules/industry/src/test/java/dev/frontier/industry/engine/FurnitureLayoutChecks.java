package dev.frontier.industry.engine;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.architecture.FinalIndustryArchitecture;
import dev.frontier.industry.architecture.FurnishedIndustryArchitecture;
import dev.frontier.industry.buildings.BuildingOrientation;
import dev.frontier.industry.buildings.FunctionalBuildings;
import dev.frontier.industry.buildings.GunDisplayBlock;
import dev.frontier.industry.buildings.OutputWarehouse;
import dev.frontier.industry.buildings.ProcessEquipment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/** Opt-in checks for v14 furniture and the existing authoritative output stock. */
public final class FurnitureLayoutChecks {
    private static final Set<String> GUNS = Set.of("tacz:ak47", "tacz:rpg7", "tacz:glock_17");
    private static final Set<String> OLD_ARTILLERY = Set.of("mock_gun_breech", "mock_gun_barrel", "mock_gun_mount");
    private FurnitureLayoutChecks() {}

    public static void run(MinecraftServer server) throws Exception {
        var checks = new LinkedHashMap<String, Boolean>();
        var layouts = new ArrayList<Map<String, Object>>();
        String runtimeFailure = null;
        try {
            checks.put("current placements use v15", Blueprint.CURRENT_VERSION == 15 && BuildingOrientation.VERSION == 15);
            checks.put("functional container native ID retained", id(FunctionalBuildings.CONTAINER.get().defaultBlockState()).equals("frontier_industry:campus_output_container"));
            checks.put("warehouse terminal native ID retained", id(FunctionalBuildings.WAREHOUSE.get().defaultBlockState()).equals("frontier_industry:output_warehouse"));
            for (var type : SiteType.values()) {
                // V14 must copy the frozen v13 map rather than alter its cached entries.
                var frozen = new LinkedHashMap<>(FurnishedIndustryArchitecture.relative(type));
                var unrotated = Blueprint.blocks(type, BlockPos.ZERO, 15);
                checks.put(type + " v14 cache not mutated", frozen.equals(FurnishedIndustryArchitecture.relative(type)));
                for (int q = 0; q < 4; q++) checkLayout(type, q, unrotated, frozen, checks, layouts);
            }
            checkOutputStock(server, checks);
        } catch (Exception failure) {
            runtimeFailure = failure.toString();
            checks.put("native checks completed without exception", false);
        }
        var failures = checks.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        var report = new LinkedHashMap<String, Object>();
        report.put("version", 15);
        report.put("layoutCount", layouts.size());
        report.put("checks", checks);
        report.put("layouts", layouts);
        report.put("failures", failures);
        if (runtimeFailure != null) report.put("runtimeFailure", runtimeFailure);
        report.put("passed", failures.isEmpty() && layouts.size() == SiteType.values().length * 4);
        Files.writeString(Path.of("furniture-check.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        if (!failures.isEmpty() || layouts.size() != 28) throw new IllegalStateException("Furniture checks failed: " + failures + (runtimeFailure == null ? "" : "; " + runtimeFailure));
        System.out.println("FURNITURE_CHECKS_COMPLETE: " + checks.size() + " checks; 28 layouts; native stock/menu persistence verified");
    }

    private static void checkLayout(SiteType type, int q, Map<BlockPos, BlockState> unrotated,
            Map<BlockPos, BlockState> frozen, Map<String, Boolean> checks, List<Map<String, Object>> layouts) {
        int version = 15 + 100 * q;
        var origin = new BlockPos(300 + type.ordinal() * 100, 80, 300 + q * 100);
        var map = Blueprint.blocks(type, origin, version);
        var bounds = Blueprint.bounds(type, version);
        String key = type + "/" + version + " ";
        checks.put(key + "all cells within footprint", map.keySet().stream().allMatch(p -> bounds.contains(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ())));
        var expectedRotation = new LinkedHashMap<BlockPos, BlockState>();
        unrotated.forEach((p, state) -> expectedRotation.put(origin.offset(BuildingOrientation.rotate(p, version)), BuildingOrientation.rotate(state, version)));
        checks.put(key + "entire map and directional states rotate", map.equals(expectedRotation));
        // Furniture tables also carry leg/connection properties; rotating FACING alone is insufficient.
        checks.put(key + "native furniture connections rotate with the block", unrotated.entrySet().stream()
                .filter(e -> id(e.getValue()).startsWith("another_furniture:"))
                .allMatch(e -> state(map, origin.offset(BuildingOrientation.rotate(e.getKey(), version))).equals(e.getValue().rotate(Rotation.values()[q]))));
        checks.put(key + "saved orientation retained", Blueprint.savedVersion(version) == version && BuildingOrientation.quarter(version) == q);
        checks.put(key + "footprint dimensions match v14", bounds.equals(Blueprint.bounds(type, version - 1)));
        var controller = Blueprint.controller(type, origin, version);
        checks.put(key + "original controller retained", state(map, controller).is(IndustryContent.controller(type)) && controller.equals(Blueprint.controller(type, origin, version - 1)));
        var legacyExpected = new LinkedHashMap<BlockPos, BlockState>();
        frozen.forEach((p, s) -> legacyExpected.put(origin.offset(BuildingOrientation.rotate(p, version - 1)), BuildingOrientation.rotate(s, version - 1)));
        checks.put(key + "v14 dispatch keeps every frozen entry", legacyExpected.equals(Blueprint.blocks(type, origin, version - 1)) && Blueprint.savedVersion(version - 1) == version - 1);
        var jobs = FinalIndustryArchitecture.workplaces(type, version);
        checks.put(key + "saved workplace contracts retained", jobs.equals(FinalIndustryArchitecture.workplaces(type, version - 1)));
        var blocked = new ArrayList<String>();
        var unsupported = new ArrayList<String>();
        for (var job : jobs) {
            var p = origin.offset(job.stand());
            if (!collisionEmpty(state(map, p)) || !collisionEmpty(state(map, p.above()))) blocked.add(job.id());
            var support = state(map, p.below()).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (support.isEmpty() || support.max(Direction.Axis.Y) < .999) unsupported.add(job.id());
        }
        checks.put(key + "all workplace feet and heads clear", blocked.isEmpty());
        checks.put(key + "all workplace floors support standing", unsupported.isEmpty());

        var gunCounts = new LinkedHashMap<String, Integer>();
        boolean nativeOnly = true;
        for (var entry : map.entrySet()) if (entry.getValue().getBlock() instanceof GunDisplayBlock gun) {
            gunCounts.merge(gun.gunId(), 1, Integer::sum);
            nativeOnly &= GUNS.contains(gun.gunId()) && entry.getValue().getRenderShape() == RenderShape.INVISIBLE;
            var entity = gun.newBlockEntity(entry.getKey(), entry.getValue());
            nativeOnly &= entity instanceof GunDisplayBlock.GunDisplayEntity display && display.gunId().equals(gun.gunId());
        }
        checks.put(key + "only native TaCZ renderer and matching GunIds", nativeOnly);
        if (type == SiteType.EQUIPMENT_FACTORY) {
            checks.put(key + "exactly four samples per TaCZ gun", gunCounts.keySet().equals(GUNS) && gunCounts.values().stream().allMatch(n -> n == 4));
            checks.put(key + "old artillery samples absent", map.values().stream().noneMatch(s -> s.getBlock() instanceof ProcessEquipment part && OLD_ARTILLERY.contains(part.kind)));
        } else checks.put(key + "no small arms outside equipment factory", gunCounts.isEmpty());

        long containers = count(map, "frontier_industry:campus_output_container");
        if (type.isFactory()) {
            checks.put(key + "at least sixty functional warehouse containers", containers >= 60 && map.values().stream().filter(s -> s.is(FunctionalBuildings.CONTAINER.get())).allMatch(OutputWarehouse::accessPoint));
            checks.put(key + "two functional warehouse terminals retained", count(map, "frontier_industry:output_warehouse") == 2);
            checks.put(key + "new connected office has real tables and chairs", count(map, "another_furniture:dark_oak_table") >= 6 && count(map, "another_furniture:dark_oak_chair") >= 5);
            checks.put(key + "office has drawers and lighting", count(map, "another_furniture:dark_oak_drawer") >= 2 && count(map, "another_furniture:gray_lamp") >= 1);
        }
        if (type == SiteType.RESEARCH_CENTER) {
            checks.put(key + "research v15 has coherent WarStuff decor", count(map, "w2d:table") + count(map, "w2d:table_2") >= 3 && count(map, "w2d:chair_1") + count(map, "w2d:chair_2") >= 4 && count(map, "w2d:wooden_crate") >= 2 && count(map, "w2d:canister") >= 2);
            checks.put(key + "research terrace has open route and technical accents", count(map, "w2d:bench") >= 2 && count(map, "w2d:dcamoslab") >= 3 && count(map, "w2d:generator") >= 1);
            checks.put(key + "research contains no empty pots or random specimen", map.values().stream().noneMatch(s -> s.is(Blocks.FLOWER_POT) || s.getBlock() instanceof ProcessEquipment part && part.kind.equals("campus_specimen")));
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("type", type.name()); row.put("version", version); row.put("blocks", map.size()); row.put("workplaces", jobs.size());
        row.put("blockedWorkplaces", blocked); row.put("unsupportedWorkplaces", unsupported); row.put("containers", containers); row.put("guns", gunCounts);
        row.put("newFurniture", map.values().stream().filter(s -> id(s).startsWith("another_furniture:")).count());
        layouts.add(row);
    }

    private static void checkOutputStock(MinecraftServer server, Map<String, Boolean> checks) {
        var level = server.overworld();
        var data = IndustryData.get(server);
        var origin = new BlockPos(-16000, 80, 16000);
        var first = origin.offset(0, 1, 0);
        var second = origin.offset(2, 1, 0);
        // Access requires the entire saved factory footprint to be loaded, even for this two-block probe.
        // Synchronous loading is sufficient during this callback; do not leave forced-chunk tickets behind.
        var bounds = Blueprint.bounds(SiteType.EQUIPMENT_FACTORY, 314);
        for (int x = Math.floorDiv(origin.getX() + bounds.minX(), 16); x <= Math.floorDiv(origin.getX() + bounds.maxX(), 16); x++)
            for (int z = Math.floorDiv(origin.getZ() + bounds.minZ(), 16); z <= Math.floorDiv(origin.getZ() + bounds.maxZ(), 16); z++) level.getChunk(x, z);
        var previous = Map.of(first, level.getBlockState(first), second, level.getBlockState(second));
        var owner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("furniture-owner".getBytes(StandardCharsets.UTF_8)), "FurnitureOwner"));
        var guest = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("furniture-guest".getBytes(StandardCharsets.UTF_8)), "FurnitureGuest"));
        var site = new IndustryData.Site(data, UUID.randomUUID(), owner.getUUID(), SiteType.EQUIPMENT_FACTORY, level.dimension().location(), origin);
        site.blueprintVersion = 314;
        site.constructing = false;
        site.kitPaid = true;
        try {
            owner.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            level.setBlock(first, FunctionalBuildings.CONTAINER.get().defaultBlockState(), 2);
            level.setBlock(second, FunctionalBuildings.WAREHOUSE.get().defaultBlockState(), 2);
            data.sites.put(site.id, site);
            owner.moveTo(first.getX() + .5, first.getY(), first.getZ() + 1.5, 0, 0);
            guest.moveTo(owner.getX(), owner.getY(), owner.getZ(), 0, 0);
            checks.put("native fixture entire rotated footprint loaded", IndustryEngine.loaded(level, site));
            checks.put("native fixture owner meets production access conditions", !site.constructing && IndustryEngine.owns(owner, site.id) && IndustryEngine.canAccess(owner, site.id) && OutputWarehouse.allowed(owner, site, first));
            site.inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 12));
            var paid = new IndustryData.Order("minecraft:diamond", 2); paid.reserved = true; paid.paid.add(new ItemStack(Items.IRON_INGOT, 3)); site.orders.add(paid);
            var batch = new ItemStack(Items.DIAMOND, 10); batch.getOrCreateTag().putString("batch", "furniture-regression");
            boolean deposited = StockTransactions.putOutputs(site.inventory, batch);
            var stock = OutputWarehouse.storage(owner, site, first);
            checks.put("native container exposes actual production output", deposited && stock.stillValid(owner) && stock.getItem(0).getCount() == 10);
            var denied = OutputWarehouse.storage(guest, site, first);
            checks.put("native container denies foreign extraction", !denied.stillValid(guest) && denied.removeItem(0, 10).isEmpty() && site.inventory.getStackInSlot(IndustryRules.INPUTS).getCount() == 10);
            var menu = OutputWarehouse.menu(1, owner, site, first);
            owner.getInventory().clearContent();
            var moved = menu.quickMoveStack(owner, 0);
            checks.put("native output menu shift-click withdraws once", moved.getCount() == 10 && owner.getInventory().countItem(Items.DIAMOND) == 10 && site.inventory.getStackInSlot(IndustryRules.INPUTS).isEmpty());
            checks.put("native menu cannot insert inputs into paid output", !menu.getSlot(0).mayPlace(new ItemStack(Items.GOLD_INGOT)) && !stock.canPlaceItem(0, new ItemStack(Items.GOLD_INGOT)));
            StockTransactions.putOutputs(site.inventory, batch.copyWithCount(7));
            var loaded = IndustryData.load(data.save(new CompoundTag())).sites.get(site.id);
            checks.put("native saved v14 rotation inputs and paid queue retained", loaded != null && loaded.blueprintVersion == 314 && loaded.inventory.getStackInSlot(0).getCount() == 12 && loaded.orders.peek() != null && loaded.orders.peek().reserved && loaded.orders.peek().paid.get(0).getCount() == 3);
            if (loaded == null) return;
            data.sites.put(site.id, loaded);
            checks.put("native stale container handle cannot duplicate stock", !stock.stillValid(owner) && stock.removeItem(0, 7).isEmpty());
            owner.moveTo(second.getX() + .5, second.getY(), second.getZ() + 1.5, 0, 0);
            var afterReload = OutputWarehouse.storage(owner, loaded, second);
            checks.put("native terminal shares persisted container output", afterReload.getItem(0).getCount() == 7 && afterReload.getItem(0).getOrCreateTag().getString("batch").equals("furniture-regression") && afterReload.removeItem(0, 2).getCount() == 2 && loaded.inventory.getStackInSlot(IndustryRules.INPUTS).getCount() == 5);
        } finally {
            data.sites.remove(site.id); data.setDirty();
            previous.forEach((pos, state) -> level.setBlock(pos, state, 2));
            owner.getInventory().clearContent(); guest.getInventory().clearContent();
        }
    }

    private static BlockState state(Map<BlockPos, BlockState> map, BlockPos p) { return map.getOrDefault(p, Blocks.AIR.defaultBlockState()); }
    private static boolean collisionEmpty(BlockState state) { return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(); }
    private static String id(BlockState state) { return ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString(); }
    private static long count(Map<BlockPos, BlockState> map, String blockId) { return map.values().stream().filter(s -> id(s).equals(blockId)).count(); }
}
