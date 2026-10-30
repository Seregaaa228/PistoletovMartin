package dev.frontier.aviation.control.net;

import dev.frontier.aviation.control.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** Pure-Java standalone contract tests for the frozen body codec. */
public final class FlightPacketCodecTest {
    private static int assertions;

    public static void main(String[] args) {
        UUID vehicle = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        PilotCommand command = new PilotCommand(42, -.5f, 1, -1, .25f, true, false, true,
            ModeRequest.ENABLE, ModeRequest.DISABLE, ModeRequest.KEEP);
        var acquire = new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE,
            "minecraft:overworld", 127, vehicle, 0, null);
        var request = new FlightWireCodec.Request(FlightWireCodec.Operation.COMMAND,
            null, 0, vehicle, 17, command);
        var release = new FlightWireCodec.Request(FlightWireCodec.Operation.RELEASE,
            null, 0, vehicle, 17, null);

        for (var value : new FlightWireCodec.Request[]{acquire, request, release}) {
            byte[] bytes = FlightWireCodec.encodeRequest(value);
            check(value.equals(FlightWireCodec.decodeRequest(bytes)), "request round trip");
            for (int length = 0; length < bytes.length; length++) {
                byte[] truncated = Arrays.copyOf(bytes, length);
                rejects(() -> FlightWireCodec.decodeRequest(truncated), "request truncation");
            }
            rejects(() -> FlightWireCodec.decodeRequest(Arrays.copyOf(bytes, bytes.length + 1)),
                "request trailing bytes");
        }

        var state = new FlightState(34.5f, -179.5f, 173.25f, 1.2f, -.5f, .75f, .63f, .077f,
            EnginePhase.RUNNING, 0, .8f, true, false, 3);
        var telemetry = new FlightTelemetry(state, 2.5f, .12f, true, false, true,
            ControlStatus.TIMEOUT, 810, 42);
        var snapshot = new FlightSnapshot("minecraft:overworld", 127, vehicle, owner, 17, telemetry);
        var reply = new FlightWireCodec.Reply(ControlStatus.OK, snapshot);
        check(reply.equals(FlightWireCodec.decodeReply(FlightWireCodec.encodeReply(reply))),
            "snapshot round trip");
        var noOwner = new FlightWireCodec.Reply(ControlStatus.RELEASED,
            new FlightSnapshot("minecraft:the_nether", 127, vehicle, null, 17, telemetry));
        check(noOwner.equals(FlightWireCodec.decodeReply(FlightWireCodec.encodeReply(noOwner))),
            "snapshot without controller");

        byte[] bytes = FlightWireCodec.encodeRequest(request);
        byte[] invalid = bytes.clone();
        invalid[0] = (byte) 255;
        rejects(() -> FlightWireCodec.decodeRequest(invalid), "enum bounds");
        byte[] nan = bytes.clone();
        ByteBuffer.wrap(nan).putFloat(33, Float.NaN);
        rejects(() -> FlightWireCodec.decodeRequest(nan), "NaN axis");
        byte[] range = bytes.clone();
        ByteBuffer.wrap(range).putFloat(33, 1.01f);
        rejects(() -> FlightWireCodec.decodeRequest(range), "axis bounds");
        byte[] epoch = bytes.clone();
        ByteBuffer.wrap(epoch).putLong(17, 0);
        rejects(() -> FlightWireCodec.decodeRequest(epoch), "positive epoch");
        byte[] bool = bytes.clone();
        bool[49] = 2;
        rejects(() -> FlightWireCodec.decodeRequest(bool), "strict boolean");
        byte[] sequence = bytes.clone();
        ByteBuffer.wrap(sequence).putLong(25, -1);
        rejects(() -> FlightWireCodec.decodeRequest(sequence), "sequence bounds");
        rejects(() -> FlightWireCodec.decodeReply(new byte[FlightWireCodec.MAX_BYTES + 1]),
            "wire size budget");
        var utf = new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE, "я".repeat(129),
            1, vehicle, 0, null);
        rejects(() -> FlightWireCodec.encodeRequest(utf), "UTF-8 byte budget");
        byte[] malformedUtf = FlightWireCodec.encodeRequest(acquire);
        malformedUtf[3] = (byte) 0xff;
        rejects(() -> FlightWireCodec.decodeRequest(malformedUtf), "malformed UTF-8");

        System.out.println("FlightPacketCodecTest: " + assertions + " assertions passed");
    }

    private static void rejects(Runnable action, String message) {
        assertions++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
