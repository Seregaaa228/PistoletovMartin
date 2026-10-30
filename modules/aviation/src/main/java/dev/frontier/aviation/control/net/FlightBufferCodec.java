package dev.frontier.aviation.control.net;

import io.netty.buffer.ByteBuf;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;

/** Forge bridge preserving FriendlyByteBuf.writeByteArray/readByteArray framing. */
public final class FlightBufferCodec {
    private FlightBufferCodec() {
    }

    public static void encodeRequest(FriendlyByteBuf buffer, FlightWireCodec.Request request) {
        encode(buffer, output -> FlightWireCodec.writeRequest(output, request));
    }

    public static FlightWireCodec.Request decodeRequest(FriendlyByteBuf buffer) {
        return decode(buffer, FlightWireCodec::readRequest);
    }

    public static void encodeReply(FriendlyByteBuf buffer, FlightWireCodec.Reply reply) {
        encode(buffer, output -> FlightWireCodec.writeReply(output, reply));
    }

    public static FlightWireCodec.Reply decodeReply(FriendlyByteBuf buffer) {
        return decode(buffer, FlightWireCodec::readReply);
    }

    private interface Writer {
        void write(DataOutput output) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInput input) throws IOException;
    }

    private static void encode(FriendlyByteBuf buffer, Writer writer) {
        int prefixIndex = buffer.writerIndex();
        // Reserve two bytes because MAX_BYTES=2048 needs at most a two-byte VarInt.
        int bodyIndex = prefixIndex + 2;
        buffer.ensureWritable(2);
        buffer.writerIndex(bodyIndex);
        try {
            writer.write(new BufferOutput(buffer, bodyIndex));
            int bodyLength = buffer.writerIndex() - bodyIndex;
            if (bodyLength > FlightWireCodec.MAX_BYTES) {
                throw new IOException("packet byte limit");
            }

            if (bodyLength < 128) {
                buffer.setByte(prefixIndex, bodyLength);
                // The one-byte envelope is shorter; copy every body byte forward exactly once.
                for (int sourceIndex = bodyIndex; sourceIndex < buffer.writerIndex(); sourceIndex++) {
                    buffer.setByte(sourceIndex - 1, buffer.getByte(sourceIndex));
                }
                buffer.writerIndex(buffer.writerIndex() - 1);
            } else {
                buffer.setByte(prefixIndex, (bodyLength & 0x7f) | 0x80);
                buffer.setByte(prefixIndex + 1, bodyLength >>> 7);
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) {
            buffer.writerIndex(prefixIndex);
            throw invalid(failure);
        }
    }

    private static <T> T decode(FriendlyByteBuf buffer, Reader<T> reader) {
        try {
            int bodyLength = readEnvelopeLength(buffer);
            if (bodyLength > FlightWireCodec.MAX_BYTES || bodyLength > buffer.readableBytes()) {
                throw new IOException("packet byte limit");
            }

            int bodyStart = buffer.readerIndex();
            int bodyLimit = bodyStart + bodyLength;
            T result = reader.read(new BufferInput(buffer, bodyStart, bodyLimit));

            if (buffer.readerIndex() != bodyLimit || buffer.isReadable()) {
                throw new IOException("trailing bytes");
            }
            return result;
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw invalid(failure);
        }
    }

    private static int readEnvelopeLength(FriendlyByteBuf buffer) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buffer.isReadable()) {
                throw new EOFException("truncated envelope length");
            }
            int value = buffer.readUnsignedByte();
            if (shift == 28 && (value & 0x7f) > 0x07) {
                throw new IOException("overflow envelope length");
            }
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("overflow envelope length");
    }

    private static IllegalArgumentException invalid(Throwable failure) {
        return new IllegalArgumentException("invalid flight packet", failure);
    }

    private static final class BufferOutput implements DataOutput {
        private final ByteBuf buffer;
        private final int bodyStart;

        private BufferOutput(ByteBuf buffer, int bodyStart) {
            this.buffer = buffer;
            this.bodyStart = bodyStart;
        }

        private void checkCapacity(int length) throws IOException {
            if (length < 0 || buffer.writerIndex() - bodyStart + length > FlightWireCodec.MAX_BYTES) {
                throw new IOException("packet byte limit");
            }
        }

        @Override
        public void write(int value) throws IOException {
            writeByte(value);
        }

        @Override
        public void write(byte[] values) throws IOException {
            write(values, 0, values.length);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            checkCapacity(length);
            buffer.writeBytes(values, offset, length);
        }

        @Override
        public void writeBoolean(boolean value) throws IOException {
            writeByte(value ? 1 : 0);
        }

        @Override
        public void writeByte(int value) throws IOException {
            checkCapacity(1);
            buffer.writeByte(value);
        }

        @Override
        public void writeShort(int value) throws IOException {
            checkCapacity(2);
            buffer.writeShort(value);
        }

        @Override
        public void writeChar(int value) throws IOException {
            writeShort(value);
        }

        @Override
        public void writeInt(int value) throws IOException {
            checkCapacity(4);
            buffer.writeInt(value);
        }

        @Override
        public void writeLong(long value) throws IOException {
            checkCapacity(8);
            buffer.writeLong(value);
        }

        @Override
        public void writeFloat(float value) throws IOException {
            writeInt(Float.floatToIntBits(value));
        }

        @Override
        public void writeDouble(double value) throws IOException {
            writeLong(Double.doubleToLongBits(value));
        }

        @Override
        public void writeBytes(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeChars(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeUTF(String value) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class BufferInput implements DataInput {
        private final ByteBuf buffer;
        private final int bodyLimit;

        private BufferInput(ByteBuf buffer, int bodyStart, int bodyLimit) {
            this.buffer = buffer;
            this.bodyLimit = bodyLimit;
            buffer.readerIndex(bodyStart);
        }

        private void checkAvailable(int length) throws IOException {
            if (length < 0 || buffer.readerIndex() + length > bodyLimit) {
                throw new EOFException("truncated packet body");
            }
        }

        @Override
        public void readFully(byte[] values) throws IOException {
            readFully(values, 0, values.length);
        }

        @Override
        public void readFully(byte[] values, int offset, int length) throws IOException {
            checkAvailable(length);
            buffer.readBytes(values, offset, length);
        }

        @Override
        public int skipBytes(int length) throws IOException {
            checkAvailable(length);
            buffer.skipBytes(length);
            return length;
        }

        @Override
        public boolean readBoolean() throws IOException {
            return readUnsignedByte() != 0;
        }

        @Override
        public byte readByte() throws IOException {
            checkAvailable(1);
            return buffer.readByte();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            checkAvailable(1);
            return buffer.readUnsignedByte();
        }

        @Override
        public short readShort() throws IOException {
            checkAvailable(2);
            return buffer.readShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            checkAvailable(2);
            return buffer.readUnsignedShort();
        }

        @Override
        public char readChar() throws IOException {
            return (char) readUnsignedShort();
        }

        @Override
        public int readInt() throws IOException {
            checkAvailable(4);
            return buffer.readInt();
        }

        @Override
        public long readLong() throws IOException {
            checkAvailable(8);
            return buffer.readLong();
        }

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        @Override
        public String readLine() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readUTF() {
            throw new UnsupportedOperationException();
        }
    }
}
