package dev.frontier.industry.engine;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsGroup;
import dev.frontier.industry.IndustryCatalog;
import dev.frontier.industry.IndustryContent;
import dev.frontier.industry.IndustryEngine;
import dev.frontier.industry.SiteType;
import dev.frontier.industry.WorkerEntity;
import dev.frontier.industry.engine.IndustryData.Site;
import dev.frontier.industry.engine.IndustryData.Worker;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Real-tick test fixture for an explicitly isolated copied world. Never included in the release jar. */
public final class EngineSmoke {
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    private static final EnumMap<SiteType, UUID> SITES = new EnumMap<>(SiteType.class);
    private static final EnumMap<SiteType, IndustryCatalog.Recipe> RECIPES = new EnumMap<>(SiteType.class);
    private static final List<UUID> ENTITIES = new ArrayList<>(), WORKERS = new ArrayList<>();
    private static ServerPlayer player;
    private static ServerLevel level;
    private static IndustryData data;
    private static BlockPos base, missingManual, foreign, chest, missingNpc;
    private static ListTag oldInventory;
    private static double oldX, oldY, oldZ;
    private static float oldYaw, oldPitch;
    private static int started, repairStart, manualKits, nativeArmyBaseline;
    private static long pausedProgress;
    private static boolean done = true, sawNpc1799, checkedRepair;
    private static ItemStack manualKit;
    private static UUID nativeGroupId;
    private EngineSmoke() {}
    public static boolean finished() { return done; }
    public static Map<String, Boolean> results() { return Map.copyOf(RESULTS); }

