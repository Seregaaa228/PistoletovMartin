package dev.frontier.industry.client;

import dev.frontier.industry.CatalogChunks;
import java.util.ArrayList;
import java.util.List;

/** Standalone Java17 checks for atomic client-side catalog assembly. */
public final class CatalogChunkAssemblyTest {
    private static int checks;

    public static void main(String[] args) {
        checkOutOfOrderAssembly();
        checkDuplicateReplacement();
        checkHashChange();
        checkTotalPartsChange();
        checkInvalidParts();
        checkCompleteSnapshot();
        checkMatchingRefreshPreservesCatalog();
        checkPartitionRoundTrips();
        checkClear();
        System.out.println("CatalogChunkAssemblyTest: " + checks + " checks passed");
    }

    private static void checkOutOfOrderAssembly() {
        var assembly = new CatalogChunkAssembly();
        check("initial complete hash is empty", assembly.completeHash() == null);
        check("initial complete rows are empty", assembly.completeRows().isEmpty());
        check("last part alone is incomplete", assembly.accept("hash", 2, 3, List.of("c")) == null);
        check("first part is still incomplete", assembly.accept("hash", 0, 3, List.of("a")) == null);
        check("complete rows follow part order", assembly.accept("hash", 1, 3, List.of("b"))
            .equals(List.of("a", "b", "c")));
    }

