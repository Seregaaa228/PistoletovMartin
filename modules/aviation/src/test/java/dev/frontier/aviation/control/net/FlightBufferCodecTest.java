package dev.frontier.aviation.control.net;

import dev.frontier.aviation.control.*;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Forge-side tests for the external FriendlyByteBuf envelope and bridge. */
public final class FlightBufferCodecTest {
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
            byte[] body = FlightWireCodec.encodeRequest(value);
            byte[] expected = oldFrame(body);
            FriendlyByteBuf encoded = buffer(256, false);
            try {
                FlightBufferCodec.encodeRequest(encoded, value);
                check(Arrays.equals(expected, readable(encoded)), "request framed bytes");
            } finally {
                encoded.release();
            }

            for (int length = 0; length < expected.length; length++) {
                FriendlyByteBuf truncated = buffer(Arrays.copyOf(expected, length), false);
                try {
                    rejects(() -> FlightBufferCodec.decodeRequest(truncated), "request truncation");
                } finally {
                    truncated.release();
                }
            }
            FriendlyByteBuf trailing = buffer(Arrays.copyOf(expected, expected.length + 1), false);
            try {
                trailing.setByte(trailing.writerIndex() - 1, 7);
                rejects(() -> FlightBufferCodec.decodeRequest(trailing), "request outer trailing");
            } finally {
                trailing.release();
            }
            FriendlyByteBuf decoded = buffer(expected, false);
            try {
                check(value.equals(FlightBufferCodec.decodeRequest(decoded)), "request framed round trip");
            } finally {
                decoded.release();
            }
        }

        var state = new FlightState(34.5f, -179.5f, 173.25f, 1.2f, -.5f, .75f, .63f, .077f,
            EnginePhase.RUNNING, 0, .8f, true, false, 3);
        var telemetry = new FlightTelemetry(state, 2.5f, .12f, true, false, true,
            ControlStatus.TIMEOUT, 810, 42);
        var snapshot = new FlightSnapshot("minecraft:overworld", 127, vehicle, owner, 17, telemetry);
        var reply = new FlightWireCodec.Reply(ControlStatus.OK, snapshot);
        byte[] replyBody = FlightWireCodec.encodeReply(reply);
        byte[] replyFrame = oldFrame(replyBody);
        for (int length = 0; length < replyFrame.length; length++) {
            FriendlyByteBuf truncated = buffer(Arrays.copyOf(replyFrame, length), false);
            try {
                rejects(() -> FlightBufferCodec.decodeReply(truncated), "reply truncation");
            } finally {
                truncated.release();
            }
        }
        FriendlyByteBuf replyTrailing = buffer(Arrays.copyOf(replyFrame, replyFrame.length + 1), false);
        try {
            rejects(() -> FlightBufferCodec.decodeReply(replyTrailing), "reply outer trailing");
        } finally {
            replyTrailing.release();
        }
        FriendlyByteBuf replyDecoded = buffer(replyFrame, false);
        try {
            check(reply.equals(FlightBufferCodec.decodeReply(replyDecoded)), "reply framed round trip");
        } finally {
            replyDecoded.release();
        }

        var noOwner = new FlightWireCodec.Reply(ControlStatus.RELEASED,
            new FlightSnapshot("minecraft:the_nether", 127, vehicle, null, 17, telemetry));
        check(noOwner.equals(decodeReply(oldFrame(FlightWireCodec.encodeReply(noOwner)), true)),
            "null owner direct buffer");
        check(new FlightWireCodec.Reply(ControlStatus.RELEASED, null)
                .equals(decodeReply(oldFrame(FlightWireCodec.encodeReply(new FlightWireCodec.Reply(ControlStatus.RELEASED, null))), false)),
            "null snapshot");

        var utf256 = new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE,
            "я".repeat(128), 1, vehicle, 0, null);
        check(utf256.equals(decodeRequest(oldFrame(FlightWireCodec.encodeRequest(utf256)), false)),
            "256 UTF-8 bytes");
        var body127 = new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE,
            "a".repeat(104), 1, vehicle, 0, null);
        var body128 = new FlightWireCodec.Request(FlightWireCodec.Operation.ACQUIRE,
            "a".repeat(105), 1, vehicle, 0, null);
        byte[] frame127 = oldFrame(FlightWireCodec.encodeRequest(body127));
        byte[] frame128 = oldFrame(FlightWireCodec.encodeRequest(body128));
        check((frame127[0] & 0xff) == 127, "127-byte envelope");
        check((frame128[0] & 0xff) == 0x80 && (frame128[1] & 0xff) == 1, "128-byte envelope");
        for (boolean direct : new boolean[]{false, true}) {
            for (int capacity : new int[]{0, 1, 256}) {
                for (var value : new FlightWireCodec.Request[]{acquire, request, release, utf256, body127, body128}) {
                    FriendlyByteBuf encoded = buffer(capacity, direct);
                    try {
                        FlightBufferCodec.encodeRequest(encoded, value);
                        check(Arrays.equals(oldFrame(FlightWireCodec.encodeRequest(value)), readable(encoded)),
                            "request encoder bytes across capacities and envelope boundary");
                        check(value.equals(FlightBufferCodec.decodeRequest(encoded)), "request matrix round trip");
                    } finally {
                        encoded.release();
                    }
                }
                for (var value : new FlightWireCodec.Reply[]{reply, noOwner,
                        new FlightWireCodec.Reply(ControlStatus.RELEASED, null)}) {
                    FriendlyByteBuf encoded = buffer(capacity, direct);
                    try {
                        FlightBufferCodec.encodeReply(encoded, value);
                        check(Arrays.equals(oldFrame(FlightWireCodec.encodeReply(value)), readable(encoded)),
                            "reply encoder bytes across capacities");
                        check(value.equals(FlightBufferCodec.decodeReply(encoded)), "reply matrix round trip");
                    } finally {
                        encoded.release();
                    }
                }
            }
        }

        rejectFrame(new byte[]{0}, "zero envelope");
        rejectFrame(new byte[]{(byte) 0x80}, "truncated envelope VarInt");
        rejectFrame(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f}, "oversize envelope VarInt");
        rejectFrame(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f}, "overflow envelope VarInt");

        byte[] malformed = oldFrame(FlightWireCodec.encodeRequest(acquire));
        malformed[3] = (byte) 0xff;
        rejectRequestFrame(malformed, "malformed UTF-8");
        byte[] invalid = oldFrame(FlightWireCodec.encodeRequest(request));
        invalid[1] = (byte) 0xff;
        rejectRequestFrame(invalid, "invalid operation");
        byte[] nonfinite = oldFrame(FlightWireCodec.encodeRequest(request));
        ByteBuffer.wrap(nonfinite, 1, nonfinite.length - 1).putFloat(34, Float.NaN);
        rejectRequestFrame(nonfinite, "nonfinite axis");
        byte[] invalidBool = oldFrame(FlightWireCodec.encodeRequest(request));
        invalidBool[50] = 2;
        rejectRequestFrame(invalidBool, "invalid boolean");
        byte[] invalidReplyStatus = replyFrame.clone();
        invalidReplyStatus[1] = (byte) 0xff;
        rejectReplyFrame(invalidReplyStatus, "invalid reply status");
        byte[] invalidReplyBool = replyFrame.clone();
        int envelopeBytes = (replyFrame[0] & 0x80) == 0 ? 1 : 2;
        int controllerFlag = envelopeBytes + 1 + 1 + 2
            + "minecraft:overworld".getBytes(StandardCharsets.UTF_8).length + 4 + 16;
        invalidReplyBool[controllerFlag] = 2;
        rejectReplyFrame(invalidReplyBool, "invalid snapshot boolean");

        FriendlyByteBuf prefixed = buffer(256, false);
        try {
            prefixed.writeBytes(new byte[]{11, 22, 33});
            FlightBufferCodec.encodeRequest(prefixed, acquire);
            prefixed.readerIndex(3);
            check(acquire.equals(FlightBufferCodec.decodeRequest(prefixed)) && !prefixed.isReadable(),
                "nonzero reader and writer prefix");
        } finally {
            prefixed.release();
        }

        System.out.println("FlightBufferCodecTest: " + assertions + " assertions passed");
    }

    private static byte[] oldFrame(byte[] body) {
        FriendlyByteBuf buffer = buffer(body.length + 3, false);
        try {
            buffer.writeByteArray(body);
            return readable(buffer);
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf buffer(int capacity, boolean direct) {
        return new FriendlyByteBuf(direct ? Unpooled.directBuffer(capacity) : Unpooled.buffer(capacity));
    }

    private static FriendlyByteBuf buffer(byte[] bytes, boolean direct) {
        FriendlyByteBuf buffer = buffer(bytes.length, direct);
        buffer.writeBytes(bytes);
        return buffer;
    }

    private static byte[] readable(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static FlightWireCodec.Request decodeRequest(byte[] frame, boolean direct) {
        FriendlyByteBuf buffer = buffer(frame, direct);
        try {
            return FlightBufferCodec.decodeRequest(buffer);
        } finally {
            buffer.release();
        }
    }

    private static FlightWireCodec.Reply decodeReply(byte[] frame, boolean direct) {
        FriendlyByteBuf buffer = buffer(frame, direct);
        try {
            return FlightBufferCodec.decodeReply(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void rejectFrame(byte[] frame, String message) {
        FriendlyByteBuf buffer = buffer(frame, false);
        try {
            rejects(() -> FlightBufferCodec.decodeRequest(buffer), message);
        } finally {
            buffer.release();
        }
    }

    private static void rejectRequestFrame(byte[] frame, String message) {
        rejectFrame(frame, message);
    }

    private static void rejectReplyFrame(byte[] frame, String message) {
        FriendlyByteBuf buffer = buffer(frame, false);
        try {
            rejects(() -> FlightBufferCodec.decodeReply(buffer), message);
        } finally {
            buffer.release();
        }
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
