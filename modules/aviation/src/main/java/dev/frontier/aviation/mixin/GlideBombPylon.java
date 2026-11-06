package dev.frontier.aviation.mixin;

import dev.frontier.aviation.ordnance.StoreLoadout;
import dev.frontier.aviation.ordnance.StoreStations;
import dev.frontier.aviation.weapons.GlideBombRackView;
import dev.frontier.aviation.weapons.GuidedOrdnance;
import dev.frontier.aviation.weapons.KabEntity;
import dev.frontier.aviation.weapons.PylonOrdnance;
import dev.frontier.aviation.weapons.WeaponLoadout;
import dev.sbw.deepstrike.game.FlightUnits;
import dev.sbw.deepstrike.game.RoutePoint;
import dev.sbw.deepstrike.vehicle.Su24mEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DeepStrike pylons for the tablet strike panel (GlideBombTabletPanel), on every
 * aircraft that uses the tablet. A one-point tablet target is dropped onto the
 * terrain, and either pylon may take a КАБ-500 chosen on the tablet (see
 * PylonOrdnance): it is held as an aerial-bomb stack in the native rack and spawned
 * here as our KabEntity. Storm Shadow always uses DeepStrike's own load/launch
 * code; the native order/timer/speed/crew launch gate is reused for both.
 */
@Mixin(value = Su24mEntity.class, remap = false)
public abstract class GlideBombPylon implements GlideBombRackView {
    @Shadow @Final private static EntityDataAccessor<Integer> RACKS;
    @Shadow @Final private ItemStack[] stores;
    @Shadow @Final private Su24mEntity.Order[] orders;
    @Shadow @Final private String[] messages;
    @Shadow @Final private UUID[] lastFlight;
    @Shadow @Final private List<RoutePoint>[] routes;
    @Shadow private void syncRacks() { throw new AssertionError(); }

    @Unique
    private static boolean aviation$isGlideBomb(ItemStack stack) {
        return PylonOrdnance.kabIndex(stack) >= 0;
    }

