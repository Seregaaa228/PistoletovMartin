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
    private static final Set<dev.frontier.arcade.geranium.GeraniumEntity> GLIDERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<AddonDroneEntity> MAVICS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID,AirTicket> MAVIC_TICKETS = new HashMap<>();
    private static final TicketType<UUID> MAVIC_FLIGHT = TicketType.create("frontier_mavic_flight", UUID::compareTo, 40);
    private static final TicketType<UUID> BODY = TicketType.create("frontier_radio_body", UUID::compareTo, 40);
    /** Chunk radius a camera's current chunk set was sent with, and the one move() is applying now. */
    private static final Map<UUID,Integer> SENT_RADIUS = new HashMap<>(), APPLYING_RADIUS = new HashMap<>();
    /** Below this the remote view is not narrowed further, whatever the client reports. */
    private static final int MIN_REMOTE_RADIUS = 3;
    /**
     * Chunk radius around a launched strike aircraft (96 blocks): at 120-240 km/h the camera crosses a
     * chunk every few ticks and each crossing sends a whole row, so the row is kept short.
     */
    private static final int FLIGHT_RADIUS = 6;
    private RemoteDroneView() {}
    /** Radius of the chunks this player holds now: vanilla's, or the narrower one sent to its camera. */
    public static int sentRadius(ServerPlayer player, int view) { return Math.min(view, SENT_RADIUS.getOrDefault(player.getUUID(), view)); }
    /**
     * Radius to send now. Through a remote camera that is no more than the client renders: vanilla
     * 1.20.1 sends the server view distance, and every chunk beyond the client's render distance is
     * transferred, lit and stored without ever being drawn (a fast aircraft crosses chunks every tick or two).
     */
    public static int wantedRadius(ServerPlayer player, int view) {
        int camera = cameraRadius(player);
        int wanted = camera < 0 ? view : Math.min(view, camera);
        APPLYING_RADIUS.put(player.getUUID(), wanted < view ? wanted : -1); // -1: vanilla's own radius
        return wanted;
    }
    /**
     * While the player looks through a remote camera: its client's render distance, and no more than
     * FLIGHT_RADIUS once a strike aircraft is in the air; else -1 (vanilla radius).
     */
    public static int cameraRadius(ServerPlayer player) {
        Watch watch = WATCH.get(player.getUUID());
        if (watch == null) return -1;
        int radius = Math.max(MIN_REMOTE_RADIUS, dev.frontier.arcade.geranium.GeraniumNet.viewDistanceOr(player.getUUID(), Integer.MAX_VALUE));
        return watch.drone instanceof dev.frontier.arcade.geranium.GeraniumEntity g && g.isLaunched() ? Math.min(radius, FLIGHT_RADIUS) : radius;
    }
    /** End of a vanilla tracking update: what was applied is what the player now holds. */
    public static void radiusSent(ServerPlayer player, boolean tracked) {
        Integer applied = APPLYING_RADIUS.remove(player.getUUID());
        if (!tracked || applied != null && applied < 0) SENT_RADIUS.remove(player.getUUID());
        else if (applied != null) SENT_RADIUS.put(player.getUUID(), applied);
    }
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
            || entity.distanceToSqr(player)>viewLimit(drone)*viewLimit(drone) || drone.isSignalLost() || drone.getSignalQuality()<=0)return null;
        if(drone instanceof dev.frontier.arcade.geranium.GeraniumEntity g && !g.ownedBy(player))return null;
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
    }
    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if(event.getLevel().isClientSide)return;
        if(event.getEntity() instanceof dev.frontier.arcade.geranium.GeraniumEntity g)GLIDERS.add(g);
        if(event.getEntity() instanceof AddonDroneEntity drone && isMavic(drone))MAVICS.add(drone);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if(event.getLevel().isClientSide)return;
        if(event.getEntity() instanceof dev.frontier.arcade.geranium.GeraniumEntity g){GLIDERS.remove(g);releaseGlide(g.getUUID());}
        if(event.getEntity() instanceof AddonDroneEntity drone && isMavic(drone)){MAVICS.remove(drone);releaseMavic(drone.getUUID());}
    }
    private static boolean isMavic(AddonDroneEntity drone){return ControlSessions.isMavic(drone);}
    private static void releaseMavic(UUID id){AirTicket t=MAVIC_TICKETS.remove(id);if(t!=null)t.level.getChunkSource().removeRegionTicket(MAVIC_FLIGHT,t.pos,2,id);}
    private static void releaseGlide(UUID id){dev.frontier.arcade.geranium.FlightChunks.release(id);}
    /** Strike aircraft fly their whole radio range; other units keep the former 1024-block camera limit. */
    private static double viewLimit(AddonDroneEntity drone){
        return drone instanceof dev.frontier.arcade.geranium.GeraniumEntity g ? g.signalRange()+64 : 1024d;
    }
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
            dev.frontier.arcade.geranium.FlightChunks.hold(level,g); // own chunk plus look-ahead along the path
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        Watch old=WATCH.remove(e.getEntity().getUUID());if(old!=null)old.level.getChunkSource().removeRegionTicket(BODY,old.body,3,e.getEntity().getUUID());
        SENT_RADIUS.remove(e.getEntity().getUUID());APPLYING_RADIUS.remove(e.getEntity().getUUID());
        for(var g:List.copyOf(GLIDERS))if(e.getEntity().getUUID().equals(g.ownerId()))releaseGlide(g.getUUID());
        for(AddonDroneEntity mavic:List.copyOf(MAVICS))if(e.getEntity().getUUID().equals(ControlSessions.ownerId(mavic)))releaseMavic(mavic.getUUID());
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent e){
        for(UUID id:List.copyOf(MAVIC_TICKETS.keySet()))releaseMavic(id);
        MAVICS.clear();WATCH.clear();GLIDERS.clear();SENT_RADIUS.clear();APPLYING_RADIUS.clear();dev.frontier.arcade.geranium.FlightChunks.clear();
    }
}
