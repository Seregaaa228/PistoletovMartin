package dev.frontier.aviation.control.server;

import dev.frontier.aviation.control.ReleaseReason;
import dev.frontier.aviation.control.diagnostics.FlightDiagnostics;
import dev.frontier.aviation.control.net.FlightNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Lifecycle checks revoke authority only; no model/physics stepping from Forge tick events. */
public final class FlightControlEvents {
    private static boolean registered;
    private FlightControlEvents() {}
    public static void register() { if (!registered) { registered = true; MinecraftForge.EVENT_BUS.register(FlightControlEvents.class); } }
    @SubscribeEvent public static void stopped(ServerStoppingEvent event) {
        FlightControlService.get(event.getServer()).close(); FlightNetwork.clear();
        if (FlightDiagnostics.ENABLED) FlightDiagnostics.dumpServer();
    }
    @SubscribeEvent public static void mount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || event.isCanceled()) return;
        // Dismount is delivered before the passenger list changes; revoke explicitly now.
        if (!event.isMounting()) FlightControlService.get(event.getLevel().getServer())
            .releaseController(event.getEntityMounting().getUUID(), ReleaseReason.DISMOUNT);
    }
    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;
        var service = FlightControlService.get(event.getEntity().getServer());
        service.releaseController(event.getEntity().getUUID(), ReleaseReason.DEATH);
        service.removeVehicle(event.getEntity().getUUID(), ReleaseReason.DEATH);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        var service = FlightControlService.get(event.getLevel().getServer());
        service.releaseController(event.getEntity().getUUID(), ReleaseReason.DIMENSION_CHANGE);
        service.removeVehicle(event.getEntity().getUUID(), ReleaseReason.VEHICLE_REMOVED);
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) FlightControlService.get(player.server)
            .releaseController(player.getUUID(), ReleaseReason.DIMENSION_CHANGE);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FlightControlService.get(player.server).releaseController(player.getUUID(), ReleaseReason.DISCONNECT);
            FlightNetwork.clear(player);
        }
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var service = FlightControlService.get(player.server);
            if (service.manages(event.getTarget())) service.snapshot(event.getTarget()).ifPresent(snapshot -> FlightNetwork.sendSnapshot(player, snapshot));
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) FlightControlService.get(event.getServer()).validateLeases();
    }
}
