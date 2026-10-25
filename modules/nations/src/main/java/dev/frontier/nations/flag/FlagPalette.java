package dev.frontier.nations.flag;

/**
 * Editor palette: the 16 vanilla dye colors in dye order 0-15 with map-ready ARGB swatches.
 * Index order matches {@code minecraft: dye index}: white, orange, magenta, light blue,
 * yellow, lime, pink, gray, light gray, cyan, purple, blue, brown, green, red, black.
 */
public final class FlagPalette {
    /** Dye names in palette order, for tooltips. */
    public static final String[] NAMES = {
            "Белый", "Оранжевый", "Пурпурный", "Голубой", "Жёлтый", "Лаймовый",
            "Розовый", "Серый", "Светло-серый", "Бирюзовый", "Фиолетовый", "Синий",
            "Коричневый", "Зелёный", "Красный", "Чёрный"};
    /** ARGB swatches for the editor row and mini-flags. */
    public static final int[] ARGB = {
            0xFFF9FFFE, 0xFFF9801D, 0xFFC74EBD, 0xFF3AB3DA, 0xFFFED83D, 0xFF80C71F,
            0xFFF38BAA, 0xFF474F52, 0xFF9D9D97, 0xFF169C9C, 0xFF8932B8, 0xFF3C44AA,
            0xFF835432, 0xFF5E7C16, 0xFFB02E26, 0xFF1D1D21};

    private FlagPalette() {}

    public static int color(int index) {
        return ARGB[index & 0xF];
    }

    public static String name(int index) {
        return NAMES[index & 0xF];
    }
}
