package dev.frontier.industry.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Included only in the dedicated smoke artifact, in a new isolated test save. */
public final class VehicleSmoke {
    private static final Map<String, Object> RESULTS = new LinkedHashMap<>();
    private static final Set<ChunkPos> FORCED = new LinkedHashSet<>();
    private static final CleanupOnStop CLEANUP_ON_STOP = new CleanupOnStop();
    private static ServerPlayer player;
    private static ServerLevel level;
    private static BlockPos origin;
    private static Entity craft;
    private static LivingEntity npc;
    private static Vec3 start;
    private static int stage, age;
    private static double highest;
    private static boolean complete;
    private static boolean ownerChangeStarted, repeatStarted, ejectionStarted, planeStarted;
    private static ItemStackHandler planeFuel;
    private static Object planeEngine;

    private VehicleSmoke() {}
    public static void start(ServerPlayer owner, BlockPos arenaOrigin) {
        if (!Boolean.getBoolean("frontier.industrySmokeTest")) throw new IllegalStateException("Vehicle smoke requires isolated test property");
        player = owner; level = owner.serverLevel(); origin = arenaOrigin.immutable();
        stage = age = 0; complete = false; RESULTS.clear();
        ownerChangeStarted = repeatStarted = ejectionStarted = planeStarted = false;
        planeFuel = null; planeEngine = null;
        MinecraftForge.EVENT_BUS.register(CLEANUP_ON_STOP);
        try {
            // Fixture-only tickets keep the runway endpoint loaded during the preceding boat/tank checks.
            // Production VehicleOperations never creates tickets. Preserve pre-existing forced chunks.
            pinArenaChunks();
            // A water lane and separate tank/plane runway exist solely in the disposable smoke save.
            for (int x = -5; x <= 7; x++) for (int z = -4; z <= 65; z++) {
                level.setBlock(origin.offset(x, -2, z), Blocks.BEDROCK.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, 0, z), Blocks.WATER.defaultBlockState(), 2);
                for (int y = 1; y < 5; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            for (int x = 19; x <= 33; x++) for (int z = -4; z <= 145; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.BEDROCK.defaultBlockState(), 2);
                for (int y = 1; y < 7; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            level.getChunkAt(origin.offset(26, 15, 150));
            craft = new Boat(level, origin.getX() + 0.5, origin.getY() + 0.6, origin.getZ() + 0.5);
            craft.setYRot(0); level.addFreshEntity(craft); npc = worker(craft.position().add(2, 1, 0));
            VehicleOperations.setOwner(craft, player.getUUID());
        } catch (Throwable failure) { failed(failure); }
    }

    public static void tick() {
        if (complete || player == null) return;
        try {
            age++;
            if (stage == 0 && age >= 12) {
                Vec3 target = craft.position().add(0, 0, 45);
                VehicleOperations.setOwner(craft, UUID.randomUUID());
                check("boat rejects another owner's craft", attempt("boat foreign owner", target) != null && !npc.isPassenger());
                VehicleOperations.setOwner(craft, player.getUUID());
                npc.getPersistentData().putString("frontierIndustrySite", UUID.randomUUID().toString());
                check("assigned factory worker rejected", attempt("boat assigned worker", target) != null);
                npc.getPersistentData().remove("frontierIndustrySite");
                start = craft.position();
                check("boat mounts real owned NPC", attempt("boat first route", target) == null && craft.getControllingPassenger() == npc);
                check("boat marks NPC assigned", npc.getPersistentData().hasUUID("frontierIndustryVehicle"));
                stage = 1; age = 0;
            } else if (stage == 1 && age >= 100) {
                double distance = craft.position().distanceTo(start);
                RESULTS.put("boatDistance", distance);
                check("boat moves with NPC over water", distance > 5 && npc.getVehicle() == craft);
                VehicleOperations.stop(player.server, craft);
                check("boat stop cancels controller and horizontal thrust", !VehicleOperations.active(craft) && craft.getDeltaMovement().horizontalDistance() < 0.001);
                check("boat stop restores NPC AI and assignment", !((Mob) npc).isNoAi() && !npc.getPersistentData().contains("frontierIndustryVehicle") && !npc.isPassenger());
                npc.setPos(craft.position().add(1, 1, 0));
                String cooldown = attempt("boat immediate after stop", craft.position().add(0, 0, 20));
                check("immediate repeat respects vanilla boarding cooldown", cooldown != null && cooldown.contains("60 тиков")
                    && !VehicleOperations.active(craft) && !npc.isPassenger());
                stage = 10; age = 0;
            } else if (stage == 10 && age >= 65) {
                boolean automaticallyBoarded = npc.getVehicle() == craft && craft.getControllingPassenger() == npc;
                check("vanilla boat automatically reboards the free NPC after cooldown", automaticallyBoarded);
                if (!npc.isPassenger()) npc.setPos(craft.position().add(1, 1, 0));
                if (automaticallyBoarded) checkCrewCommandWhileSeated();
                start = craft.position();
                repeatStarted = attempt("boat second route after 65 ticks", craft.position().add(0, 0, 20)) == null;
                check("boat can receive second order", repeatStarted && VehicleOperations.active(craft) && npc.getVehicle() == craft);
                stage = 11; age = 0;
            } else if (stage == 11 && age >= 40) {
                double distance = craft.position().distanceTo(start);
                RESULTS.put("boatSecondRouteDistance", distance);
                check("boat second order physically moves same craft and NPC", repeatStarted && distance > 3 && npc.getVehicle() == craft);
                ejectionStarted = VehicleOperations.active(craft) && npc.getVehicle() == craft;
                npc.stopRiding();
                check("NPC ejection cancels route immediately", ejectionStarted && !VehicleOperations.active(craft) && !((Mob) npc).isNoAi()
                    && !npc.getPersistentData().contains("frontierIndustryVehicle"));
                npc.setPos(craft.position().add(1, 1, 0));
                String cooldown = attempt("boat immediate after ejection", craft.position().add(0, 0, 20));
                check("immediate remount after ejection respects vanilla cooldown", ejectionStarted && cooldown != null && cooldown.contains("60 тиков")
                    && !VehicleOperations.active(craft) && !npc.isPassenger());
                stage = 12; age = 0;
            } else if (stage == 12 && age >= 65) {
                if (!npc.isPassenger()) npc.setPos(craft.position().add(1, 1, 0));
                ownerChangeStarted = attempt("boat owner change route after 65 ticks", craft.position().add(0, 0, 20)) == null;
                check("owner change scenario starts", ownerChangeStarted && VehicleOperations.active(craft) && npc.getVehicle() == craft);
                if (ownerChangeStarted) npc.getPersistentData().putString("frontierIndustryOwner", UUID.randomUUID().toString());
                stage = 2; age = 0;
            } else if (stage == 2 && age >= 2) {
                check("NPC owner change cancels route", ownerChangeStarted && !VehicleOperations.active(craft) && !npc.getPersistentData().contains("frontierIndustryVehicle"));
                cleanup();
                craft = VehicleContent.GUNBOAT.get().create(level);
                if (craft == null) throw new IllegalStateException("Gunboat registry create failed");
                craft.setPos(origin.getX() + 0.5, origin.getY() + 0.6, origin.getZ() + 0.5); craft.setYRot(0);
                VehicleOperations.setOwner(craft, player.getUUID()); level.addFreshEntity(craft);
                npc = worker(craft.position().add(2, 1, 0));
                CompoundTag saved = craft.saveWithoutId(new CompoundTag()); saved.putString("id", EntityType.getKey(craft.getType()).toString());
                Entity restored = EntityType.loadEntityRecursive(saved, level, e -> e);
                check("gunboat and ownership survive NBT reload", restored instanceof VehicleGunboat && player.getUUID().equals(VehicleOperations.owner(restored)));
                stage = 3; age = 0;
            } else if (stage == 3 && age >= 12) {
                start = craft.position();
                check("gunboat mounts owned NPC", attempt("gunboat route", craft.position().add(0, 0, 40)) == null && npc.getVehicle() == craft);
                GameType mode = player.gameMode.getGameModeForPlayer();
                CompoundTag abilities = new CompoundTag(); player.getAbilities().addSaveData(abilities);
                Vec3 priorPosition = player.position(), priorMotion = player.getDeltaMovement();
                float priorYaw = player.getYRot(), priorPitch = player.getXRot(), priorFallDistance = player.fallDistance;
                try {
                    player.setGameMode(GameType.SURVIVAL);
                    int ammunition = player.getInventory().countItem(Items.IRON_NUGGET);
                    player.getInventory().add(new ItemStack(Items.IRON_NUGGET, 2));
                    String firing = ((VehicleGunboat) craft).fire(player, craft.position().add(0, 1, 35));
                    RESULTS.put("gunboat firing rejection", firing == null ? "accepted" : firing);
                    check("gunboat consumes real cannon ammunition", firing == null && player.getInventory().countItem(Items.IRON_NUGGET) == ammunition + 1);
                    check("gunboat spawns physical projectile", !level.getEntitiesOfClass(Arrow.class, craft.getBoundingBox().inflate(4)).isEmpty());
                    String cooldown = ((VehicleGunboat) craft).fire(player, craft.position().add(0, 1, 35));
                    RESULTS.put("gunboat cooldown rejection", cooldown == null ? "accepted" : cooldown);
                    check("gunboat cannon has cooldown", cooldown != null);
                } finally {
                    player.setGameMode(mode); player.getAbilities().loadSaveData(abilities); player.onUpdateAbilities();
                    player.teleportTo(level, priorPosition.x, priorPosition.y, priorPosition.z, priorYaw, priorPitch);
                    player.setDeltaMovement(priorMotion); player.fallDistance = priorFallDistance;
                }
                CompoundTag restoredAbilities = new CompoundTag(); player.getAbilities().addSaveData(restoredAbilities);
                check("ammunition fixture preserves player mode and all abilities", player.gameMode.getGameModeForPlayer() == mode && abilities.equals(restoredAbilities));
                check("ammunition fixture preserves player position and flight motion", player.position().distanceToSqr(priorPosition) < 0.000001
                    && player.getDeltaMovement().distanceToSqr(priorMotion) < 0.000001);
                stage = 4; age = 0;
            } else if (stage == 4 && age >= 80) {
                RESULTS.put("gunboatDistance", craft.position().distanceTo(start));
                check("gunboat moves with NPC over water", craft.position().distanceTo(start) > 4 && npc.getVehicle() == craft);
                npc.kill();
                stage = 5; age = 0;
            } else if (stage == 5 && age >= 3) {
                check("NPC death cancels gunboat controller", !VehicleOperations.active(craft));
                cleanup();
                var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("trajanstanks:panzer_two_entity_type"));
                if (type == null) { RESULTS.put("tankStatus", "not installed; untested"); preparePlane(); return; }
                craft = type.create(level);
                if (craft == null) throw new IllegalStateException("Tank registry create failed");
                craft.setPos(origin.getX() + 26.5, origin.getY() + 1, origin.getZ() + 0.5); craft.setYRot(0);
                invoke(craft, "setFuelAmount", new Class<?>[]{int.class}, 4000);
                VehicleOperations.setOwner(craft, player.getUUID()); level.addFreshEntity(craft);
                npc = worker(craft.position().add(3, 1, 0));
                stage = 6; age = 0;
            } else if (stage == 6 && age >= 12) {
                start = craft.position();
                String rejection = attempt("tank route", craft.position().add(0, 0, 65));
                RESULTS.put("tankStatus", rejection == null ? "native NPC route running" : rejection);
                check("patched Trajan tank accepts mounted NPC route", rejection == null && craft.getControllingPassenger() == npc);
                stage = 7; age = 0;
            } else if (stage == 7 && age >= 140) {
                RESULTS.put("tankDistance", craft.position().distanceTo(start));
                check("Trajan tank moves with NPC native input", craft.position().distanceTo(start) > 3 && npc.getVehicle() == craft);
                VehicleOperations.stop(player.server, craft);
                check("Trajan tank stop clears route", !VehicleOperations.active(craft));
                cleanup(); preparePlane();
            } else if (stage == 8 && age >= 12) {
                start = craft.position(); highest = start.y;
                Vec3 target = craft.position().add(0, 15, 135);
                RESULTS.put("planeEndpointLoadedBeforeOrder", level.hasChunkAt(BlockPos.containing(target)));
                check("plane destination loaded by scoped arena fixture", level.hasChunkAt(BlockPos.containing(target)));
                String emptyEngine = attempt("plane empty cold engine", target);
                check("SimplePlanes empty cold engine refuses NPC order", emptyEngine != null && emptyEngine.contains("топливо")
                    && !VehicleOperations.active(craft) && !npc.isPassenger());
                planeFuel.setStackInSlot(0, new ItemStack(Items.COAL, 16));
                check("SimplePlanes fueled cold engine remains unpowered before native ignition",
                    ((Number) planeEngine.getClass().getField("burnTime").get(planeEngine)).intValue() == 0
                        && ((Number) invoke(craft, "getThrottle", new Class<?>[0])).intValue() == 0
                        && !Boolean.TRUE.equals(invoke(craft, "isPowered", new Class<?>[0])));
                String rejection = attempt("plane route", target);
                planeStarted = rejection == null;
                RESULTS.put("planeStatus", rejection == null ? "native NPC route running" : rejection);
                check("SimplePlanes accepts mounted NPC route", planeStarted && VehicleOperations.active(craft) && craft.getControllingPassenger() == npc);
                stage = 9; age = 0;
            } else if (stage == 9) {
                highest = Math.max(highest, craft.getY());
                if (age >= 240) {
                    RESULTS.put("planeDistance", craft.position().distanceTo(start)); RESULTS.put("planeAltitudeGain", highest - start.y);
                    RESULTS.put("planeRouteActiveAtEnd", VehicleOperations.active(craft));
                    RESULTS.put("planeFinalY", craft.getY()); RESULTS.put("planeFinalPitch", craft.getXRot());
                    RESULTS.put("planeFinalThrottle", invoke(craft, "getThrottle", new Class<?>[0]));
                    RESULTS.put("planeRemainingCoal", planeFuel.getStackInSlot(0).getCount());
                    RESULTS.put("planeNativeBurnTime", planeEngine.getClass().getField("burnTime").get(planeEngine));
                    check("SimplePlanes native engine consumes actual cold-start fuel", planeStarted && planeFuel.getStackInSlot(0).getCount() < 16
                        && ((Number) planeEngine.getClass().getField("burnTime").get(planeEngine)).intValue() > 0);
                    check("SimplePlanes moves with NPC native input", planeStarted && craft.position().distanceTo(start) > 15 && npc.getVehicle() == craft);
                    check("SimplePlanes NPC takes off", planeStarted && highest - start.y > 2 && npc.getVehicle() == craft);
                    VehicleOperations.stop(player.server, craft);
                    check("SimplePlanes stop neutralizes native throttle", !VehicleOperations.active(craft) && ((Number) invoke(craft, "getThrottle", new Class<?>[0])).intValue() == 0);
                    finish();
                }
            }
        } catch (Throwable failure) { failed(failure); }
    }

    private static void preparePlane() throws Exception {
        cleanup();
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("simpleplanes:plane"));
        if (type == null) { RESULTS.put("planeStatus", "not installed; untested"); finish(); return; }
        craft = type.create(level);
        if (craft == null) throw new IllegalStateException("Plane registry create failed");
        craft.setPos(origin.getX() + 26.5, origin.getY() + 1.1, origin.getZ() + 0.5); craft.setYRot(0);
        VehicleOperations.setOwner(craft, player.getUUID()); level.addFreshEntity(craft);
        ItemStack previous = player.getMainHandItem().copy();
        GameType mode = player.gameMode.getGameModeForPlayer(); player.setGameMode(GameType.CREATIVE);
        try {
            for (String upgrade : new String[]{"furnace_engine", "propeller"}) {
                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("simpleplanes", upgrade));
                if (item == null || item == Items.AIR) throw new IllegalStateException("Missing plane upgrade " + upgrade);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
                craft.interact(player, InteractionHand.MAIN_HAND);
            }
            planeEngine = craft.getClass().getField("engineUpgrade").get(craft);
            planeFuel = (ItemStackHandler) planeEngine.getClass().getField("itemStackHandler").get(planeEngine);
            planeFuel.setStackInSlot(0, ItemStack.EMPTY);
        } finally { player.setItemInHand(InteractionHand.MAIN_HAND, previous); player.setGameMode(mode); }
        if (player.getVehicle() == craft) player.stopRiding();
        npc = worker(craft.position().add(3, 1, 0)); stage = 8; age = 0;
    }

    private static LivingEntity worker(Vec3 position) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("frontier_industry:worker"));
        Entity created = type == null ? null : type.create(level);
        if (!(created instanceof LivingEntity worker)) throw new IllegalStateException("Worker registry create failed");
        worker.setPos(position); worker.getPersistentData().putBoolean("frontierIndustryWorker", true);
        worker.getPersistentData().putString("frontierIndustryOwner", player.getUUID().toString());
        worker.getPersistentData().putString("frontierIndustryRole", "production");
        level.addFreshEntity(worker); return worker;
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        return object.getClass().getMethod(name, types).invoke(object, values);
    }
    private static String attempt(String name, Vec3 target) {
        Map<String, Object> state = Map.of("playerToCraft", player.position().distanceTo(craft.position()),
            "npcToCraft", npc.position().distanceTo(craft.position()), "playerPosition", player.position().toString(),
            "craftPosition", craft.position().toString(), "npcPosition", npc.position().toString(),
            "npcVehicle", npc.getVehicle() == null ? "none" : npc.getVehicle().getUUID().toString(),
            "npcAlreadyDriver", craft.getControllingPassenger() == npc, "playerFlying", player.getAbilities().flying);
        RESULTS.put(name + " state", state);
        String rejection = VehicleOperations.start(player, npc, craft, target);
        String status = rejection == null ? "accepted" : rejection;
        RESULTS.put(name + " rejection", status);
        System.out.println("INDUSTRY_VEHICLE_ORDER " + name + ": " + status + " " + state);
        return rejection;
    }
    private static void checkCrewCommandWhileSeated() {
        Vec3 position = player.position(), motion = player.getDeltaMovement();
        float yaw = player.getYRot(), pitch = player.getXRot(), fallDistance = player.fallDistance;
        try {
            player.teleportTo(level, craft.getX() + 4, craft.getY() + 1, craft.getZ(), yaw, pitch);
            player.getPersistentData().remove("frontierIndustrySelectedDriver");
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "industryvehicle crew " + npc.getStringUUID());
            check("crew command selects owned NPC already driving the same craft", player.getPersistentData().hasUUID("frontierIndustrySelectedDriver")
                && player.getPersistentData().getUUID("frontierIndustrySelectedDriver").equals(npc.getUUID()));
        } finally {
            player.teleportTo(level, position.x, position.y, position.z, yaw, pitch); player.setDeltaMovement(motion); player.fallDistance = fallDistance;
        }
    }
    private static void pinArenaChunks() {
        for (int x = (origin.getX() - 16) >> 4; x <= (origin.getX() + 72) >> 4; x++)
            for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 200) >> 4; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!level.getForcedChunks().contains(pos.toLong())) {
                    level.setChunkForced(x, z, true); FORCED.add(pos);
                }
                level.getChunk(x, z);
            }
        RESULTS.put("fixtureForcedChunkCount", FORCED.size());
    }
    private static void finish() {
        complete = true;
        try { cleanup(); }
        finally {
            try { if (level != null) for (ChunkPos pos : FORCED) level.setChunkForced(pos.x, pos.z, false); }
            finally { FORCED.clear(); MinecraftForge.EVENT_BUS.unregister(CLEANUP_ON_STOP); }
        }
        RESULTS.put("fixtureForcedChunksReleased", FORCED.isEmpty());
    }
    private static final class CleanupOnStop {
        @SubscribeEvent public void stopping(ServerStoppingEvent event) {
            if (level != null && level.getServer() == event.getServer()) finish();
        }
    }
    private static void check(String name, boolean ok) {
        RESULTS.put(name, ok); System.out.println("INDUSTRY_VEHICLE_CHECK " + (ok ? "PASS " : "FAIL ") + name);
    }
    private static void failed(Throwable failure) {
        failure.printStackTrace(); RESULTS.put("exception", failure.toString()); check("vehicle runtime completed without exception", false);
        finish();
    }
    private static void cleanup() {
        if (craft != null) { VehicleOperations.stop(player.server, craft); craft.discard(); craft = null; }
        if (npc != null) { npc.discard(); npc = null; }
    }
    public static boolean done() { return complete; }
    public static Map<String, Object> results() { return new LinkedHashMap<>(RESULTS); }
}
