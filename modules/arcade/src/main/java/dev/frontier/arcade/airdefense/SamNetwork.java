package dev.frontier.arcade.airdefense;

import dev.frontier.arcade.ArcadeVehicles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Air-defence traffic, kept small: the radar picture goes only to a site's own crew, twice a
 * second, as a dozen bytes per contact; a burst is one short message to players within earshot;
 * a launch warning goes only to the crew of the aircraft being shot at. Missile flight itself is
 * the vanilla entity tracker at a low update rate, flown between updates by the client.
 */
public final class SamNetwork {
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ArcadeVehicles.ID, "air_defense"), () -> "2", "2"::equals, "2"::equals);
    /** Contacts sent per picture: the nearest ones; a scope cannot show more legibly anyway. */
    public static final int MAX_CONTACTS = 24;
    /** Bursts are heard and seen this far. */
    private static final double BURST_RANGE = 384;
    private static final Map<UUID, Long> LAST_COMMAND = new WeakHashMap<>();
    private static boolean initialized;

    private SamNetwork() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(0, Picture.class, Picture::encode, Picture::decode, Picture::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Command.class, Command::encode, Command::decode, Command::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, Burst.class, Burst::encode, Burst::decode, Burst::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Warning.class, Warning::encode, Warning::decode, Warning::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    // ------------------------------------------------------------------ radar picture

    /** One contact relative to the site; {@code relation} 0 hostile, 1 unknown, 2 friendly. */
    public record Contact(int entityId, byte kind, byte relation, boolean engaged, boolean inEnvelope,
                          short dx, short dy, short dz, short altitude) { }

    public record Picture(int site, byte mode, byte deploy, byte inFlight, int designated, byte[] ready, byte[] reload,
                          List<Contact> contacts) {
        void encode(FriendlyByteBuf out) {
            out.writeVarInt(site);
            out.writeByte(mode);
            out.writeByte(deploy);
            out.writeByte(inFlight);
            out.writeVarInt(designated + 1);
            out.writeByte(ready.length);
            for (int i = 0; i < ready.length; i++) { out.writeByte(ready[i]); out.writeByte(reload[i]); }
            out.writeByte(contacts.size());
            for (Contact c : contacts) {
                out.writeVarInt(c.entityId);
                out.writeByte(c.kind | c.relation << 3 | (c.engaged ? 1 << 5 : 0) | (c.inEnvelope ? 1 << 6 : 0));
                out.writeShort(c.dx); out.writeShort(c.dy); out.writeShort(c.dz); out.writeShort(c.altitude);
            }
        }

        static Picture decode(FriendlyByteBuf in) {
            int site = in.readVarInt();
            byte mode = in.readByte(), deploy = in.readByte(), inFlight = in.readByte();
            int designated = in.readVarInt() - 1;
            int racks = Math.min(4, in.readUnsignedByte());
            byte[] ready = new byte[racks], reload = new byte[racks];
            for (int i = 0; i < racks; i++) { ready[i] = in.readByte(); reload[i] = in.readByte(); }
            int count = Math.min(MAX_CONTACTS, in.readUnsignedByte());
            List<Contact> contacts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = in.readVarInt();
                int flags = in.readUnsignedByte();
                contacts.add(new Contact(id, (byte) (flags & 7), (byte) ((flags >> 3) & 3), (flags & 1 << 5) != 0, (flags & 1 << 6) != 0,
                    in.readShort(), in.readShort(), in.readShort(), in.readShort()));
            }
            return new Picture(site, mode, deploy, inFlight, designated, ready, reload, contacts);
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> AirDefense.client().picture(this));
            context.get().setPacketHandled(true);
        }
    }

    static void sendPicture(SamSiteEntity site, Collection<SamSiteEntity.Track> tracks) {
        List<ServerPlayer> crew = site.crew();
        if (crew.isEmpty()) return;
        SamSystem system = site.system();
        int racks = system.racks.size();
        byte[] ready = new byte[racks], reload = new byte[racks];
        int[] readyAll = new int[racks];
        for (int i = 0; i < racks; i++) { readyAll[i] = site.ready(i); ready[i] = (byte) readyAll[i]; reload[i] = (byte) site.reloadPercent(i); }
        List<Contact> contacts = new ArrayList<>();
        Vec3 origin = site.position();
        tracks.stream().sorted(Comparator.comparingDouble(t -> t.range)).limit(MAX_CONTACTS).forEach(t -> {
            Vec3 d = t.position.subtract(origin);
            contacts.add(new Contact(t.entity.getId(), (byte) t.kind.ordinal(), t.relation,
                t.entity.getUUID() != null && SamSiteEntity.engagedCount(t.entity) > 0,
                SamEnvelope.choose(system, readyAll, t.kind, t.range, t.altitude) != null,
                clamp(d.x), clamp(d.y), clamp(d.z), clamp(t.altitude)));
        });
        var picture = new Picture(site.getId(), site.mode(), (byte) site.deployPercent(), (byte) site.inFlight(),
            site.designated(), ready, reload, contacts);
        for (ServerPlayer player : crew) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), picture);
    }

    private static short clamp(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }

    // ------------------------------------------------------------------ crew commands

    public record Command(int site, byte op, int argument) {
        public static final byte TOGGLE_MODE = 0, DESIGNATE = 1;

        void encode(FriendlyByteBuf out) { out.writeVarInt(site); out.writeByte(op); out.writeVarInt(argument); }

        static Command decode(FriendlyByteBuf in) { return new Command(in.readVarInt(), in.readByte(), in.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            context.get().enqueueWork(() -> {
                if (player == null) return;
                long now = player.serverLevel().getGameTime();
                Long last = LAST_COMMAND.get(player.getUUID());
                if (last != null && now - last < 2 && now >= last) return;
                LAST_COMMAND.put(player.getUUID(), now);
                if (!(player.serverLevel().getEntity(site) instanceof SamSiteEntity sam) || player.getVehicle() != sam) return;
                if (op == TOGGLE_MODE) sam.toggleMode(player);
                else if (op == DESIGNATE) sam.designate(player, argument);
            });
            context.get().setPacketHandled(true);
        }
    }

    public static void command(int site, byte op, int argument) {
        CHANNEL.sendToServer(new Command(site, op, argument));
    }

    // ------------------------------------------------------------------ burst

    public record Burst(float x, float y, float z, byte missile, boolean target) {
        void encode(FriendlyByteBuf out) { out.writeFloat(x); out.writeFloat(y); out.writeFloat(z); out.writeByte(missile); out.writeBoolean(target); }

        static Burst decode(FriendlyByteBuf in) {
            return new Burst(in.readFloat(), in.readFloat(), in.readFloat(), in.readByte(), in.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> AirDefense.client().burst(this));
            context.get().setPacketHandled(true);
        }
    }

    static void burst(ServerLevel level, Vec3 at, SamMissile missile, boolean target) {
        CHANNEL.send(PacketDistributor.NEAR.with(PacketDistributor.TargetPoint.p(at.x, at.y, at.z, BURST_RANGE, level.dimension())),
            new Burst((float) at.x, (float) at.y, (float) at.z, (byte) missile.ordinal(), target));
    }

    // ------------------------------------------------------------------ launch warning

    /** Sent to the crew of the aircraft a missile was launched at: bearing to the site and the missile. */
    public record Warning(float bearing, short distance, byte missile, int missileId) {
        void encode(FriendlyByteBuf out) { out.writeFloat(bearing); out.writeShort(distance); out.writeByte(missile); out.writeVarInt(missileId); }

        static Warning decode(FriendlyByteBuf in) { return new Warning(in.readFloat(), in.readShort(), in.readByte(), in.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> AirDefense.client().warning(this));
            context.get().setPacketHandled(true);
        }
    }

    static void warn(Entity target, SamSiteEntity site, SamMissileEntity missile) {
        Vec3 d = site.position().subtract(target.position());
        float bearing = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        var warning = new Warning(bearing, (short) Math.min(Short.MAX_VALUE, d.length()), (byte) missile.missileType().ordinal(), missile.getId());
        for (Entity rider : target.getRootVehicle().getIndirectPassengers())
            if (rider instanceof ServerPlayer player) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), warning);
        if (target instanceof ServerPlayer player) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), warning);
    }
}
