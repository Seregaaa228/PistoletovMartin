package dev.frontier.nations.flag;

/**
 * Pure 16x16 flag packing. No Minecraft dependencies so unit tests run on a plain JDK.
 *
 * <p>Memory layout is {@code byte[256]} row-major ({@code y * 16 + x}), one palette
 * index 0-15 per pixel. Network layout is {@code byte[128]}: two pixels per byte,
 * even pixel in the high nibble, odd pixel in the low nibble. The {@code byte[256]}
 * form is kept in memory and NBT because it allows direct {@code [y * 16 + x]}
 * indexing for the editor and renderer; the packed 128-byte form is used only on
 * the wire to keep flag packets small.</p>
 */
public final class FlagCodec {
    /** Flag edge in pixels. */
    public static final int SIZE = 16;
    /** Pixel count ({@code SIZE * SIZE}). */
    public static final int PIXELS = SIZE * SIZE;
    /** Packed network bytes ({@code PIXELS / 2}). */
    public static final int PACKED_BYTES = PIXELS / 2;
    /** Current format version stored in NBT and save data. */
    public static final int FORMAT_VERSION = 1;
    /** Palette entries (vanilla dye order 0-15). */
    public static final int COLORS = 16;

    private FlagCodec() {}

    /** Rejects null, wrong length, or palette indices outside 0-15. */
    public static void validate(byte[] pixels) {
        if (pixels == null || pixels.length != PIXELS) {
            throw new IllegalArgumentException("flag must hold exactly " + PIXELS + " pixels");
        }
        for (byte pixel : pixels) {
            if ((pixel & 0xFF) >= COLORS) {
                throw new IllegalArgumentException("flag palette index out of range: " + (pixel & 0xFF));
            }
        }
    }

    /** Packs 256 palette indices into 128 bytes (high nibble = even pixel). */
    public static byte[] pack(byte[] pixels) {
        validate(pixels);
        byte[] packed = new byte[PACKED_BYTES];
        for (int i = 0; i < PIXELS; i++) {
            int value = pixels[i] & 0xF;
            if ((i & 1) == 0) packed[i >> 1] = (byte) (value << 4);
            else packed[i >> 1] |= (byte) value;
        }
        return packed;
    }

    /** Unpacks 128 bytes back into 256 palette indices. */
    public static byte[] unpack(byte[] packed) {
        if (packed == null || packed.length != PACKED_BYTES) {
            throw new IllegalArgumentException("packed flag must hold exactly " + PACKED_BYTES + " bytes");
        }
        byte[] pixels = new byte[PIXELS];
        for (int i = 0; i < PIXELS; i++) {
            int value = packed[i >> 1];
            pixels[i] = (byte) (((i & 1) == 0 ? value >>> 4 : value) & 0xF);
        }
        return pixels;
    }
}
