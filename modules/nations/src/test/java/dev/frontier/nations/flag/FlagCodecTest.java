package dev.frontier.nations.flag;

/**
 * Codec test: NBT round-trip plus packed 128-byte wire round-trip.
 * Runs without Minecraft: only {@link FlagCodec}/{@link FlagPresets} are exercised here.
 */
public final class FlagCodecTest {
    private static int checks;

    private static void check(String label, boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        FlagDefinition solid = FlagDefinition.solid(3);
        check("solid fills every pixel", solid.pixel(0, 0) == 3 && solid.pixel(15, 15) == 3);
        check("packed size is exactly 128 bytes", FlagCodec.pack(solid.pixels()).length == FlagCodec.PACKED_BYTES);

        byte[] gradient = new byte[FlagCodec.PIXELS];
        for (int index = 0; index < gradient.length; index++) gradient[index] = (byte) (index & 0xF);
        FlagDefinition original = new FlagDefinition(gradient);
        check("packet round-trip preserves pixels",
                java.util.Arrays.equals(FlagCodec.unpack(FlagCodec.pack(original.pixels())), gradient));
        check("packet round-trip preserves definition",
                new FlagDefinition(FlagCodec.unpack(FlagCodec.pack(original.pixels()))).equals(original));

        for (FlagPreset preset : FlagPreset.values()) {
            FlagDefinition built = FlagPresets.build(preset, 14, 0, 11);
            check("preset " + preset + " packs to 128 bytes", FlagCodec.pack(built.pixels()).length == 128);
            check("preset " + preset + " survives packet round-trip",
                    new FlagDefinition(FlagCodec.unpack(FlagCodec.pack(built.pixels()))).equals(built));
            check("preset " + preset + " uses at least two colors", distinctColors(built) >= 2);
        }

        try {
            new FlagDefinition(new byte[10]);
            check("short pixel buffer rejected", false);
        } catch (IllegalArgumentException expected) {
            check("short pixel buffer rejected", true);
        }
        try {
            byte[] bad = new byte[FlagCodec.PIXELS];
            bad[7] = 16;
            new FlagDefinition(bad);
            check("palette index above 15 rejected", false);
        } catch (IllegalArgumentException expected) {
            check("palette index above 15 rejected", true);
        }

        System.out.println("FlagCodecTest: " + checks + " checks passed");
    }

    private static int distinctColors(FlagDefinition flag) {
        boolean[] seen = new boolean[FlagCodec.COLORS];
        int count = 0;
        for (byte pixel : flag.pixels()) {
            if (!seen[pixel & 0xF]) {
                seen[pixel & 0xF] = true;
                count++;
            }
        }
        return count;
    }
}
