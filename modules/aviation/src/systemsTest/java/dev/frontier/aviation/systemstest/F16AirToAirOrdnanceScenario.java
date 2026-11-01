package dev.frontier.aviation.systemstest;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonObject;
import dev.frontier.aviation.AircraftProfile;
import dev.frontier.aviation.api.AircraftArmament;
import dev.frontier.aviation.api.AircraftController;
import dev.frontier.aviation.control.ControlLease;
import dev.frontier.aviation.control.PilotCommand;
import dev.frontier.aviation.ordnance.F16StormLoader;
import dev.sbw.deepstrike.game.MissileData;
import dev.sbw.deepstrike.game.MissileKind;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Real fresh-world F-16 check for entity-seeking AAMs and magazine limits. */
final class F16AirToAirOrdnanceScenario {
    private static final String[] LARGE_SLOTS = {"aamissile"};
    private static final String[] LIGHT_SLOTS = {"light_aamissile"};

    private final ServerPlayer pilot;
    private final VehicleEntity plane;
    private final ArmorStand target;
    private final ControlLease lease;
    private final Vec3 targetStart;
    private final JsonObject result = new JsonObject();
    private int ticks;
    private int stage;
    private String largeSlot, largeGun, lightSlot, lightGun;
    private boolean largeQueued, lightQueued;
    private int largeBefore, largeAfter, lightBefore, lightAfter;
    private int largeSpawnBefore, lightSpawnBefore;

    static F16AirToAirOrdnanceScenario start(ServerPlayer pilot) {
        return new F16AirToAirOrdnanceScenario(pilot);
    }

    private F16AirToAirOrdnanceScenario(ServerPlayer pilot) {
        this.pilot = pilot;
        if (pilot.isPassenger()) throw new IllegalStateException("air-to-air fixture needs a free pilot");
        var level = pilot.serverLevel();
        plane = AircraftProfile.F16.entity.get().create(level);
        target = EntityType.ARMOR_STAND.create(level);
        if (plane == null || target == null) throw new IllegalStateException("F-16 target fixture creation failed");
        double y = Math.max(128, level.getMinBuildHeight() + 128);
        plane.moveTo(pilot.getX(), y, pilot.getZ(), 0, 0);
        plane.setNoGravity(true);
        plane.setInvulnerable(true);
        plane.setEnergy(plane.getMaxEnergy());
        target.moveTo(plane.getX(), y + 2, plane.getZ() + 80, 0, 0);
        targetStart = target.position();
        target.setNoGravity(true);
        target.setInvulnerable(true);
        if (!level.addFreshEntity(plane) || !level.addFreshEntity(target)
                || !pilot.startRiding(plane, true)) throw new IllegalStateException("F-16 target setup failed");
        lease = AircraftController.acquire(pilot, plane)
            .orElseThrow(() -> new IllegalStateException("F-16 lease acquisition failed"));
        result.addProperty("vehicle", "frontier_aviation:f16");
        result.addProperty("pilot", pilot.getUUID().toString());
        result.addProperty("target", target.getUUID().toString());
        result.addProperty("targetInitialDistance", plane.position().distanceTo(target.position()));
        result.addProperty("pass", false);
    }

