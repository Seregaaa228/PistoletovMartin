package dev.frontier.aviation.control.net;

import dev.frontier.aviation.control.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.UUID;

/** Fixed, bounded wire format shared by the Forge transport and standalone contract tests. */
public final class FlightWireCodec {
    public static final int MAX_BYTES = 2048;
    public static final int MAX_DIMENSION_BYTES = 256;
    private FlightWireCodec() {}
    public enum Operation { ACQUIRE, COMMAND, RELEASE }
    public record Request(Operation operation, String dimensionId, int entityId, UUID vehicleId, long epoch, PilotCommand command) {}
    public record Reply(ControlStatus status, FlightSnapshot snapshot) {}
    public static byte[] encodeRequest(Request request) { return write(out -> writeRequest(out, request)); }
    public static Request decodeRequest(byte[] bytes) { return read(bytes, FlightWireCodec::readRequest); }
    public static byte[] encodeReply(Reply reply) { return write(out -> writeReply(out, reply)); }
    public static Reply decodeReply(byte[] bytes) { return read(bytes, FlightWireCodec::readReply); }
    static void writeRequest(DataOutput out, Request request) throws IOException {
        out.writeByte(request.operation().ordinal());
        if (request.operation() == Operation.ACQUIRE) { string(out, request.dimensionId()); out.writeInt(request.entityId()); }
        uuid(out, request.vehicleId());
        if (request.operation() != Operation.ACQUIRE) out.writeLong(request.epoch());
        if (request.operation() == Operation.COMMAND) command(out, request.command());
    }
    static Request readRequest(DataInput in) throws IOException {
        Operation operation = enumeration(in, Operation.values());
        String dimension = operation == Operation.ACQUIRE ? string(in) : null;
        int entity = operation == Operation.ACQUIRE ? in.readInt() : 0;
        UUID vehicle = uuid(in);
        long epoch = operation == Operation.ACQUIRE ? 0 : in.readLong();
        if (entity < 0 || epoch < 0 || ((operation == Operation.COMMAND || operation == Operation.RELEASE) && epoch == 0))
            throw new IOException("invalid request identity");
        return new Request(operation, dimension, entity, vehicle, epoch, operation == Operation.COMMAND ? command(in) : null);
    }
    static void writeReply(DataOutput out, Reply reply) throws IOException {
        out.writeByte(reply.status().ordinal()); out.writeBoolean(reply.snapshot() != null);
        if (reply.snapshot() != null) snapshot(out, reply.snapshot());
    }
    static Reply readReply(DataInput in) throws IOException {
        return new Reply(enumeration(in, ControlStatus.values()), bool(in) ? snapshot(in) : null);
    }
    private static void command(DataOutput out, PilotCommand c) throws IOException {
        out.writeLong(c.sequence()); out.writeFloat(c.pitchAxis()); out.writeFloat(c.yawAxis());
        out.writeFloat(c.rollAxis()); out.writeFloat(c.throttleAxis());
        out.writeBoolean(c.brake()); out.writeBoolean(c.secondaryBrake()); out.writeBoolean(c.boost());
        out.writeByte(c.ignition().ordinal()); out.writeByte(c.gear().ordinal()); out.writeByte(c.hover().ordinal());
    }
    private static PilotCommand command(DataInput in) throws IOException {
        return new PilotCommand(in.readLong(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
            bool(in), bool(in), bool(in), enumeration(in, ModeRequest.values()),
            enumeration(in, ModeRequest.values()), enumeration(in, ModeRequest.values()));
    }
    private static void snapshot(DataOutput out, FlightSnapshot s) throws IOException {
        string(out, s.dimensionId()); out.writeInt(s.entityId()); uuid(out, s.vehicleId());
        out.writeBoolean(s.controllerId() != null); if (s.controllerId() != null) uuid(out, s.controllerId()); out.writeLong(s.epoch());
        FlightTelemetry t = s.telemetry(); FlightState state = t.state();
        out.writeFloat(state.pitch()); out.writeFloat(state.yaw()); out.writeFloat(state.roll());
        out.writeFloat(state.pitchRate()); out.writeFloat(state.yawRate()); out.writeFloat(state.rollRate());
        out.writeFloat(state.targetThrottle()); out.writeFloat(state.effectivePower()); out.writeByte(state.enginePhase().ordinal());
        out.writeInt(state.phaseTicks()); out.writeFloat(state.spool()); out.writeBoolean(state.gearDown()); out.writeBoolean(state.hover());
        out.writeInt(state.throttleHoldTicks()); out.writeFloat(t.speed()); out.writeFloat(t.maximumPower());
        out.writeBoolean(t.brake()); out.writeBoolean(t.secondaryBrake()); out.writeBoolean(t.boost()); out.writeByte(t.status().ordinal());
        out.writeLong(t.serverTick()); out.writeLong(t.acknowledgedSequence());
    }
    private static FlightSnapshot snapshot(DataInput in) throws IOException {
        String dimension = string(in); int entity = in.readInt(); UUID vehicle = uuid(in);
        UUID controller = bool(in) ? uuid(in) : null; long epoch = in.readLong();
        if (entity < 0 || epoch <= 0) throw new IOException("invalid snapshot identity");
        FlightState state = new FlightState(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
            in.readFloat(), in.readFloat(), enumeration(in, EnginePhase.values()), in.readInt(), in.readFloat(), bool(in), bool(in), in.readInt());
        FlightTelemetry telemetry = new FlightTelemetry(state, in.readFloat(), in.readFloat(), bool(in), bool(in), bool(in),
            enumeration(in, ControlStatus.values()), in.readLong(), in.readLong());
        return new FlightSnapshot(dimension, entity, vehicle, controller, epoch, telemetry);
    }
    private static void uuid(DataOutput out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInput in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void string(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_DIMENSION_BYTES) throw new IOException("dimension byte limit");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String string(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > MAX_DIMENSION_BYTES) throw new IOException("dimension byte limit");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static boolean bool(DataInput in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw new IOException("boolean encoding"); return value == 1;
    }
    private static <T> T enumeration(DataInput in, T[] values) throws IOException {
        int ordinal = in.readUnsignedByte(); if (ordinal >= values.length) throw new IOException("enum ordinal"); return values[ordinal];
    }
    private interface Writer { void run(DataOutput out) throws IOException; }
    private interface Reader<T> { T run(DataInput in) throws IOException; }
    private static byte[] write(Writer writer) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); writer.run(new DataOutputStream(bytes));
            if (bytes.size() > MAX_BYTES) throw new IOException("packet byte limit"); return bytes.toByteArray();
        } catch (IOException | IllegalArgumentException failure) { throw new IllegalArgumentException("invalid flight packet", failure); }
    }
    private static <T> T read(byte[] bytes, Reader<T> reader) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("packet byte limit");
        try { DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes)); T result = reader.run(input);
            if (input.available() != 0) throw new IOException("trailing bytes"); return result;
        } catch (IOException | IllegalArgumentException failure) { throw new IllegalArgumentException("invalid flight packet", failure); }
    }
}
