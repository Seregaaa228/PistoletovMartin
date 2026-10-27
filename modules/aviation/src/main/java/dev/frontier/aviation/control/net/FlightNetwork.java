package dev.frontier.aviation.control.net;

import dev.frontier.aviation.control.*;
import dev.frontier.aviation.control.server.FlightControlService;
import dev.frontier.aviation.missile.F16MissileLauncher;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** Server-authorized flight intentions. Common code never links a Minecraft client class. */
public final class FlightNetwork {
    public static final String PROTOCOL = "1.1.0:flight4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("frontier_aviation", "flight_controls"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static Consumer<FlightSnapshot> clientReceiver = ignored -> {};
    private static boolean registered;
    // Server-owned rate windows are per connection identity and cleared by lifecycle events.
    private static final Map<UUID, Window> ACQUIRE_WINDOWS = new HashMap<>();
    // Distinct connection/player objects do not inherit a disconnected player's budget.
    // Weak keys also let late packets from a closed connection be collected.
    private static final Map<ServerPlayer, FlightIngressLimiter> INGRESS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Window { long start; int count; Window(long now) { start = now; } }
    private FlightNetwork() {}
    public static void register() {
        if (registered) return; registered = true;
        CHANNEL.registerMessage(0, Acquire.class, Acquire::encode, Acquire::decode, Acquire::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Command.class, Command::encode, Command::decode, Command::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Release.class, Release::encode, Release::decode, Release::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, Snapshot.class, Snapshot::encode, Snapshot::decode, Snapshot::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(4, MissileTrigger.class, MissileTrigger::encode, MissileTrigger::decode,
            MissileTrigger::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public static void installClientReceiver(Consumer<FlightSnapshot> receiver) { clientReceiver = Objects.requireNonNull(receiver); }
    public static void requestLease(Entity vehicle) {
        CHANNEL.sendToServer(new Acquire(new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE,
            vehicle.level().dimension().location().toString(), vehicle.getId(), vehicle.getUUID(), 0, null)));
    }
    public static void submit(ControlLease lease, PilotCommand command) {
        CHANNEL.sendToServer(new Command(new FlightWireCodec.Request(FlightWireCodec.Operation.COMMAND, null, 0, lease.vehicleId(), lease.epoch(), command)));
    }
    public static void release(ControlLease lease) {
        CHANNEL.sendToServer(new Release(new FlightWireCodec.Request(FlightWireCodec.Operation.RELEASE, null, 0, lease.vehicleId(), lease.epoch(), null)));
    }
    public static void fireMissiles(FlightSnapshot snapshot, int count, Vec3 target) {
        CHANNEL.sendToServer(new MissileTrigger(snapshot.vehicleId(), snapshot.epoch(), count, target));
    }
    public static void sendSnapshot(ServerPlayer receiver, FlightSnapshot snapshot) { send(receiver, ControlStatus.OK, snapshot); }
    private static void send(ServerPlayer receiver, ControlStatus status, FlightSnapshot snapshot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver), new Snapshot(new FlightWireCodec.Reply(status, snapshot)));
    }
    public static void broadcastSnapshot(Entity vehicle, FlightSnapshot snapshot) {
        // Native tracking includes the riding pilot; explicit owner delivery would duplicate every cadence packet.
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> vehicle), new Snapshot(new FlightWireCodec.Reply(ControlStatus.OK, snapshot)));

    }
    public static void clear(ServerPlayer controller) { INGRESS.remove(controller); ACQUIRE_WINDOWS.remove(controller.getUUID()); }
    public static void clear() { INGRESS.clear(); ACQUIRE_WINDOWS.clear(); }
    private static boolean live(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator() && !player.isRemoved()
            && player.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }
    private static boolean allowed(Map<UUID, Window> windows, UUID id, int budget) {
        long now = System.nanoTime(); Window window = windows.computeIfAbsent(id, ignored -> new Window(now));
        if (now - window.start >= 1_000_000_000L) { window.start = now; window.count = 0; }
        if (window.count >= budget) return false; window.count++; return true;
    }
    private static FlightWireCodec.Request read(FriendlyByteBuf buffer, FlightWireCodec.Operation expected) {
        try {
            FlightWireCodec.Request request = FlightBufferCodec.decodeRequest(buffer);
            if (request.operation() != expected) throw new IllegalArgumentException("wrong packet operation");
            return request;
        } catch (IllegalArgumentException failure) { throw new DecoderException("Malformed flight request", failure); }
    }
    private static void write(FriendlyByteBuf buffer, FlightWireCodec.Request request) { FlightBufferCodec.encodeRequest(buffer, request); }
    private static void handle(FlightWireCodec.Request request, Supplier<NetworkEvent.Context> source) {
        NetworkEvent.Context context = source.get();
        ServerPlayer sender = context.getSender();
        context.setPacketHandled(true);
        if (sender == null) return;
        FlightIngressLimiter limiter = INGRESS.computeIfAbsent(sender,
            ignored -> new FlightIngressLimiter(32, 64, 1_000_000_000L));
        FlightIngressLimiter.Decision admission = limiter.tryAcquire(System.nanoTime());
        if (admission == FlightIngressLimiter.Decision.DROP) return;
        if (admission == FlightIngressLimiter.Decision.RESET) {
            try {
                context.enqueueWork(() -> {
                    try {
                        if (live(sender)) FlightControlService.get(sender.server)
                            .releaseController(sender.getUUID(), ReleaseReason.REQUESTED);
                    } finally { limiter.completeReset(); }
                });
            } catch (RuntimeException failure) { limiter.completeReset(); throw failure; }
            return;
        }
        try { context.enqueueWork(() -> {
          try {
            ServerPlayer player = context.getSender(); if (!live(player)) return;
            FlightControlService service = FlightControlService.get(player.server);
            if (request.operation() == FlightWireCodec.Operation.ACQUIRE) {
                if (!allowed(ACQUIRE_WINDOWS, player.getUUID(), 5)) return;
                if (!player.level().dimension().location().toString().equals(request.dimensionId())) { send(player, ControlStatus.NOT_PILOT, null); return; }
                Entity vehicle = player.serverLevel().getEntity(request.entityId());
                if (vehicle == null || !vehicle.getUUID().equals(request.vehicleId())) { send(player, ControlStatus.NOT_PILOT, null); return; }
                Optional<ControlLease> lease = service.acquire(player, vehicle);
                if (lease.isPresent()) service.snapshot(vehicle).ifPresent(snapshot -> sendSnapshot(player, snapshot));
                else send(player, ControlStatus.NOT_PILOT, null);
                return;
            }
            Entity vehicle = player.serverLevel().getEntity(request.vehicleId());
            if (vehicle == null) return;
            ControlLease lease = new ControlLease(request.vehicleId(), player.getUUID(), request.epoch());
            if (request.operation() == FlightWireCodec.Operation.RELEASE) {
                // Release checks server-owned lease identity; old-owner requests cannot release a handoff.
                service.release(lease, ReleaseReason.REQUESTED); return;
            }
            ControlStatus status = service.submit(player, lease, request.command());
            if (status != ControlStatus.OK) {
                // At most one command failure reply per accepted rate window slot.
                service.snapshot(vehicle).ifPresent(snapshot -> send(player, status, snapshot));
            }
          } finally { limiter.completeTask(); }
        }); } catch (RuntimeException failure) { limiter.completeTask(); throw failure; }
    }
    public record Acquire(FlightWireCodec.Request request) {
        static void encode(Acquire packet, FriendlyByteBuf buffer) { write(buffer, packet.request); }
        static Acquire decode(FriendlyByteBuf buffer) { return new Acquire(read(buffer, FlightWireCodec.Operation.ACQUIRE)); }
        static void handle(Acquire packet, Supplier<NetworkEvent.Context> context) { FlightNetwork.handle(packet.request, context); }
    }
    public record Command(FlightWireCodec.Request request) {
        static void encode(Command packet, FriendlyByteBuf buffer) { write(buffer, packet.request); }
        static Command decode(FriendlyByteBuf buffer) { return new Command(read(buffer, FlightWireCodec.Operation.COMMAND)); }
        static void handle(Command packet, Supplier<NetworkEvent.Context> context) { FlightNetwork.handle(packet.request, context); }
    }
    public record Release(FlightWireCodec.Request request) {
        static void encode(Release packet, FriendlyByteBuf buffer) { write(buffer, packet.request); }
        static Release decode(FriendlyByteBuf buffer) { return new Release(read(buffer, FlightWireCodec.Operation.RELEASE)); }
        static void handle(Release packet, Supplier<NetworkEvent.Context> context) { FlightNetwork.handle(packet.request, context); }
    }
    public record Snapshot(FlightWireCodec.Reply reply) {
        static void encode(Snapshot packet, FriendlyByteBuf buffer) { FlightBufferCodec.encodeReply(buffer, packet.reply); }
        static Snapshot decode(FriendlyByteBuf buffer) {
            try {
                return new Snapshot(FlightBufferCodec.decodeReply(buffer));
            } catch (IllegalArgumentException failure) { throw new DecoderException("Malformed flight snapshot", failure); }
        }
        static void handle(Snapshot packet, Supplier<NetworkEvent.Context> source) {
            NetworkEvent.Context context = source.get();
            context.enqueueWork(() -> {
                // Reply status has no request identity; only the snapshot can be correlated safely.
                if (packet.reply.snapshot() != null) clientReceiver.accept(packet.reply.snapshot());
            });
            context.setPacketHandled(true);
        }
    }

    /** Additive message 4; the original control/snapshot formats keep their IDs and encoding. */
    public record MissileTrigger(UUID vehicleId, long epoch, int count, Vec3 target) {
        public MissileTrigger {
            Objects.requireNonNull(vehicleId);
            if (epoch <= 0 || (count != 1 && count != 2) || target != null
                    && (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)))
                throw new IllegalArgumentException("invalid missile trigger");
        }
        static void encode(MissileTrigger packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.vehicleId); buffer.writeLong(packet.epoch); buffer.writeByte(packet.count);
            buffer.writeBoolean(packet.target != null);
            if (packet.target != null) {
                buffer.writeDouble(packet.target.x); buffer.writeDouble(packet.target.y); buffer.writeDouble(packet.target.z);
            }
        }
        static MissileTrigger decode(FriendlyByteBuf buffer) {
            try {
                if (buffer.readableBytes() != 26 && buffer.readableBytes() != 50)
                    throw new IllegalArgumentException("missile trigger size");
                UUID vehicle = buffer.readUUID(); long epoch = buffer.readLong(); int count = buffer.readUnsignedByte();
                int present = buffer.readUnsignedByte();
                if (present > 1) throw new IllegalArgumentException("target flag");
                Vec3 target = present == 0 ? null : new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
                if (buffer.isReadable()) throw new IllegalArgumentException("trailing missile bytes");
                return new MissileTrigger(vehicle, epoch, count, target);
            } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                throw new DecoderException("Malformed missile trigger", error);
            }
        }
        static void handle(MissileTrigger packet, Supplier<NetworkEvent.Context> source) {
            NetworkEvent.Context context = source.get();
            context.setPacketHandled(true);
            ServerPlayer sender = context.getSender();
            if (sender == null) return;
            FlightIngressLimiter limiter = INGRESS.computeIfAbsent(sender,
                ignored -> new FlightIngressLimiter(32, 64, 1_000_000_000L));
            FlightIngressLimiter.Decision admission = limiter.tryAcquire(System.nanoTime());
            if (admission == FlightIngressLimiter.Decision.DROP) return;
            if (admission == FlightIngressLimiter.Decision.RESET) {
                try {
                    context.enqueueWork(() -> {
                        try {
                            if (live(sender)) FlightControlService.get(sender.server)
                                .releaseController(sender.getUUID(), ReleaseReason.REQUESTED);
                        } finally { limiter.completeReset(); }
                    });
                } catch (RuntimeException failure) { limiter.completeReset(); throw failure; }
                return;
            }
            try {
                context.enqueueWork(() -> {
                    try {
                        if (!live(sender)) return;
                        Entity vehicle = sender.getVehicle();
                        if (!(vehicle instanceof F16MissileLauncher launcher)
                                || !vehicle.getUUID().equals(packet.vehicleId)) return;
                        FlightControlService service = FlightControlService.get(sender.server);
                        FlightSnapshot current = service.snapshot(vehicle).orElse(null);
                        if (current == null || current.epoch() != packet.epoch || !service.owns(sender, vehicle)) return;
                        launcher.aviation$fireMissiles(sender, packet.count, packet.target);
                    } finally { limiter.completeTask(); }
                });
            } catch (RuntimeException failure) { limiter.completeTask(); throw failure; }
        }
    }
}
