package dev.frontier.industry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Standalone Java17 checks for UTF-8 catalog transfer boundaries. */
public final class CatalogChunksTest {
    private static int checks;

    public static void main(String[] args) {
        check("empty input has one empty range", CatalogChunks.partition(List.of(), 2, 262144)
            .equals(List.of(new CatalogChunks.Range(0, 0))));
        check("one row fits one range", CatalogChunks.partition(List.of("{\"id\":1}"), 2, 11)
            .equals(List.of(new CatalogChunks.Range(0, 1))));
        check("UTF-8 counts bytes rather than UTF-16 code units", CatalogChunks.utf8Bytes("Aя😀") == 7);
        check("Cyrillic rows cannot share a chunk allowed by String.length",
            CatalogChunks.partition(List.of("\"я\"", "\"я\""), 2, 9)
                .equals(List.of(new CatalogChunks.Range(0, 1), new CatalogChunks.Range(1, 2))));
        check("comma is included at the exact boundary", CatalogChunks.partition(List.of("\"a\"", "\"b\""), 2, 9)
            .equals(List.of(new CatalogChunks.Range(0, 2))));
        expectIllegalArgument("negative empty JSON size", () -> CatalogChunks.partition(List.of(), -1, 10));
        expectIllegalArgument("maximum below empty JSON size", () -> CatalogChunks.partition(List.of(), 10, 9));
        expectIllegalArgument("row exceeding its chunk is not truncated",
            () -> CatalogChunks.partition(List.of("\"слишком длинная строка\""), 2, 10));
        check("deterministic SHA-256", CatalogChunks.sha256("abc")
            .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check("base-only JSON accepts the exact UTF-8 byte boundary",
            CatalogChunks.requireJsonBytes("я".repeat(131072), 262144) == 262144);
        expectIllegalArgument("base-only JSON rejects one byte over budget",
            () -> CatalogChunks.requireJsonBytes("x".repeat(262145), 262144));
        checkLargeCatalog();
        System.out.println("CatalogChunksTest: " + checks + " checks passed");
    }

    private static void checkLargeCatalog() {
        List<String> rows = new ArrayList<>();
        String cyrillic = "Длиннаякириллическаястрокакаталога".repeat(12);
        for (int i = 0; i < 512; i++) {
            StringBuilder row = new StringBuilder("{\"row\":").append(i);
            for (int component = 0; component < 12; component++) {
                row.append(",\"component").append(component).append("\":\"").append(cyrillic).append('"');
            }
            rows.add(row.append('}').toString());
        }
        List<CatalogChunks.Range> ranges = CatalogChunks.partition(rows, 32, 262144);
        int expectedFrom = 0;
        int visited = 0;
        for (CatalogChunks.Range range : ranges) {
            check("large catalog ranges are contiguous", range.from() == expectedFrom && range.to() > range.from());
            int bytes = 32;
            for (int i = range.from(); i < range.to(); i++) {
                bytes += rows.get(i).getBytes(StandardCharsets.UTF_8).length;
                if (i > range.from()) bytes++;
                visited++;
            }
            check("large catalog chunk fits 262144 bytes", bytes <= 262144);
            expectedFrom = range.to();
        }
        check("large catalog visits exactly 512 rows without duplicates", visited == 512 && expectedFrom == 512);
    }

    private static void expectIllegalArgument(String label, Runnable call) {
        checks++;
        try {
            call.run();
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {
            // Expected rejection.
        }
    }

    private static void check(String label, boolean result) {
        checks++;
        if (!result) throw new AssertionError(label);
    }
}