    public static void run(ServerPlayer owner) {
        if (!done) throw new IllegalStateException("Engine smoke already running");
        RESULTS.clear(); SITES.clear(); RECIPES.clear(); ENTITIES.clear(); WORKERS.clear();
        RESULTS.putAll(EngineRuntimeChecks.run());
        player = owner; level = owner.serverLevel(); data = IndustryData.get(owner.server);
        oldX = owner.getX(); oldY = owner.getY(); oldZ = owner.getZ(); oldYaw = owner.getYRot(); oldPitch = owner.getXRot();
        oldInventory = owner.getInventory().save(new ListTag());
        nativeGroupId = null;
        nativeArmyBaseline = RecruitEvents.recruitsPlayerUnitManager == null ? -1 : RecruitEvents.recruitsPlayerUnitManager.getRecruitCount(owner.getUUID());
        base = owner.blockPosition().offset(0, Math.min(36, level.getMaxBuildHeight() - owner.getBlockY() - 12), 0);
        started = owner.server.getTickCount(); repairStart = 0; done = false; sawNpc1799 = checkedRepair = false;
        try {
            owner.getInventory().clearContent(); owner.getInventory().setItem(8, new ItemStack(Items.EMERALD, 64));
            int[][] offsets = {{0,0}, {12,0}, {24,0}, {36,0}, {24,12}, {36,12}};
            for (SiteType type : SiteType.values()) {
                BlockPos origin = base.offset(offsets[type.ordinal()][0], 0, offsets[type.ordinal()][1]);
                move(origin); UUID id = legacyFixture(owner, type, origin);
                check("physical " + type.name() + " placement", id != null);
                if (id == null) throw new IllegalStateException("empty loaded arena required");
                SITES.put(type, id);
            }
            move(site(SiteType.DRONE_FACTORY).origin);
            check("native factory bypass rejected", !IndustryEngine.link(owner, SITES.get(SiteType.POWER_STATION), SITES.get(SiteType.DRONE_FACTORY)));
            move(site(SiteType.SUBSTATION).origin);
            check("native plant substation link", IndustryEngine.link(owner, SITES.get(SiteType.POWER_STATION), SITES.get(SiteType.SUBSTATION)));
            for (SiteType type : SiteType.values()) if (type.isFactory()) {
                Site site = site(type); move(site.origin);
                check("native substation " + type.name() + " link", IndustryEngine.link(owner, SITES.get(SiteType.SUBSTATION), site.id));
                IndustryCatalog.Recipe recipe = IndustryCatalog.recipes().stream().filter(r -> r.factory() == type).findFirst().orElse(null);
                check("catalog recipe for " + type.name(), recipe != null);
                if (recipe == null) throw new IllegalStateException("required factory catalog empty");
                RECIPES.put(type, recipe); check("real material input " + type.name(), StockTransactions.refundInputs(site.inventory, recipe.cost()));
                IndustryEngine.action(owner, site.id, "hire", "production", 1);
                IndustryEngine.action(owner, site.id, "order", recipe.id(), 1);
                check("queue accepted " + type.name(), site.orders.size() == 1);
            }
            Site plant = site(SiteType.POWER_STATION); plant.inventory.setStackInSlot(0, new ItemStack(Items.COAL, 4));
            Site substation = site(SiteType.SUBSTATION); move(substation.origin); IndustryEngine.action(owner, substation.id, "hire", "grid_repair", 1);
            Worker grid = data.workers.values().stream().filter(w -> substation.id.equals(w.site) && w.owner.equals(owner.getUUID())).findFirst().orElseThrow();
            Site factory = site(SiteType.EQUIPMENT_FACTORY); move(factory.origin); IndustryEngine.action(owner, factory.id, "hire", "factory_repair", 1);
            Worker repairer = data.workers.values().stream().filter(w -> factory.id.equals(w.site) && w.role.equals("factory_repair")).findFirst().orElseThrow();
            IndustryEngine.action(owner, factory.id, "assign", grid.id.toString(), 1);
            check("grid crew cannot be assigned to factory repair", substation.id.equals(grid.site));
            move(substation.origin); IndustryEngine.action(owner, substation.id, "assign", repairer.id.toString(), 2);
            check("factory repair crew cannot repair grid", factory.id.equals(repairer.site));
            substation.inventory.setStackInSlot(1, new ItemStack(IndustryContent.REPAIR_KIT.get(), 2));
            // Start one factory jammed while all the other factories can draw finite grid energy.
            Site drone = site(SiteType.DRONE_FACTORY);
            for (int slot = 12; slot < 24; slot++) drone.inventory.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
            move(factory.origin); transferChecks(factory);
            WORKERS.addAll(data.workers.values().stream().filter(w -> w.owner.equals(owner.getUUID()) && SITES.containsValue(w.site)).map(w -> w.id).toList());
            check("six paid civilian hires", owner.getInventory().countItem(Items.EMERALD) == 16);
            // Ownership rejection is tested against a distinct owner record without touching foreign terrain.
            Site foreignSite = new Site(data, UUID.randomUUID(), UUID.randomUUID(), SiteType.EQUIPMENT_FACTORY, factory.dimension, factory.origin);
            data.sites.put(foreignSite.id, foreignSite);
            IndustryEngine.action(owner, foreignSite.id, "order", RECIPES.get(SiteType.EQUIPMENT_FACTORY).id(), 64);
            check("foreign owner action rejected natively", foreignSite.orders.isEmpty() && !IndustryEngine.owns(owner, foreignSite.id));
            data.sites.remove(foreignSite.id); data.setDirty();
        } catch (Throwable error) {
            check("setup exception: " + error.getClass().getSimpleName() + " " + error.getMessage(), false); cleanup();
        }
    }

