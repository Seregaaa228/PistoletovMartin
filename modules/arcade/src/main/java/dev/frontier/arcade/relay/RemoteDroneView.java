package dev.frontier.arcade.relay;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import dev.frontier.arcade.ArcadeVehicles;
import dev.frontier.arcade.control.ControlSessions;
import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import ru.lavafrai.uncomplicatedfpv.entity.AddonDroneEntity;

/** Vanilla chunk subscriptions follow the radio camera; the physical player never moves. */
@Mod.EventBusSubscriber(modid=ArcadeVehicles.ID)
public final class RemoteDroneView {
    private record Watch(ServerLevel level, AddonDroneEntity drone, ChunkPos body) {}
    private record AirTicket(ServerLevel level, ChunkPos pos) {}
    private static final Map<UUID,Watch> WATCH = new HashMap<>();
    private static final Set<RelayEntity> RELAYS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID,AirTicket> AIR = new HashMap<>();
    private static final Set<dev.frontier.arcade.geranium.GeraniumEntity> GLIDERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID,AirTicket> GLIDE_TICKETS = new HashMap<>();
    private static final Set<AddonDroneEntity> MAVICS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID,AirTicket> MAVIC_TICKETS = new HashMap<>();
    private static final TicketType<UUID> MAVIC_FLIGHT = TicketType.create("frontier_mavic_flight", UUID::compareTo, 40);
    private static final TicketType<UUID> GLIDE = TicketType.create("frontier_geranium_flight", UUID::compareTo, 40);
    private static final TicketType<UUID> BODY = TicketType.create("frontier_radio_body", UUID::compareTo, 40);
    private static final TicketType<UUID> AIRCRAFT = TicketType.create("frontier_relay_aircraft", UUID::compareTo, 40);
    private RemoteDroneView() {}
    public static long ownedCount(UUID owner){return RELAYS.stream().filter(r -> r.isAlive() && !r.isRemoved() && owner.equals(r.ownerId())).count();}
    public static long totalCount(){return RELAYS.stream().filter(r -> r.isAlive() && !r.isRemoved()).count();}
    public static Vec3 center(ServerPlayer player) {
        Watch watch=WATCH.get(player.getUUID());
        return watch!=null && watch.level==player.level() && watch.drone.isAlive() && !watch.drone.isRemoved()
            ? watch.drone.position() : player.position();
    }
    public static boolean active(ServerPlayer player) { return WATCH.containsKey(player.getUUID()); }
    public static AddonDroneEntity target(ServerPlayer player) {
        var held=player.getMainHandItem();var tag=held.getTag();
        if(!player.isAlive() || player.isSpectator() || tag==null || !tag.getBoolean("Using") || !tag.getBoolean("Linked")
            || !ControlSessions.isMonitor(held))return null;
        UUID id;try{id=UUID.fromString(tag.getString("LinkedDrone"));}catch(IllegalArgumentException invalid){return null;}
        Entity entity=player.serverLevel().getEntity(id);
        if(!(entity instanceof AddonDroneEntity drone) || !entity.isAlive()
            || !player.getStringUUID().equals(drone.getEntityData().get(DroneEntity.CONTROLLER))
            || entity.distanceToSqr(player)>1024d*1024d || drone.isSignalLost() || drone.getSignalQuality()<=0)return null;
        if(drone instanceof dev.frontier.arcade.geranium.GeraniumEntity g && !g.ownedBy(player))return null;
        if(drone instanceof RelayEntity relay && (!relay.ownedBy(player) || !relay.activeMonitor()))return null;
        if(!ControlSessions.authorizedInput(player,drone))return null;
        return drone;
    }
    @SubscribeEvent public static void playerTick(TickEvent.PlayerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !(event.player instanceof ServerPlayer player))return;
        AddonDroneEntity drone=target(player);Watch old=WATCH.get(player.getUUID());
        if(drone==null) {
            if(old!=null){WATCH.remove(player.getUUID());old.level.getChunkSource().removeRegionTicket(BODY,old.body,3,player.getUUID());player.serverLevel().getChunkSource().move(player);}
            return;
        }
        ChunkPos body=player.chunkPosition();
        if(old!=null && (old.level!=player.level() || !old.body.equals(body)))old.level.getChunkSource().removeRegionTicket(BODY,old.body,3,player.getUUID());
        WATCH.put(player.getUUID(),new Watch(player.serverLevel(),drone,body));
        player.serverLevel().getChunkSource().addRegionTicket(BODY,body,3,player.getUUID());
        player.serverLevel().getChunkSource().move(player);
        if(player.tickCount%10==0){var origin=drone.getOperatorPosition();if(origin==null)origin=player.getEyePosition();
            dev.frontier.arcade.geranium.GeraniumNet.sendRadioStatus(player,drone.getUUID(),RelayNetwork.linkInfo(drone,origin).relayHops());}
    }
    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if(event.getLevel().isClientSide)return;
        if(event.getEntity() instanceof RelayEntity relay)RELAYS.add(relay);
        if(event.getEntity() instanceof dev.frontier.arcade.geranium.GeraniumEntity g)GLIDERS.add(g);
        if(event.getEntity() instanceof AddonDroneEntity drone && isMavic(drone))MAVICS.add(drone);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if(event.getLevel().isClientSide)return;
        if(event.getEntity() instanceof RelayEntity relay){RELAYS.remove(relay);releaseAir(relay.getUUID());}
        if(event.getEntity() instanceof dev.frontier.arcade.geranium.GeraniumEntity g){GLIDERS.remove(g);releaseGlide(g.getUUID());}
        if(event.getEntity() instanceof AddonDroneEntity drone && isMavic(drone)){MAVICS.remove(drone);releaseMavic(drone.getUUID());}
    }
    private static boolean isMavic(AddonDroneEntity drone){return ControlSessions.isMavic(drone);}
    private static void releaseMavic(UUID id){AirTicket t=MAVIC_TICKETS.remove(id);if(t!=null)t.level.getChunkSource().removeRegionTicket(MAVIC_FLIGHT,t.pos,2,id);}
    private static void releaseGlide(UUID id){AirTicket t=GLIDE_TICKETS.remove(id);if(t!=null)t.level.getChunkSource().removeRegionTicket(GLIDE,t.pos,2,id);}
    private static void releaseAir(UUID id) {AirTicket t=AIR.remove(id);if(t!=null)t.level.getChunkSource().removeRegionTicket(AIRCRAFT,t.pos,3,id);}
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.START)return;
        // Closing the terminal releases the camera subscriptions, not an owned Mavic's current flight chunk.
        Map<UUID,Integer> mavicCounts=new HashMap<>();int mavicTotal=0;
        for(AddonDroneEntity mavic:MAVICS.stream().sorted(Comparator.comparing(Entity::getUUID)).toList()){
            if(mavic.isRemoved() || !(mavic.level() instanceof ServerLevel level)){MAVICS.remove(mavic);releaseMavic(mavic.getUUID());continue;}
            UUID ownerId=ControlSessions.ownerId(mavic);
            var owner=ownerId==null ? null : level.getPlayerByUUID(ownerId);
            if(owner==null || !owner.isAlive() || owner.isRemoved() || owner.level()!=level || !mavic.isAlive()
                || !ControlSessions.isPowered(mavic) || mavic.onGround()
                || mavicCounts.getOrDefault(ownerId,0)>=4 || mavicTotal>=16){releaseMavic(mavic.getUUID());continue;}
            ControlSessions.rememberNativeOwner(mavic);
            mavicCounts.merge(ownerId,1,Integer::sum);mavicTotal++;
            ChunkPos pos=mavic.chunkPosition();AirTicket old=MAVIC_TICKETS.get(mavic.getUUID());
            if(old!=null && (old.level!=level || !old.pos.equals(pos)))releaseMavic(mavic.getUUID());
            level.getChunkSource().addRegionTicket(MAVIC_FLIGHT,pos,2,mavic.getUUID());
            MAVIC_TICKETS.put(mavic.getUUID(),new AirTicket(level,pos));
        }
        Map<UUID,Integer> gliderCounts=new HashMap<>();int gliderTotal=0;
        for(var g:GLIDERS.stream().sorted(Comparator.comparing(Entity::getUUID)).toList()){
            if(g.isRemoved() || !(g.level() instanceof ServerLevel level)){GLIDERS.remove(g);releaseGlide(g.getUUID());continue;}
            if(!g.isAlive() || !g.isLaunched() || g.ownerId()==null || level.getPlayerByUUID(g.ownerId())==null
                || gliderCounts.getOrDefault(g.ownerId(),0)>=8 || gliderTotal>=32){releaseGlide(g.getUUID());continue;}
            gliderCounts.merge(g.ownerId(),1,Integer::sum);gliderTotal++;
            ChunkPos pos=g.chunkPosition();AirTicket old=GLIDE_TICKETS.get(g.getUUID());
            if(old!=null && (old.level!=level || !old.pos.equals(pos)))releaseGlide(g.getUUID());
            level.getChunkSource().addRegionTicket(GLIDE,pos,2,g.getUUID());GLIDE_TICKETS.put(g.getUUID(),new AirTicket(level,pos));
        }
        Map<UUID,Integer> counts=new HashMap<>();int total=0;
        for(RelayEntity relay:RELAYS.stream().sorted(Comparator.comparing(Entity::getUUID)).toList()) {
            if(relay.isRemoved() || !(relay.level() instanceof ServerLevel level)) {RELAYS.remove(relay);releaseAir(relay.getUUID());continue;}
            if(relay.ownerId()==null || level.getPlayerByUUID(relay.ownerId())==null || !relay.shouldKeepFlightLoaded()){releaseAir(relay.getUUID());continue;}
            if(counts.getOrDefault(relay.ownerId(),0)>=3 || total>=24){releaseAir(relay.getUUID());continue;}
            counts.merge(relay.ownerId(),1,Integer::sum);total++;
            ChunkPos pos=relay.chunkPosition();AirTicket old=AIR.get(relay.getUUID());
            if(old!=null && (old.level!=level || !old.pos.equals(pos)))releaseAir(relay.getUUID());
            level.getChunkSource().addRegionTicket(AIRCRAFT,pos,3,relay.getUUID());AIR.put(relay.getUUID(),new AirTicket(level,pos));
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        Watch old=WATCH.remove(e.getEntity().getUUID());if(old!=null)old.level.getChunkSource().removeRegionTicket(BODY,old.body,3,e.getEntity().getUUID());
        for(RelayEntity relay:List.copyOf(RELAYS))if(e.getEntity().getUUID().equals(relay.ownerId()))releaseAir(relay.getUUID());
        for(var g:List.copyOf(GLIDERS))if(e.getEntity().getUUID().equals(g.ownerId()))releaseGlide(g.getUUID());
        for(AddonDroneEntity mavic:List.copyOf(MAVICS))if(e.getEntity().getUUID().equals(ControlSessions.ownerId(mavic)))releaseMavic(mavic.getUUID());
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent e){
        for(UUID id:List.copyOf(MAVIC_TICKETS.keySet()))releaseMavic(id);
        MAVICS.clear();WATCH.clear();RELAYS.clear();AIR.clear();GLIDERS.clear();GLIDE_TICKETS.clear();
    }
}
