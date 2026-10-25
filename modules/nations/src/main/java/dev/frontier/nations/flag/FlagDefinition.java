package dev.frontier.nations.flag;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server-owned 16x16 flag bound to a Recruits faction string ID.
 * Native faction creation/leadership stays with Recruits; this only decorates it.
 */
public final class FlagDefinition {
    /** NBT field holding {@link FlagCodec#FORMAT_VERSION}. */
    public static final String NBT_VERSION = "Version";
    /** NBT field holding the raw 256-pixel indices. */
    public static final String NBT_PIXELS = "Pixels";

    private final byte[] pixels;

    public FlagDefinition(byte[] pixels) {
        FlagCodec.validate(pixels);
        this.pixels = pixels.clone();
    }

    public static FlagDefinition solid(int palette) {
        byte[] pixels = new byte[FlagCodec.PIXELS];
        byte value = (byte) (palette & 0xF);
        java.util.Arrays.fill(pixels, value);
        return new FlagDefinition(pixels);
    }

    public byte[] pixels() {
        return pixels.clone();
    }

    public int pixel(int x, int y) {
        return pixels[y * FlagCodec.SIZE + x] & 0xFF;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NBT_VERSION, FlagCodec.FORMAT_VERSION);
        tag.putByteArray(NBT_PIXELS, pixels.clone());
        return tag;
    }

    public static FlagDefinition fromNbt(CompoundTag tag) {
        if (tag == null || tag.getInt(NBT_VERSION) != FlagCodec.FORMAT_VERSION) return null;
        byte[] pixels = tag.getByteArray(NBT_PIXELS);
        if (pixels.length != FlagCodec.PIXELS) return null;
        try {
            return new FlagDefinition(pixels);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Packed 128-byte wire form; see {@link FlagCodec#pack(byte[])}. */
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeByte(FlagCodec.FORMAT_VERSION);
        buffer.writeBytes(FlagCodec.pack(pixels));
    }

    public static FlagDefinition decode(FriendlyByteBuf buffer) {
        if (buffer == null || buffer.readableBytes() < 1 + FlagCodec.PACKED_BYTES)
            throw new io.netty.handler.codec.DecoderException("Truncated flag payload");
        if (buffer.readByte() != FlagCodec.FORMAT_VERSION)
            throw new io.netty.handler.codec.DecoderException("Unsupported flag format");
        byte[] packed = new byte[FlagCodec.PACKED_BYTES];
        buffer.readBytes(packed);
        return new FlagDefinition(FlagCodec.unpack(packed));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FlagDefinition flag && java.util.Arrays.equals(pixels, flag.pixels);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(pixels);
    }
}