    private static void checkDuplicateReplacement() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("hash", 0, 2, List.of("old"));
        check("duplicate part is not complete", assembly.accept("hash", 0, 2, List.of("new")) == null);
        check("duplicate replaces rows within the same set", assembly.accept("hash", 1, 2, List.of("tail"))
            .equals(List.of("new", "tail")));
    }

    private static void checkHashChange() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("old", 0, 2, List.of("old-head"));
        check("new hash starts an incomplete set", assembly.accept("new", 0, 2, List.of("new-head")) == null);
        check("old hash cannot mix into the new set", assembly.accept("old", 1, 2, List.of("old-tail")) == null);
        check("stale old set does not become complete", assembly.completeHash() == null);
        assembly.accept("new", 0, 2, List.of("new-head"));
        check("new set completes without old rows", assembly.accept("new", 1, 2, List.of("new-tail"))
            .equals(List.of("new-head", "new-tail")));
    }

    private static void checkTotalPartsChange() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("hash", 0, 2, List.of("discarded"));
        check("new total starts an incomplete set", assembly.accept("hash", 0, 3, List.of("a")) == null);
        check("old total cannot complete the new set", assembly.accept("hash", 1, 2, List.of("stale")) == null);
        check("old total did not publish a mixed set", assembly.completeHash() == null);
        assembly.accept("hash", 0, 3, List.of("a"));
        assembly.accept("hash", 1, 3, List.of("b"));
        check("changed total completes only its own rows", assembly.accept("hash", 2, 3, List.of("c"))
            .equals(List.of("a", "b", "c")));
    }

    private static void checkInvalidParts() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("hash", 0, 2, List.of("head"));
        expectIllegalArgument("negative part", () -> assembly.accept("hash", -1, 2, List.of()));
        expectIllegalArgument("part equal to total", () -> assembly.accept("hash", 2, 2, List.of()));
        expectIllegalArgument("zero total", () -> assembly.accept("hash", 0, 0, List.of()));
        expectIllegalArgument("negative total", () -> assembly.accept("hash", 0, -1, List.of()));
        expectIllegalArgument("total above protocol maximum", () -> assembly.accept("hash", 0, 513, List.of()));
        check("invalid parts do not erase a pending set", assembly.accept("hash", 1, 2, List.of("tail"))
            .equals(List.of("head", "tail")));
        check("protocol maximum is accepted", assembly.accept("hash", 511, 512, List.of("last")) == null);
    }

    private static void checkCompleteSnapshot() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("old", 0, 1, List.of("old-row"));
        check("new incomplete set preserves complete hash", assembly.accept("new", 0, 2, List.of("new-head")) == null
            && assembly.completeHash().equals("old"));
        check("new incomplete set preserves complete rows", assembly.completeRows().equals(List.of("old-row")));
        check("new complete set atomically replaces snapshot", assembly.accept("new", 1, 2, List.of("new-tail"))
            .equals(List.of("new-head", "new-tail"))
            && assembly.completeHash().equals("new")
            && assembly.completeRows().equals(List.of("new-head", "new-tail")));
    }

    private static void checkMatchingRefreshPreservesCatalog() {
        var assembly = new CatalogChunkAssembly();
        List<String> completeRows = List.of("first-row", "second-row");
        List<String> applied = assembly.accept("hash", 0, 1, completeRows);
        applied = applyView(assembly, "hash", false, List.of());
        check("matching refresh keeps the complete applied catalog", completeRows.equals(applied));
        check("matching refresh keeps cached rows for later refreshes", completeRows.equals(assembly.completeRows()));
        applied = applyView(assembly, "hash", false, List.of());
        check("repeated matching refresh remains unchanged", completeRows.equals(applied)
            && completeRows.equals(assembly.completeRows()) && "hash".equals(assembly.completeHash()));
        check("unknown catalog-less hash is rejected without changing cached rows",
            applyView(assembly, "unknown", false, List.of()) == null && completeRows.equals(assembly.completeRows()));
        check("changed catalog-less hash is rejected without changing applied state",
            applyView(assembly, "changed", false, List.of()) == null && completeRows.equals(applied)
                && "hash".equals(assembly.completeHash()));
        var empty = new CatalogChunkAssembly();
        check("catalog-less response cannot use an unknown hash without a complete snapshot",
            applyView(empty, "hash", false, List.of()) == null && empty.completeHash() == null);
    }

    private static List<String> applyView(CatalogChunkAssembly assembly, String catalogHash,
                                          boolean catalogPresent, List<String> rows) {
        if (!catalogPresent) return CatalogChunks.hasMatchingHash(catalogHash, assembly.completeHash())
            ? assembly.completeRows() : null;
        return assembly.accept(catalogHash, 0, 1, rows);
    }

    private static void checkPartitionRoundTrips() {
        check("empty catalog uses one part", checkRoundTrip("empty catalog", List.of(), 2) == 1);
        check("small catalog uses one part", checkRoundTrip("single-part catalog",
            List.of("{\"id\":1}", "{\"id\":2}"), 2) == 1);
        check("maximum catalog requires multiple parts", checkRoundTrip("maximum catalog", largeCatalogRows(), 32) > 1);
    }

    private static int checkRoundTrip(String label, List<String> rows, int emptyJsonBytes) {
        String hash = CatalogChunks.sha256("[" + String.join(",", rows) + "]");
        List<CatalogChunks.Range> ranges = CatalogChunks.partition(rows, emptyJsonBytes, 262144);
        var assembly = new CatalogChunkAssembly();
        List<String> assembled = null;
        for (int part = 0; part < ranges.size(); part++) {
            CatalogChunks.Range range = ranges.get(part);
            assembled = assembly.accept(hash, part, ranges.size(), rows.subList(range.from(), range.to()));
        }
        check(label + " completes through partition and assembly", assembled != null);
        check(label + " reconstructs exact ordered rows", rows.equals(assembled) && rows.equals(assembly.completeRows()));
        check(label + " retains the complete catalog hash", hash.equals(assembly.completeHash()));
        return ranges.size();
    }

    private static List<String> largeCatalogRows() {
        List<String> rows = new ArrayList<>();
        String cyrillic = "Длиннаякириллическаястрокакаталога".repeat(12);
        for (int i = 0; i < 512; i++) {
            StringBuilder row = new StringBuilder("{\"row\":").append(i);
            for (int component = 0; component < 12; component++) {
                row.append(",\"component").append(component).append("\":\"").append(cyrillic).append('"');
            }
            rows.add(row.append('}').toString());
        }
        return rows;
    }

    private static void checkClear() {
        var assembly = new CatalogChunkAssembly();
        assembly.accept("old", 0, 1, List.of("complete"));
        assembly.accept("new", 0, 2, List.of("pending"));
        assembly.clear();
        check("clear removes complete hash", assembly.completeHash() == null);
        check("clear removes complete rows", assembly.completeRows().isEmpty());
        check("clear removes pending rows", assembly.accept("new", 1, 2, List.of("tail")) == null
            && assembly.completeHash() == null && assembly.completeRows().isEmpty());
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