    boolean tick() {
        ticks++;
        if (!AircraftController.owns(pilot, plane, lease)) return fail("pilot lease lost");
        // The fixture owns this disposable flight; keep its server lease alive
        // even while the test client is still synchronizing the newly mounted plane.
        AircraftController.submit(pilot, lease, PilotCommand.neutral(1_000_000L + ticks));
        if (plane.isRemoved()) return fail("fixture aircraft removed");
        if (!target.isAlive() && stage < 2) return fail("target removed before both launches");
        if (target.isAlive()) {
            target.setDeltaMovement(new Vec3(0.18, 0, 0));
            target.setPos(target.getX() + 0.18, target.getY(), target.getZ());
        }
        if (ticks == 10) {
            if (!checkInventoryLimit()) return fail("native inventory limit or stack remainder failed");
            var large = find(LARGE_SLOTS);
            var light = find(LIGHT_SLOTS);
            if (large == null || light == null) {
                result.addProperty("largeSlot", large == null ? "missing" : large.slot);
                result.addProperty("lightSlot", light == null ? "missing" : light.slot);
                return fail("expected air-to-air slots are not registered");
            }
            largeSlot = large.slot; largeGun = large.state.nativeGun();
            lightSlot = light.slot; lightGun = light.state.nativeGun();
            if (!AircraftArmament.select(pilot, plane, lease, largeSlot))
                return fail("large AAM selection failed");
            if (!largeGun.equals(plane.getGunName(0))) return fail("large AAM is not active");
            largeBefore = seed(largeGun, 2);
            largeSpawnBefore = ProjectileObservations.count("superbwarfare:ru_9m100_missile");
            lightSpawnBefore = ProjectileObservations.count("superbwarfare:ru_9m336_missile");
            if (!AircraftArmament.select(pilot, plane, lease, largeSlot))
                return fail("large AAM re-selection failed before launch");
            largeQueued = AircraftArmament.fireTracked(pilot, plane, lease, largeSlot, 1, target)
                == AircraftArmament.Result.QUEUED;
            result.addProperty("largeQueued", largeQueued);
            stage = 1;
        } else if (stage == 1 && ticks >= 35) {
            largeAfter = roundsForGun(largeGun, largeSlot);
            if (!largeQueued || largeAfter >= largeBefore) return fail("large AAM did not consume one round");
            if (!AircraftArmament.select(pilot, plane, lease, lightSlot))
                return fail("light air-to-air selection failed");
            if (!lightGun.equals(plane.getGunName(0))) return fail("light AAM is not active");
            lightBefore = seed(lightGun, 2);
            if (!AircraftArmament.select(pilot, plane, lease, lightSlot))
                return fail("light AAM re-selection failed before launch");
            lightQueued = AircraftArmament.fireTracked(pilot, plane, lease, lightSlot, 1, target)
                == AircraftArmament.Result.QUEUED;
            result.addProperty("lightQueued", lightQueued);
            stage = 2;
        } else if (stage == 2 && ticks >= 70) {
            lightAfter = roundsForGun(lightGun, lightSlot);
            return finish();
        }
        return false;
    }

    private record Candidate(String slot, AircraftArmament.State state) {}

