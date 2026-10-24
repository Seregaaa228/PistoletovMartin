package dev.frontier.industry.smoke;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.frontier.industry.*;
import dev.frontier.industry.engine.*;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real native world/GUI acceptance. Opt-in test artifact only, never part of production. */
@Mod.EventBusSubscriber(modid = IndustryMod.ID)
public final class RedesignSmoke {
    private static final Map<String, Boolean> CHECKS = new LinkedHashMap<>();
    private static final EnumMap<SiteType, UUID> SITES = new EnumMap<>(SiteType.class);
    private static final BlockPos BASE = new BlockPos(5248, 180, 4864);
    private static ServerPlayer player;
    private static MinecraftServer server;
    private static int phase, age, total, savedProgress, nativeJobStarted;
    private static boolean done;
    private static String imageStage = "";
    private static IndustryData.Site site(SiteType type) { return IndustryData.get(server).sites.get(SITES.get(type)); }
    private static void check(String name, boolean value) { CHECKS.put(name, value); System.out.println("REDESIGN " + (value ? "PASS " : "FAIL ") + name); }
    private static void checks(Map<String, Boolean> checks) { checks.forEach(RedesignSmoke::check); }
    private static void position(BlockPos pos) {
        player.teleportTo(player.serverLevel(), pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 0, 0);
        player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = true; player.onUpdateAbilities();
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Boolean.getBoolean("frontier.redesignSmoke") || done) return;
        server = event.getServer(); if (server.getPlayerList().getPlayers().isEmpty()) return;
        if (player == null) player = server.getPlayerList().getPlayers().get(0);
        if (++total < 60) return;
        try {
            age++;
            if (phase == 0) {
                if (Boolean.getBoolean("frontier.redesignReload")) {
                    position(BASE);
                    for (int x = -5; x <= 5; x++) for (int z = -4; z <= 6; z++) {
                        player.serverLevel().getChunk((BASE.getX() >> 4) + x, (BASE.getZ() >> 4) + z);
                        player.serverLevel().setChunkForced((BASE.getX() >> 4) + x, (BASE.getZ() >> 4) + z, true);
                    }
                    phase = 90; age = 0; return;
                }
                if (Boolean.getBoolean("frontier.redesignCombatOnly")) {
                    Class.forName("dev.frontier.industry.combat.NativeCombatSmoke").getMethod("start", ServerPlayer.class).invoke(null, player);
                    phase = 15; age = 0; return;
                }
                checks(EngineRuntimeChecks.run());
                var existing = IndustryData.get(server).sites.values();
                check("actual released0.2 copied SavedData has legacy sites", !existing.isEmpty() && existing.stream().allMatch(s -> s.blueprintVersion == Blueprint.LEGACY_VERSION));
                check("native DeepStrike loaded with installed SBW", net.minecraftforge.fml.ModList.get().isLoaded("deepstrike"));
                check("single preferred PDA is the actual native command tablet", net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(IndustryContent.preferredTablet()).toString().equals("deepstrike:jagm_camera_tablet"));
                position(BASE);
                for (int x = -5; x <= 5; x++) for (int z = -4; z <= 6; z++) {
                    player.serverLevel().getChunk((BASE.getX() >> 4) + x, (BASE.getZ() >> 4) + z);
                    player.serverLevel().setChunkForced((BASE.getX() >> 4) + x, (BASE.getZ() >> 4) + z, true);
                }
                player.serverLevel().setDayTime(1000); player.serverLevel().setWeatherParameters(6000, 0, false, false);
                for (int x = -68; x <= 68; x++) for (int z = -27; z <= 70; z++)
                    player.serverLevel().setBlock(BASE.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                phase = 1; age = 0;
            } else if (phase == 1 && age >= 40) {
                int[][] offsets = {{-42,0}, {0,0}, {42,0}, {-42,44}, {0,44}, {42,44}};
                for (SiteType type : SiteType.values()) {
                    BlockPos origin = BASE.offset(offsets[type.ordinal()][0], 0, offsets[type.ordinal()][1]); position(origin);
                    UUID id = IndustryEngine.place(player, type, origin); check("physical v2 " + type + " placed", id != null);
                    if (id == null) throw new IllegalStateException("Placement " + type);
                    SITES.put(type, id); check("v2 " + type + " all native blocks match", site(type).matching == site(type).total && site(type).total > 100);
                }
                check("substation auto connects to thermal station", SITES.get(SiteType.POWER_STATION).equals(site(SiteType.SUBSTATION).upstream));
                for (SiteType type : SiteType.values()) if (type.isFactory()) check("auto power line " + type, SITES.get(SiteType.SUBSTATION).equals(site(type).upstream));
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(IndustryContent.preferredTablet()));
                int index = 1; for (SiteType type : SiteType.values()) player.getInventory().setItem(index++, new ItemStack(IndustryContent.deployer(type)));
                player.getInventory().setItem(7, new ItemStack(IndustryContent.REPAIR_KIT.get(), 16));
                player.getInventory().setItem(8, new ItemStack(Items.EMERALD, 64)); player.getInventory().selected = 0;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));
                player.inventoryMenu.broadcastChanges();
                site(SiteType.POWER_STATION).inventory.setStackInSlot(0, new ItemStack(Items.COAL, 8));
                var factory = site(SiteType.EQUIPMENT_FACTORY); position(factory.origin);
                IndustryEngine.action(player, factory.id, "staff", "", 1);
                var recipe = IndustryCatalog.resolve("minecraft:iron_sword", SiteType.EQUIPMENT_FACTORY);
                int materialSlot = 9;
                for (var stack : recipe.cost()) player.getInventory().setItem(materialSlot++, stack.copy());
                player.inventoryMenu.broadcastChanges();
                phase = 2; age = 0;
            } else if (phase == 2 && age >= 100) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                check("v2 factory has connected finite upstream power without idle FE hoarding", factory.powered && site(SiteType.SUBSTATION).energy > 0);
                check("one-click staff creates or assigns actual production NPC", factory.liveProduction > 0);
                try {
                    Object result = Class.forName("dev.frontier.industry.pda.NativePdaRecruitmentChecks").getMethod("run", ServerPlayer.class).invoke(null, player);
                    if (result instanceof Map<?, ?> values) for (var entry : values.entrySet()) if (entry.getValue() instanceof Boolean value) check("native PDA hire " + entry.getKey(), value);
                } catch (ReflectiveOperationException nativeFailure) {
                    nativeFailure.printStackTrace(); check("native PDA hiring module completed", false);
                }
                try {
                    Object result = Class.forName("dev.frontier.industry.pda.NativePdaShopChecks").getMethod("run", ServerPlayer.class).invoke(null, player);
                    if (result instanceof Map<?, ?> values) for (var entry : values.entrySet()) if (entry.getValue() instanceof Boolean value) check("native PDA shop " + entry.getKey(), value);
                } catch (ReflectiveOperationException nativeFailure) { nativeFailure.printStackTrace(); check("native PDA shop module completed", false); }
                for (int i = 0; i < player.getInventory().items.size(); i++) if (player.getInventory().getItem(i).is(Items.EMERALD)) player.getInventory().setItem(i, ItemStack.EMPTY);
                for (int i = 20; i < 28; i++) player.getInventory().setItem(i, new ItemStack(Items.EMERALD, 64));
                player.getInventory().selected = 0;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(0));
                player.inventoryMenu.broadcastChanges();
                for (String id : new String[]{"deepstrike:fp5", "deepstrike:storm_shadow", "deepstrike:peacekeeper", "deepstrike:fp5_launcher", "deepstrike:peacekeeper_platform", "deepstrike:mim_104_patriot_truck", "deepstrike:iron_dome", "deepstrike:pac_3"})
                    check("native missile factory catalog " + id, IndustryCatalog.resolve(id, SiteType.MISSILE_FACTORY) != null);
                check("old custom launcher hidden from new orders", IndustryCatalog.resolve("frontier_industry:patriot_launcher", SiteType.MISSILE_FACTORY) == null);
                check("legacy queued recipe retained for save compatibility", IndustryCatalog.recipes().stream().anyMatch(r -> r.id().equals("frontier_industry:patriot_launcher")));
                OperationsNet.open(player); requestImage("hub"); phase = 3; age = 0;
            } else if (phase == 3) {
                if (!imageFinished() && age < 180) return;
                check("native PDA opens actual unified client screen", Boolean.getBoolean("frontier.redesign.hubVisible"));
                requestImage("country"); phase = 4; age = 0;
            } else if (phase == 4) {
                if (!imageFinished() && age < 180) return;
                requestImage("army"); phase = 5; age = 0;
            } else if (phase == 5) {
                if (!imageFinished() && age < 180) return;
                requestImage("shop"); phase = 19; age = 0;
            } else if (phase == 19) {
                if (!imageFinished() && age < 180) return;
                int emeralds = 0, aircraft = 0;
                for (var stack : player.getInventory().items) {
                    if (stack.is(Items.EMERALD)) emeralds += stack.getCount();
                    if (stack.hasTag() && stack.getTag().contains("BlockEntityTag", 10)
                        && "superbwarfare:a_10a".equals(stack.getTagElement("BlockEntityTag").getString("EntityType"))) aircraft += stack.getCount();
                }
                check("real shop GUI C2S buys actual A10 with exact emerald debit", aircraft == 1 && emeralds == 128);
                requestImage("industry"); phase = 6; age = 0;
            } else if (phase == 6) {
                if (!imageFinished() && age < 180) return;
                requestImage("icons"); phase = 7; age = 0;
            } else if (phase == 7) {
                if (!imageFinished() && age < 180) return;
                player.teleportTo(player.serverLevel(), BASE.getX() + 28, BASE.getY() + 27, BASE.getZ() - 47, 31, 20);
                requestImage("buildings"); phase = 18; age = 0;
            } else if (phase == 18) {
                if (!imageFinished() && age < 180) return;
                var factory = site(SiteType.EQUIPMENT_FACTORY); position(factory.origin); IndustryMenus.open(player, factory.id);
                requestImage("factory"); phase = 8; age = 0;
            } else if (phase == 8) {
                if (!imageFinished() && age < 240) return;
                check("simplified factory actual product-selection GUI", Boolean.getBoolean("frontier.redesign.factoryVisible"));
                check("factory search inventory key does not close screen", Boolean.getBoolean("frontier.redesign.searchGuard"));
                phase = 9; age = 0;
            } else if (phase == 9) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                if (factory.orders.peek() != null && factory.orders.peek().progress >= 40) {
                    check("new GUI real C2S creates paid order", factory.orders.peek().reserved);
                    savedProgress = factory.orders.peek().progress;
                    var core = Blueprint.criticalBlocks(SiteType.SUBSTATION, site(SiteType.SUBSTATION).origin, Blueprint.CURRENT_VERSION).keySet().iterator().next();
                    player.serverLevel().setBlock(core, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    phase = 10; age = 0;
                } else if (age > 360) { check("GUI order received within360 ticks", false); phase = 14; age = 0; }
            } else if (phase == 10 && age >= 40) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                var substation = site(SiteType.SUBSTATION);
                check("one destroyed transformer core stops power with almost whole yard intact", substation.matching * 100L / substation.total > 90 && !substation.functional());
                check("power outage preserves paid v2 queue within bounded20tick machinery poll", factory.orders.peek() != null && factory.orders.peek().reserved
                    && factory.orders.peek().progress >= savedProgress && factory.orders.peek().progress <= savedProgress + 20 && !factory.powered);
                savedProgress = factory.orders.peek().progress;
                phase = 16; age = 0;
            } else if (phase == 16 && age >= 25) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                var substation = site(SiteType.SUBSTATION);
                check("v2 factory stays paused across ordinaryticks after grid polling", factory.orders.peek() != null && factory.orders.peek().progress == savedProgress && !factory.powered);
                for (var e : Blueprint.criticalBlocks(SiteType.SUBSTATION, substation.origin, Blueprint.CURRENT_VERSION).entrySet()) player.serverLevel().setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
                phase = 11; age = 0;
            } else if (phase == 11 && age >= 50) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                check("physical transformer restoration resumes same paid queue", factory.orders.peek() != null && factory.orders.peek().reserved && factory.orders.peek().progress > savedProgress);
                savedProgress = factory.orders.peek().progress;
                var droneType = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new net.minecraft.resources.ResourceLocation("uncomplicatedfpv", "shahed136"));
                var drone = droneType.create(player.serverLevel()); drone.setPos(factory.origin.getX() + .5, factory.origin.getY() + 2.5, factory.origin.getZ() + .5);
                player.serverLevel().addFreshEntity(drone); position(factory.origin.offset(35, 2, -30));
                drone.getClass().getMethod("explode").invoke(drone); phase = 12; age = 0;
            } else if (phase == 12 && age >= 40) {
                var factory = site(SiteType.EQUIPMENT_FACTORY);
                check("actual mod Shahed blast damages creative v2 factory", factory.matching < factory.total * .75 && !factory.functional());
                check("actual mod Shahed pauses paid v2 queue without loss", factory.orders.peek() != null && factory.orders.peek().reserved && factory.orders.peek().progress == savedProgress);
                phase = 14; age = 0;
            } else if (phase == 14) {
                var missileFactory = site(SiteType.MISSILE_FACTORY); position(missileFactory.origin);
                var recipe = IndustryCatalog.resolve("deepstrike:fp5", SiteType.MISSILE_FACTORY);
                for (var cost : recipe.cost()) player.getInventory().add(cost.copy());
                IndustryEngine.action(player, missileFactory.id, "staff", "", 1);
                IndustryEngine.action(player, missileFactory.id, "supply", recipe.id(), 1);
                IndustryEngine.action(player, missileFactory.id, "order", recipe.id(), 1);
                check("native FP5 queued through real factory actions", !missileFactory.orders.isEmpty()); nativeJobStarted = server.getTickCount();
                // A separate opt-in native missile fixture is invoked after industrial acceptance when present.
                try {
                    Class<?> test = Class.forName("dev.frontier.industry.combat.NativeCombatSmoke");
                    test.getMethod("start", ServerPlayer.class).invoke(null, player); phase = 15; age = 0;
                } catch (ClassNotFoundException notYetBuilt) { finish(); }
            } else if (phase == 15) {
                Class<?> test = Class.forName("dev.frontier.industry.combat.NativeCombatSmoke"); test.getMethod("tick").invoke(null);
                if (Boolean.TRUE.equals(test.getMethod("done").invoke(null))) {
                    Object result = test.getMethod("results").invoke(null);
                    if (result instanceof Map<?, ?> values) for (var entry : values.entrySet()) if (entry.getValue() instanceof Boolean value) check("native weapons " + entry.getKey(), value);
                    if (Boolean.getBoolean("frontier.redesignCombatOnly")) finish();
                    else { phase = 17; age = 0; }
                } else if (age > 4500) { check("native missile fixture bounded finish", false); finish(); }
            } else if (phase == 17) {
                var factory = site(SiteType.MISSILE_FACTORY);
                if (factory.orders.isEmpty() || server.getTickCount() - nativeJobStarted > 2350) {
                    int outputs = 0; for (int slot = 12; slot < 24; slot++) {
                        var stack = factory.inventory.getStackInSlot(slot);
                        if ("deepstrike:fp5".equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())))) outputs += stack.getCount();
                    }
                    check("factory produces exactlyone actual DeepStrike FP5 from paid resources", outputs == 1 && factory.orders.isEmpty()); finish();
                }
            }
            if (phase == 90 && age >= 50) {
                reloadChecks();
                player.teleportTo(player.serverLevel(), BASE.getX() + 32, BASE.getY() + 34, BASE.getZ() - 52, 47, 26);
                player.getAbilities().flying = true; player.onUpdateAbilities();
                requestImage("buildings"); phase = 91; age = 0;
            } else if (phase == 91 && (imageFinished() || age > 180)) {
                OperationsNet.open(player); requestImage("shop"); phase = 92; age = 0;
            } else if (phase == 92 && (imageFinished() || age > 180)) {
                check("fresh client shows native PDA shop after serial Forge loading", Boolean.getBoolean("frontier.redesign.shopVisible")); finish();
            }
            if (total > 8500) { check("bounded smoke completion", false); finish(); }
        } catch (Throwable failure) { failure.printStackTrace(); check("unhandled " + failure, false); try { finish(); } catch (Exception ignored) { done = true; } }
    }
    private static void requestImage(String name) { imageStage = name; System.setProperty("frontier.redesign.ui", name); }
    private static boolean imageFinished() { return imageStage.equals(System.getProperty("frontier.redesign.uiDone", "")); }
    private static void reloadChecks() throws Exception {
        var path = server.getServerDirectory().toPath().resolve("redesign-v2-receipt.nbt");
        var expected = net.minecraft.nbt.NbtIo.readCompressed(path.toFile());
        var saved = expected.getList("Sites", 10); var data = IndustryData.get(server);
        int count = 0;
        for (int i = 0; i < saved.size(); i++) {
            var before = saved.getCompound(i); if (before.getInt("BlueprintVersion") != Blueprint.CURRENT_VERSION) continue;
            UUID id = before.getUUID("Id"); var actual = data.sites.get(id); count++;
            String type = before.getString("Type");
            check("fresh process preserves v2 footprint and links " + type, actual != null && actual.blueprintVersion == Blueprint.CURRENT_VERSION
                && actual.origin.asLong() == before.getLong("Origin") && actual.type.name().equals(type)
                && (!before.hasUUID("Upstream") || before.getUUID("Upstream").equals(actual.upstream)));
            if (actual == null) continue;
            check("fresh process preserves native output and material stock " + type, before.getCompound("Inventory").equals(actual.inventory.serializeNBT()));
            var now = data.save(new net.minecraft.nbt.CompoundTag()).getList("Sites", 10);
            for (int j = 0; j < now.size(); j++) if (now.getCompound(j).getUUID("Id").equals(id))
                check("fresh process consumes no second payment and preserves queue " + type, before.getList("Orders", 10).equals(now.getCompound(j).getList("Orders", 10)));
        }
        check("fresh process reloads all six original v2 sites", count == 6);
        var workers = expected.getList("Workers", 10);
        check("fresh process preserves worker custody IDs", java.util.stream.IntStream.range(0, workers.size()).allMatch(i -> data.workers.containsKey(workers.getCompound(i).getUUID("Id"))));
    }
    private static void finish() throws Exception {
        done = true;
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 6; z++) player.serverLevel().setChunkForced((BASE.getX() >> 4) + x, (BASE.getZ() >> 4) + z, false);
        JsonObject result = new JsonObject(); result.addProperty("allPassed", !CHECKS.isEmpty() && CHECKS.values().stream().allMatch(Boolean::booleanValue));
        var gson = new GsonBuilder().setPrettyPrinting().create(); result.add("checks", gson.toJsonTree(CHECKS)); result.add("pda", Operations.snapshot(player));
        String reportName = Boolean.getBoolean("frontier.redesignReload") ? "redesign-reload-results.json" : "redesign-results.json";
        Files.writeString(server.getServerDirectory().toPath().resolve(reportName), gson.toJson(result));
        if (!Boolean.getBoolean("frontier.redesignReload") && !Boolean.getBoolean("frontier.redesignCombatOnly"))
            net.minecraft.nbt.NbtIo.writeCompressed(IndustryData.get(server).save(new net.minecraft.nbt.CompoundTag()), server.getServerDirectory().toPath().resolve("redesign-v2-receipt.nbt").toFile());
        server.saveEverything(true, true, true); System.setProperty("frontier.redesign.done", "true");
    }
}