    // These historical custody/1800-tick fixtures intentionally keep their compact 0.2 spacing.
    // The redesign smoke independently exercises the real v2 deployer and its larger clearances.
    private static UUID legacyFixture(ServerPlayer owner, SiteType type, BlockPos origin) {
        Site site = new Site(data, UUID.randomUUID(), owner.getUUID(), type, level.dimension().location(), origin);
        site.team = owner.getTeam() == null ? "" : owner.getTeam().getName();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = Blueprint.blocks(type, origin, site.blueprintVersion);
        for (BlockPos pos : blocks.keySet()) if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()) return null;
        for (var block : blocks.entrySet()) level.setBlock(block.getKey(), block.getValue(), Block.UPDATE_ALL);
        site.total = site.matching = blocks.size(); site.loaded = site.controller = true;
        data.sites.put(site.id, site); data.setDirty(); return site.id;
    }

    public static void tick(MinecraftServer server) {
        if (done || player == null || player.isRemoved()) return;
        int elapsed = server.getTickCount() - started;
        try {
            if (elapsed == 60) {
                Site drone = site(SiteType.DRONE_FACTORY);
                check("live output jam prevents reservation and progress", drone.orders.peek() != null && !drone.orders.peek().reserved && drone.orders.peek().progress == 0);
                check("other real factories progress from finite FE", SITES.keySet().stream().filter(SiteType::isFactory).filter(t -> t != SiteType.DRONE_FACTORY).anyMatch(t -> !site(t).orders.isEmpty() && site(t).orders.peek().progress > 0));
                Site plant = site(SiteType.POWER_STATION);
                level.setBlock(Blueprint.controller(plant.type, plant.origin, plant.blueprintVersion), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                pausedProgress = progressSum();
            }
            if (elapsed == 100) {
                check("destroyed plant controller pauses all downstream production", progressSum() == pausedProgress);
                Site plant = site(SiteType.POWER_STATION);
                level.setBlock(Blueprint.controller(plant.type, plant.origin, plant.blueprintVersion), IndustryContent.controller(plant.type).defaultBlockState(), Block.UPDATE_ALL);
                Site drone = site(SiteType.DRONE_FACTORY); for (int slot = 12; slot < 24; slot++) drone.inventory.setStackInSlot(slot, ItemStack.EMPTY);
                startRealRepairs();
            }
            if (repairStart > 0 && !checkedRepair) {
                Site substation = site(SiteType.SUBSTATION);
                if (substation.repairProgress == 1799) {
                    sawNpc1799 = true;
                    check("real NPC repair does not restore at tick1799", level.getBlockState(missingNpc).isAir() && substation.repairKit);
                    check("real NPC paid kit and tick1799 survive native NBT", IndustryData.load(data.save(new CompoundTag())).sites.get(substation.id).repairProgress == 1799);
                }
                int heldElapsed = server.getTickCount() - repairStart;
                if (heldElapsed == 7) {
                    int remainingBeforeDuplicate = player.getUseItemRemainingTicks();
                    check("duplicate native use preserves running1800tick repair", IndustryEngine.beginRepair(player, manualKit)
                        && player.isUsingItem() && player.getUseItemRemainingTicks() == remainingBeforeDuplicate);
                }
                if (heldElapsed == 1798) check("real held manual repair cannot finish early", level.getBlockState(missingManual).isAir() && manualKit.getCount() == manualKits);
                if (heldElapsed >= 1803) {
                    checkedRepair = true;
                    System.setProperty("frontier.industrySmokeHoldRepair", "false");
                    check("real NPC reached tick1799", sawNpc1799);
                    check("real NPC restores controller after1800 and consumes one kit", level.getBlockState(missingNpc).is(IndustryContent.controller(SiteType.SUBSTATION)) && substation.inventory.getStackInSlot(1).getCount() == 1 && !substation.repairKit);
                    check("real manual held1800 restores missing blueprint and pays exactlyone kit", !level.getBlockState(missingManual).isAir() && manualKit.getCount() == manualKits - 1);
                    check("real manual repair preserves foreign block", level.getBlockState(foreign).is(Blocks.DIAMOND_BLOCK));
                    check("real manual repair preserves chest and contents", level.getBlockEntity(chest) instanceof ChestBlockEntity container && container.getItem(0).is(Items.DIAMOND) && container.getItem(0).getCount() == 37);
                }
            }
            if (elapsed >= 2200 && SITES.keySet().stream().filter(SiteType::isFactory).allMatch(t -> site(t).orders.isEmpty())) {
                for (SiteType type : RECIPES.keySet()) check("physical paid production output " + type.name(), containsOutput(site(type), RECIPES.get(type).output()));
                check("generation consumes coal instead of free FE", site(SiteType.POWER_STATION).inventory.getStackInSlot(0).getCount() < 4);
                check("all native buffers remain finite", SITES.values().stream().map(data.sites::get).allMatch(s -> s.energy >= 0 && s.energy <= IndustryRules.BUFFER));
                cleanup();
            } else if (elapsed > 5100) {
                for (SiteType type : RECIPES.keySet()) check("physical paid production output " + type.name(), containsOutput(site(type), RECIPES.get(type).output()));
                check("all four factories finish within5100 real ticks", false); cleanup();
            }
        } catch (Throwable error) { check("tick exception: " + error.getClass().getSimpleName() + " " + error.getMessage(), false); cleanup(); }
    }

    private static void startRealRepairs() {
        Site substation = site(SiteType.SUBSTATION);
        missingNpc = Blueprint.controller(substation.type, substation.origin, substation.blueprintVersion); level.setBlock(missingNpc, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        move(substation.origin); IndustryEngine.action(player, substation.id, "repair", "", 1);
        Site factory = site(SiteType.EQUIPMENT_FACTORY); int r = Blueprint.radius(factory.type, factory.blueprintVersion);
        missingManual = factory.origin.offset(-r, 1, -r); foreign = factory.origin.offset(-r, 2, -r); chest = factory.origin.offset(-r, 3, -r);
        level.setBlock(missingManual, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(foreign, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity container) container.setItem(0, new ItemStack(Items.DIAMOND, 37));
        move(factory.origin);
        manualKit = new ItemStack(IndustryContent.REPAIR_KIT.get(), 3); manualKits = manualKit.getCount();
        player.setItemInHand(InteractionHand.MAIN_HAND, manualKit);
        System.setProperty("frontier.industrySmokeHoldRepair", "true");
        check("manual repair begins on real held native item", IndustryEngine.beginRepair(player, manualKit));
        player.startUsingItem(InteractionHand.MAIN_HAND); repairStart = player.server.getTickCount();
    }
    private static void transferChecks(Site nearby) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits:bowman"));
        check("installed native Recruit type", type != null);
        if (type == null) return;
        var units = RecruitEvents.recruitsPlayerUnitManager;
        if (units == null || RecruitEvents.recruitsGroupsManager == null || FactionEvents.recruitsFactionManager == null) { check("native army managers ready", false); return; }
        int baseline = units.getRecruitCount(player.getUUID());
        check("civilian hires independent of army counter", baseline == nativeArmyBaseline);
        var nativeFaction = player.getTeam() == null ? null : FactionEvents.recruitsFactionManager.getFactionByStringID(player.getTeam().getName());
        int factionBaseline = nativeFaction == null ? -1 : nativeFaction.getNPCs();
        RecruitsGroup group = new RecruitsGroup("Industry custody fixture", player, 0); nativeGroupId = group.getUUID();
        RecruitEvents.recruitsGroupsManager.addOrUpdateGroup(level, player, group);
        ItemStack tablet = new ItemStack(IndustryContent.TABLET.get());
        List<UUID> transfers = new ArrayList<>(), originals = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            Entity fresh = type.create(level);
            if (!(fresh instanceof AbstractRecruitEntity recruit)) { check("native Recruit fixture", false); return; }
            recruit.setIsOwned(true); recruit.setOwnerUUID(java.util.Optional.of(player.getUUID())); recruit.setPersistenceRequired(); recruit.setNoAi(true);
            recruit.moveTo(player.getX() + 1, player.getY(), player.getZ(), 0, 0);
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD); sword.setHoverName(Component.literal("Recruit custody " + n)); sword.setDamageValue(17 + n); recruit.setItemSlot(EquipmentSlot.MAINHAND, sword);
            level.addFreshEntity(recruit); originals.add(recruit.getUUID()); ENTITIES.add(recruit.getUUID());
            enroll(recruit, group);
            boolean selected = IndustryEngine.selectWorker(player, recruit, tablet);
            if (n < 7) {
                check("native army transfer " + (n + 1), selected && recruit.isRemoved()); if (selected) transfers.add(tablet.getTag().getUUID("SelectedWorker"));
                check("native unit and group debit exactlyonce " + n, units.getRecruitCount(player.getUUID()) == baseline && !group.members.contains(recruit.getUUID()));
                if (nativeFaction != null) check("native faction debit " + n, nativeFaction.getNPCs() == factionBaseline);
                check("native source team removed while civilian", level.getScoreboard().getPlayersTeam(recruit.getStringUUID()) == null);
            }
            else check("native eighth army transfer refused with source alive", !selected && !recruit.isRemoved());
        }
        for (int n = 0; n < transfers.size(); n++) {
            UUID worker = transfers.get(n); ENTITIES.add(worker);
            IndustryEngine.action(player, nearby.id, "dismiss", worker.toString(), 1);
            Entity restored = level.getEntity(originals.get(n));
            check("native recruit return UUID equipment " + n, restored instanceof AbstractRecruitEntity recruit && recruit.getMainHandItem().hasCustomHoverName()
                && recruit.getMainHandItem().getDamageValue() == 17 + n && level.getEntity(worker) == null);
            check("native return credits army and restores existing own group " + n, units.getRecruitCount(player.getUUID()) == baseline + n + 2 && group.members.contains(originals.get(n)));
            int credited = units.getRecruitCount(player.getUUID());
            if (restored instanceof AbstractRecruitEntity recruit) NativeRecruitCustody.credit(data, data.workers.get(worker), recruit);
            check("native return journal blocks repeated credit " + n, units.getRecruitCount(player.getUUID()) == credited);
        }
        Entity extra = type.create(level);
        if (extra instanceof AbstractRecruitEntity recruit) {
            recruit.setIsOwned(true); recruit.setOwnerUUID(java.util.Optional.of(player.getUUID())); recruit.setPersistenceRequired(); recruit.setNoAi(true);
            recruit.moveTo(player.getX() + 1, player.getY(), player.getZ(), 0, 0); level.addFreshEntity(recruit); enroll(recruit, group); ENTITIES.add(recruit.getUUID());
            int before = units.getRecruitCount(player.getUUID());
            boolean selected = IndustryEngine.selectWorker(player, recruit, tablet);
            if (selected) {
                UUID civilianId = tablet.getTag().getUUID("SelectedWorker"); ENTITIES.add(civilianId);
                Entity civilian = level.getEntity(civilianId);
                if (civilian instanceof WorkerEntity worker) {
                    CancelWorkerDeath cancellation = new CancelWorkerDeath(worker.getUUID());
                    CompoundTag sourceBeforeDeath = data.workers.get(civilianId).source.copy();
                    MinecraftForge.EVENT_BUS.register(cancellation);
                    try { worker.hurt(level.damageSources().genericKill(), Float.MAX_VALUE); }
                    finally { MinecraftForge.EVENT_BUS.unregister(cancellation); }
                    check("cancelled native LivingDeath preserves civilian and recruit custody", cancellation.seen && worker.isAlive()
                        && data.workers.get(civilianId).active() && !data.workers.get(civilianId).dead
                        && sourceBeforeDeath.equals(data.workers.get(civilianId).source) && units.getRecruitCount(player.getUUID()) == before - 1);
                    worker.invulnerableTime = 0;
                    worker.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                }
                IndustryEngine.action(player, nearby.id, "dismiss", civilianId.toString(), 1);
                check("native transferred death never credits ghost troop or returns source", data.workers.get(civilianId).dead
                    && units.getRecruitCount(player.getUUID()) == before - 1 && level.getEntity(recruit.getUUID()) == null && !group.members.contains(recruit.getUUID()));
            } else check("native transfer death fixture created", false);
        }
        Entity removedGroupFixture = type.create(level);
        if (removedGroupFixture instanceof AbstractRecruitEntity recruit) {
            recruit.setIsOwned(true); recruit.setOwnerUUID(java.util.Optional.of(player.getUUID())); recruit.setPersistenceRequired(); recruit.setNoAi(true);
            recruit.moveTo(player.getX() + 1, player.getY(), player.getZ(), 0, 0); level.addFreshEntity(recruit); enroll(recruit, group); ENTITIES.add(recruit.getUUID());
            boolean selected = IndustryEngine.selectWorker(player, recruit, tablet);
            if (selected) {
                UUID civilianId = tablet.getTag().getUUID("SelectedWorker"); ENTITIES.add(civilianId);
                RecruitEvents.recruitsGroupsManager.removeGroup(group.getUUID());
                IndustryEngine.action(player, nearby.id, "dismiss", civilianId.toString(), 1);
                Entity returned = level.getEntity(recruit.getUUID());
                check("return safely clears deleted native group without resurrecting it", returned instanceof AbstractRecruitEntity nativeRecruit && nativeRecruit.getGroup() == null
                    && RecruitEvents.recruitsGroupsManager.getGroup(group.getUUID()) == null);
            } else check("deleted group transfer fixture created", false);
        }
    }
    private static void enroll(AbstractRecruitEntity recruit, RecruitsGroup group) {
        RecruitEvents.recruitsPlayerUnitManager.addRecruits(player.getUUID(), 1);
        recruit.getPersistentData().putBoolean("industrySmokeNativeCounted", true);
        recruit.setGroupUUID(group.getUUID()); RecruitEvents.recruitsGroupsManager.addMember(group.getUUID(), recruit.getUUID(), level);
        if (player.getTeam() != null) {
            FactionEvents.addRecruitToTeam(recruit, player.getTeam(), level);
            var faction = FactionEvents.recruitsFactionManager.getFactionByStringID(player.getTeam().getName()); if (faction != null) faction.addNPCs(1);
        }
    }
    private static long progressSum() { return SITES.keySet().stream().filter(SiteType::isFactory).map(EngineSmoke::site).mapToLong(s -> s.orders.isEmpty() ? 100000 : s.orders.peek().progress).sum(); }
    private static boolean containsOutput(Site site, ItemStack expected) {
        for (int slot = 12; slot < 24; slot++) if (ItemStack.isSameItemSameTags(site.inventory.getStackInSlot(slot), expected) && site.inventory.getStackInSlot(slot).getCount() >= expected.getCount()) return true;
        return false;
    }
    private static Site site(SiteType type) { return data.sites.get(SITES.get(type)); }
    private static void move(BlockPos origin) { player.teleportTo(level, origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5, 0, 0); }
    private static void check(String name, boolean value) { RESULTS.put("engine " + name, value); }
    public static final class CancelWorkerDeath {
        private final UUID target;
        boolean seen;
        CancelWorkerDeath(UUID target) { this.target = target; }
        @SubscribeEvent public void cancel(LivingDeathEvent event) {
            if (event.getEntity().getUUID().equals(target)) {
                seen = true; event.setCanceled(true); event.getEntity().setHealth(event.getEntity().getMaxHealth());
            }
        }
    }
    public static void cleanup() {
        System.setProperty("frontier.industrySmokeHoldRepair", "false");
        if (player != null) player.stopUsingItem();
        if (level != null && data != null) {
            for (UUID id : WORKERS) { Entity worker = level.getEntity(id); if (worker != null) worker.discard(); data.workers.remove(id); }
            for (UUID id : ENTITIES) {
                Entity entity = level.getEntity(id);
                if (entity instanceof AbstractRecruitEntity recruit && recruit.getPersistentData().getBoolean("industrySmokeNativeCounted")) {
                    RecruitEvents.recruitsPlayerUnitManager.removeRecruits(player.getUUID(), 1);
                    if (recruit.getGroup() != null) RecruitEvents.recruitsGroupsManager.removeMember(recruit.getGroup(), recruit.getUUID(), level);
                    if (recruit.getTeam() != null) {
                        var faction = FactionEvents.recruitsFactionManager.getFactionByStringID(recruit.getTeam().getName()); if (faction != null) faction.addNPCs(-1);
                        FactionEvents.removeRecruitFromTeam(recruit, recruit.getTeam(), level);
                    }
                }
                if (entity != null) entity.discard(); data.workers.remove(id);
            }
            if (nativeGroupId != null) { RecruitEvents.recruitsGroupsManager.removeGroup(nativeGroupId); RecruitEvents.recruitsGroupsManager.save(level); }
            for (UUID id : SITES.values()) {
                Site site = data.sites.remove(id); if (site == null) continue;
                for (BlockPos pos : Blueprint.blocks(site.type, site.origin, site.blueprintVersion).keySet()) if (level.hasChunkAt(pos)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            data.setDirty();
        }
        if (player != null && !player.isRemoved() && oldInventory != null) {
            player.getInventory().clearContent(); player.getInventory().load(oldInventory); player.teleportTo(level, oldX, oldY, oldZ, oldYaw, oldPitch);
        }
        done = true;
    }
}