    private boolean checkInventoryLimit() {
        for (String gun : new String[]{"Missile", "MediumAGMissile", "AAMissile", "LightAAMissile"}) {
            var data = plane.getGunData(gun);
            if (data == null) return false;
            data.ammo.set(0);
        }
        var inventory = plane.getInventory();
        var lightItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare", "medium_anti_air_missile"));
        var groundItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("superbwarfare", "large_anti_ground_missile"));
        if (lightItem == null || groundItem == null || inventory.getSlots() < 3) return false;
        ItemStack light = new ItemStack(lightItem, 64);
        result.addProperty("initialSlot0", inventory.getStackInSlot(0).toString());
        result.addProperty("lightItemMax", light.getMaxStackSize());
        int simulated = inventory.insertItem(0, light, true).getCount();
        boolean untouched = inventory.getStackInSlot(0).isEmpty() && light.getCount() == 64;
        int actual = inventory.insertItem(0, light, false).getCount();
        int slotMax = new SlotItemHandler(inventory, 0, 0, 0).getMaxStackSize(light);
        int second = inventory.insertItem(1, light, false).getCount();
        boolean full = inventory.insertItem(2, new ItemStack(lightItem, 1), false).getCount() == 1;
        boolean retained = inventory.getStackInSlot(0).getCount() == 4 && inventory.getStackInSlot(1).getCount() == 2;
        inventory.extractItem(0, 64, false);
        inventory.extractItem(1, 64, false);
        ItemStack ground = new ItemStack(groundItem, 64);
        ItemStack groundLeft = ground;
        int nextSlot = 0;
        while (nextSlot < inventory.getSlots() - 1 && groundLeft.getCount() > 60)
            groundLeft = inventory.insertItem(nextSlot++, groundLeft, false);
        int groundRemainder = groundLeft.getCount();
        int extraGround = inventory.insertItem(nextSlot, new ItemStack(groundItem, 1), true).getCount();
        int tipRemainder = inventory.insertItem(nextSlot, light, false).getCount();
        boolean compatible = groundRemainder == 60 && extraGround == 1 && tipRemainder == 62;
        for (int i = 0; i <= nextSlot; i++) inventory.extractItem(i, 64, false);
        // Real SBW menu quick-move, not just ItemStackHandler.insertItem.
        pilot.getInventory().setItem(9, new ItemStack(lightItem, 4));
        var menu = plane.createMenu(1, pilot.getInventory(), pilot);
        boolean quickMove = false;
        if (menu != null) {
            menu.quickMoveStack(pilot, 0);
            int afterFirst = 0;
            for (int i = 0; i < inventory.getSlots(); i++) afterFirst += inventory.getStackInSlot(i).getCount();
            pilot.getInventory().setItem(9, new ItemStack(lightItem, 4));
            menu.quickMoveStack(pilot, 0);
            int loaded = 0;
            for (int i = 0; i < inventory.getSlots(); i++) loaded += inventory.getStackInSlot(i).getCount();
            result.addProperty("quickMoveFirst", afterFirst);
            result.addProperty("quickMoveLoaded", loaded);
            result.addProperty("quickMoveRemainder", pilot.getInventory().getItem(9).getCount());
            quickMove = afterFirst == 4 && loaded == 6 && pilot.getInventory().getItem(9).getCount() == 2;
        }
        pilot.getInventory().setItem(9, ItemStack.EMPTY);
        for (int i = 0; i < inventory.getSlots(); i++) inventory.extractItem(i, 64, false);
        // Emulate an older saved container with eight rounds. Loading must not
        // truncate it; extraction remains available while new insertion waits.
        inventory.setStackInSlot(0, new ItemStack(lightItem, 4));
        inventory.setStackInSlot(1, new ItemStack(lightItem, 4));
        var saved = inventory.serializeNBT();
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        inventory.setStackInSlot(1, ItemStack.EMPTY);
        inventory.deserializeNBT(saved);
        boolean legacy = inventory.getStackInSlot(0).getCount() == 4 && inventory.getStackInSlot(1).getCount() == 4
            && inventory.insertItem(2, new ItemStack(lightItem, 1), false).getCount() == 1;
        legacy &= inventory.extractItem(0, 3, false).getCount() == 3;
        legacy &= inventory.insertItem(2, new ItemStack(lightItem, 1), false).isEmpty();
        for (int i = 0; i < inventory.getSlots(); i++) inventory.extractItem(i, 64, false);
        boolean storm = checkStormTransfer();
        boolean pass = simulated == 60 && untouched && actual == 60 && slotMax == 4 && second == 62 && full && retained && compatible && quickMove && legacy && storm;
        result.addProperty("inventoryLimit", pass);
        result.addProperty("simulatedRemainder", simulated);
        result.addProperty("actualRemainder", actual);
        result.addProperty("slotMaximum", slotMax);
        result.addProperty("secondRemainder", second);
        result.addProperty("fullRejected", full);
        result.addProperty("retained", retained);
        result.addProperty("groundItemMax", ground.getMaxStackSize());
        result.addProperty("groundRemainder", groundRemainder);
        result.addProperty("extraGroundRemainder", extraGround);
        result.addProperty("mixedCompatible", compatible);
        result.addProperty("quickMove", quickMove);
        result.addProperty("legacySerializedOverflowPreserved", legacy);
        result.addProperty("mixedLoadRemainder", tipRemainder);
        return pass;
    }

    private boolean checkStormTransfer() {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("deepstrike", "storm_shadow"));
        if (item == null || !((Object) plane instanceof F16StormLoader loader)) return false;
        ItemStack stack = new ItemStack(item);
        var data = new MissileData(MissileKind.STORM_SHADOW, MissileData.WARHEAD_HE,
            MissileData.ENGINE_TURBOJET, List.of(), MissileData.CAMO_DEFAULT, 1000, 1000);
        data.writeToItemStack(stack);
        if (!data.isFueled() || !data.supportsWarhead()) return false;
        var inventory = plane.getInventory();
        if (!inventory.insertItem(0, stack, false).isEmpty()) return false;
        plane.setDeltaMovement(Vec3.ZERO);
        plane.setOnGround(true);
        loader.aviation$loadInventoryStores();
        var su = (Su24mEntity) plane;
        boolean mounted = su.loaded(0) && inventory.getStackInSlot(0).isEmpty();
        boolean unloaded = mounted && su.unloadStore(pilot, 0);
        for (int i = 0; i < pilot.getInventory().getContainerSize(); i++)
            if (pilot.getInventory().getItem(i).is(item)) pilot.getInventory().setItem(i, ItemStack.EMPTY);
        plane.setOnGround(false);
        result.addProperty("stormInventoryTransfer", mounted && unloaded);
        return mounted && unloaded;
    }

    private Candidate find(String[] slots) {
        for (String slot : slots) {
            var state = AircraftArmament.inspect(pilot, plane, lease, slot);
            if (state.isPresent() && AircraftArmament.select(pilot, plane, lease, slot))
                return new Candidate(slot, state.get());
        }
        return null;
    }

    private int seed(String gun, int rounds) {
        var data = plane.getGunData(gun);
        if (data == null) throw new IllegalStateException("native gun missing: " + gun);
        data.ammo.set(rounds);
        return rounds;
    }

    private int roundsForGun(String gun, String slot) {
        if (!(gun.equals(largeGun) && slot.equals(largeSlot))
                && !(gun.equals(lightGun) && slot.equals(lightSlot)))
            throw new IllegalArgumentException("unknown F-16 air-to-air gun " + gun);
        return roundsForSlot(slot);
    }

    private int roundsForSlot(String slot) {
        return AircraftArmament.inspect(pilot, plane, lease, slot)
            .map(AircraftArmament.State::rounds).orElse(-1);
    }

    private boolean finish() {
        result.addProperty("largeSlot", largeSlot);
        result.addProperty("largeGun", largeGun);
        result.addProperty("lightSlot", lightSlot);
        result.addProperty("lightGun", lightGun);
        result.addProperty("largeRoundsBefore", largeBefore);
        result.addProperty("largeRoundsAfter", largeAfter);
        result.addProperty("lightRoundsBefore", lightBefore);
        result.addProperty("lightRoundsAfter", lightAfter);
        result.addProperty("largeQueued", largeQueued);
        result.addProperty("lightQueued", lightQueued);
        result.addProperty("targetAliveAtObservation", target.isAlive());
        int largeSpawns = ProjectileObservations.count("superbwarfare:ru_9m100_missile") - largeSpawnBefore;
        int lightSpawns = ProjectileObservations.count("superbwarfare:ru_9m336_missile") - lightSpawnBefore;
        result.addProperty("nativeProjectileSpawns", largeSpawns + lightSpawns);
        result.addProperty("largeNativeProjectileSpawns", largeSpawns);
        result.addProperty("lightNativeProjectileSpawns", lightSpawns);
        boolean targetMoved = target.position().distanceToSqr(targetStart) > 1;
        result.addProperty("targetMoved", targetMoved);
        result.addProperty("pass", largeQueued && lightQueued
            && largeAfter == largeBefore - 1 && lightAfter == lightBefore - 1
            && largeSpawns == 1 && lightSpawns == 1 && targetMoved);
        cleanup();
        return true;
    }

    private boolean fail(String message) {
        result.addProperty("error", message);
        result.addProperty("pass", false);
        cleanup();
        return true;
    }

    private void cleanup() {
        AircraftController.release(pilot, lease);
        pilot.stopRiding();
        target.discard();
        plane.discard();
    }

    JsonObject result() { return result; }
}