    /** Surface height (first free block) under a map point; the tablet only picks X/Z. */
    @Unique
    private static double aviation$groundY(Level level, double x, double z, double fallback) {
        // Level.getHeight answers the build floor for a chunk that is not loaded, and a far
        // target's chunk usually is not; the tablet's fallback Y is the aircraft's altitude
        // (clients have no NO_LEAVES heightmap), so the missile burst in the air. Ask the
        // chunk itself, loading it from disk on the server.
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y = level.getChunk(bx >> 4, bz >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
        return y > level.getMinBuildHeight() ? y : fallback;
    }

    // A piloted aircraft is often moved by position, so its server delta movement can read
    // ~0 km/h in flight and the native launch gate (>= 40 km/h) then never opens.
    // Measure the distance actually flown between tick starts, client moves included.
    @Unique private Vec3 aviation$lastPos;
    @Unique private Vec3 aviation$flown = Vec3.ZERO;

    @Inject(method = "m_8119_", at = @At("HEAD"))
    private void aviation$measureFlight(CallbackInfo ci) {
        Vec3 now = ((Entity) (Object) this).position();
        aviation$flown = aviation$lastPos == null ? Vec3.ZERO : now.subtract(aviation$lastPos);
        aviation$lastPos = now;
    }

    @Inject(method = "speedKmh", at = @At("HEAD"), cancellable = true)
    private void aviation$trueSpeed(CallbackInfoReturnable<Double> result) {
        // On the ground keep DeepStrike's own reading (parked() / loading rely on it).
        Entity plane = (Entity) (Object) this;
        if (plane.onGround()) return;
        result.setReturnValue(FlightUnits.kilometresPerHour(aviation$velocity().length()));
    }

    @Override
    public Vec3 aviation$velocity() {
        Vec3 own = KabEntity.carrierVelocity((Entity) (Object) this);
        // Teleports are not flight.
        return aviation$flown.lengthSqr() > own.lengthSqr() && aviation$flown.lengthSqr() < 400 ? aviation$flown : own;
    }

    // Bits 0-1 stay DeepStrike's "pylon n loaded"; bits 2-3 add "pylon n holds a КАБ" and
    // bits 4-5 / 6-7 its class (PylonOrdnance.KABS) so clients can name and draw the store.
    // loaded(n) only tests bit n.
    @Inject(method = "syncRacks", at = @At("TAIL"))
    private void aviation$syncKabMask(CallbackInfo ci) {
        var data = ((Entity) (Object) this).getEntityData();
        int kab = 0;
        for (int i = 0; i < stores.length; i++) {
            int variant = PylonOrdnance.kabIndex(stores[i]);
            if (variant >= 0) kab |= 1 << i | variant << (2 + 2 * i);
        }
        data.set(RACKS, (data.get(RACKS) & 0b11) | kab << 2);
    }

    @Override
    public int aviation$kabVariant(int slot) {
        int racks = ((Entity) (Object) this).getEntityData().get(RACKS);
        if (slot < 0 || slot > 1 || (racks >> (2 + slot) & 1) == 0) return -1;
        return racks >> (4 + 2 * slot) & 0b11;
    }

    @Override
    public int aviation$kabMask() {
        return (((Entity) (Object) this).getEntityData().get(RACKS) >> 2) & 0b11;
    }

    // The tablet sends one target point; for a strike it must be on the ground.
    // Storm Shadow detonates at its final waypoint and the КАБ steers to it, so the
    // point the pilot picked on the map is dropped onto the terrain here.
    @ModifyVariable(method = "prepare", at = @At("HEAD"), argsOnly = true)
    private List<RoutePoint> aviation$groundTarget(List<RoutePoint> route) {
        Su24mEntity plane = (Su24mEntity) (Object) this;
        if (route == null || route.size() != 1) return route;
        RoutePoint point = route.get(0);
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.z())) return route;
        double y = aviation$groundY(plane.level(), point.x(), point.z(), point.y());
        return List.of(new RoutePoint(point.x(), y, point.z(), point.speed()));
    }

    @Inject(method = "loadStore", at = @At("HEAD"), cancellable = true)
    private void aviation$loadGlideBomb(ServerPlayer p, int slot, CallbackInfoReturnable<Boolean> result) {
        Su24mEntity plane = (Su24mEntity) (Object) this;
        // Always consumed, even when falling through to native Storm Shadow loading.
        int variant = PylonOrdnance.kabIndex(PylonOrdnance.consumePendingLoadType());
        if (variant < 0) return;
        result.cancel();
        if (slot < 0 || slot >= stores.length || !plane.canOperate(p, false) || !plane.parked()
                || orders[slot] != null || !stores[slot].isEmpty()) {
            result.setReturnValue(false);
            return;
        }
        // A J-10 has one (belly) rack, and every owned jet shares its stations with missiles.
        if (!StoreLoadout.canLoadStormShadow(plane, slot)) {
            result.setReturnValue(false);
            return;
        }
        PylonOrdnance.Kab kab = PylonOrdnance.KABS.get(variant);
        var inventory = p.getInventory();
        int have = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).getItem() == kab.item().get()) have += inventory.getItem(i).getCount();
        if (have < kab.count()) {
            result.setReturnValue(false);
            return;
        }
        int needed = kab.count();
        for (int i = 0; i < inventory.getContainerSize() && needed > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.getItem() != kab.item().get()) continue;
            if (stores[slot].isEmpty()) stores[slot] = stack.copyWithCount(kab.count());
            int taken = Math.min(needed, stack.getCount());
            stack.shrink(taken);
            needed -= taken;
        }
        syncRacks();
        messages[slot] = routes[slot].isEmpty() ? "loaded" : "ready";
        result.setReturnValue(true);
    }

    // DeepStrike launches from its Su-24M pylons (+-2.1, 2.2, 0.2). An owned jet launches
    // from where its store hangs: the station of that rack (the J-10's belly) and the
    // hung model's origin, so the released store leaves from where it was drawn.
    @Inject(method = "pylonPosition", at = @At("HEAD"), cancellable = true)
    private void aviation$stationPosition(int slot, CallbackInfoReturnable<Vec3> result) {
        Su24mEntity plane = (Su24mEntity) (Object) this;
        var layout = StoreLoadout.layout(plane).orElse(null);
        int station = layout == null ? -1 : layout.rackStation(slot);
        if (station < 0 || slot >= stores.length) return;
        int model = StoreStations.model(StoreStations.RACK, PylonOrdnance.kabIndex(stores[slot]));
        double[] p = StoreStations.launch(layout.stations().get(station), model);
        var point = plane.getTransformFromString("Vehicle").transformPosition(new Vector3d(p[0], p[1], p[2]));
        result.setReturnValue(new Vec3(point.x, point.y, point.z));
    }

    @Inject(method = "unloadStore", at = @At("HEAD"), cancellable = true)
    private void aviation$unloadGlideBomb(ServerPlayer p, int slot, CallbackInfoReturnable<Boolean> result) {
        Su24mEntity plane = (Su24mEntity) (Object) this;
        if (slot < 0 || slot >= stores.length || !aviation$isGlideBomb(stores[slot])) return;
        result.cancel();
        if (!plane.canOperate(p, false) || !plane.parked() || orders[slot] != null) {
            result.setReturnValue(false);
            return;
        }
        ItemStack copy = stores[slot].copy();
        if (!p.getInventory().add(copy) && !copy.isEmpty()) p.drop(copy, false);
        stores[slot] = ItemStack.EMPTY;
        syncRacks();
        messages[slot] = "empty";
        result.setReturnValue(true);
    }

    @Inject(method = "launch", at = @At("HEAD"), cancellable = true)
    private void aviation$launchGlideBomb(ServerLevel server, int slot, Su24mEntity.Order order, CallbackInfo ci) {
        if (slot < 0 || slot >= stores.length || !aviation$isGlideBomb(stores[slot])) return;
        ci.cancel();
        Su24mEntity plane = (Su24mEntity) (Object) this;
        List<RoutePoint> route = routes[slot];
        Vec3 from = plane.pylonPosition(slot);
        // Last point is the target, as for Storm Shadow (the native route is append-only).
        RoutePoint point = route.isEmpty() ? null : route.get(route.size() - 1);
        Vec3 target = point == null ? null
                : new Vec3(point.x(), aviation$groundY(server, point.x(), point.z(), point.y()), point.z());
        if (target == null || from.distanceTo(target) > WeaponLoadout.GLIDE_BOMB.maximumRange()) {
            orders[slot] = null;
            messages[slot] = "range";
            return;
        }
        KabEntity bomb = GuidedOrdnance.KAB_500.get().create(server);
        if (bomb != null) bomb.setVariant(PylonOrdnance.kabIndex(stores[slot]));
        if (bomb == null) {
            orders[slot] = null;
            messages[slot] = "rejected";
            return;
        }
        Vec3 direction = plane.noseDirection();
        bomb.setPos(from.x, from.y, from.z);
        bomb.setDeltaMovement(aviation$velocity());
        bomb.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        bomb.setXRot((float) -Math.toDegrees(Math.asin(direction.y)));
        bomb.setOwner(server.getServer().getPlayerList().getPlayer(order.operator()));
        bomb.setGuideType(1);
        bomb.setTargetUUID("none");
        bomb.setTargetPos(target);
        if (server.addFreshEntity(bomb)) {
            stores[slot] = ItemStack.EMPTY;
            lastFlight[slot] = bomb.getUUID();
            orders[slot] = null;
            messages[slot] = "launched";
            syncRacks();
        } else {
            orders[slot] = null;
            messages[slot] = "rejected";
        }
    }
}
