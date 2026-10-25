package dev.frontier.nations.flag;

/**
 * Five baked presets built from the 16 vanilla dye indices.
 * Every preset keeps a guard that at least two different colors are used so a
 * flattened one-color pick still reads as a designed flag.
 */
public final class FlagPresets {
    private FlagPresets() {}

    public static FlagDefinition build(FlagPreset preset, int primary, int secondary, int accent) {
        primary = palette(primary);
        secondary = palette(secondary);
        accent = palette(accent);
        if (preset == null) preset = FlagPreset.HORIZONTAL_TRICOLOR;
        return switch (preset) {
            case HORIZONTAL_TRICOLOR -> tricolor(primary, secondary, accent);
            case NORDIC_CROSS -> nordicCross(primary, secondary, accent);
            case SUN_DISC -> sunDisc(primary, secondary, accent);
            case DIAGONAL -> diagonal(primary, secondary, accent);
            case CHEVRON -> chevron(primary, secondary, accent);
        };
    }

    private static FlagDefinition tricolor(int primary, int secondary, int accent) {
        byte[] pixels = new byte[FlagCodec.PIXELS];
        int[] bands = {guard(primary, secondary), guard(secondary, primary), guard(accent, primary)};
        for (int y = 0; y < FlagCodec.SIZE; y++) {
            byte color = (byte) bands[Math.min(2, y * 3 / FlagCodec.SIZE)];
            for (int x = 0; x < FlagCodec.SIZE; x++) pixels[y * FlagCodec.SIZE + x] = color;
        }
        return new FlagDefinition(pixels);
    }

    private static FlagDefinition nordicCross(int field, int cross, int fimbriation) {
        field = guard(field, cross);
        cross = guard(cross, field);
        byte[] pixels = filled((byte) field);
        int bar = guard(fimbriation, field == cross ? fimbriation : field);
        int vertical = 5;
        int horizontal = 6;
        for (int y = 0; y < FlagCodec.SIZE; y++) {
            for (int x = 0; x < FlagCodec.SIZE; x++) {
                boolean inBar = Math.abs(x - vertical) <= 1 || Math.abs(y - horizontal) <= 1;
                boolean inCross = x == vertical || y == horizontal;
                if (inCross) pixels[y * FlagCodec.SIZE + x] = (byte) cross;
                else if (inBar) pixels[y * FlagCodec.SIZE + x] = (byte) bar;
            }
        }
        return new FlagDefinition(pixels);
    }

    private static FlagDefinition sunDisc(int sky, int sun, int ring) {
        sky = guard(sky, sun);
        sun = guard(sun, sky);
        byte[] pixels = filled((byte) sky);
        int center = FlagCodec.SIZE / 2 - 1;
        for (int y = 0; y < FlagCodec.SIZE; y++) {
            for (int x = 0; x < FlagCodec.SIZE; x++) {
                double distance = Math.hypot(x - center - 0.5, y - center - 0.5);
                if (distance <= 4.5) pixels[y * FlagCodec.SIZE + x] = (byte) sun;
                else if (distance <= 5.6) pixels[y * FlagCodec.SIZE + x] = (byte) guard(ring, sun);
            }
        }
        return new FlagDefinition(pixels);
    }

    private static FlagDefinition diagonal(int first, int second, int stripe) {
        first = guard(first, second);
        second = guard(second, first);
        byte[] pixels = new byte[FlagCodec.PIXELS];
        for (int y = 0; y < FlagCodec.SIZE; y++) {
            for (int x = 0; x < FlagCodec.SIZE; x++) {
                int diagonal = x - y;
                if (diagonal > 1) pixels[y * FlagCodec.SIZE + x] = (byte) first;
                else if (diagonal < -1) pixels[y * FlagCodec.SIZE + x] = (byte) second;
                else pixels[y * FlagCodec.SIZE + x] = (byte) guard(stripe, first);
            }
        }
        return new FlagDefinition(pixels);
    }

    private static FlagDefinition chevron(int field, int chevron, int edge) {
        field = guard(field, chevron);
        chevron = guard(chevron, field);
        byte[] pixels = filled((byte) field);
        for (int y = 0; y < FlagCodec.SIZE; y++) {
            for (int x = 0; x < FlagCodec.SIZE; x++) {
                int arm = Math.abs(y - (FlagCodec.SIZE / 2)) + 1;
                if (x <= arm && x >= arm - 2) pixels[y * FlagCodec.SIZE + x] = (byte) chevron;
                else if (x == arm + 1 || (x == arm - 3 && x >= 0)) pixels[y * FlagCodec.SIZE + x] = (byte) guard(edge, chevron);
            }
        }
        return new FlagDefinition(pixels);
    }

    private static byte[] filled(byte color) {
        byte[] pixels = new byte[FlagCodec.PIXELS];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static int palette(int index) {
        return index & 0xF;
    }

    private static int guard(int candidate, int fallback) {
        return candidate == fallback ? (fallback + 7) & 0xF : candidate;
    }
}
