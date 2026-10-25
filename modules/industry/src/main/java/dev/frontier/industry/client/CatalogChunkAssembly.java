package dev.frontier.industry.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collects one catalog transfer without exposing its incomplete rows. */
public final class CatalogChunkAssembly {
    private String pendingHash;
    private int pendingTotal;
    private final Map<Integer, List<String>> pendingParts = new HashMap<>();
    private String completeHash;
    private List<String> completeRows = List.of();

    public List<String> accept(String catalogHash, int part, int totalParts, List<String> rows) {
        if (totalParts < 1 || totalParts > 512 || part < 0 || part >= totalParts) {
            throw new IllegalArgumentException("Invalid catalog chunk index");
        }
        if (!Objects.equals(pendingHash, catalogHash) || pendingTotal != totalParts) {
            pendingHash = catalogHash;
            pendingTotal = totalParts;
            pendingParts.clear();
        }
        pendingParts.put(part, List.copyOf(rows));
        if (pendingParts.size() != totalParts) return null;

        List<String> assembled = new ArrayList<>();
        for (int index = 0; index < totalParts; index++) {
            assembled.addAll(pendingParts.get(index));
        }
        completeHash = catalogHash;
        completeRows = List.copyOf(assembled);
        clearPending();
        return completeRows;
    }

    public String completeHash() {
        return completeHash;
    }

    public List<String> completeRows() {
        return completeRows;
    }

    public void clear() {
        clearPending();
        completeHash = null;
        completeRows = List.of();
    }

    private void clearPending() {
        pendingHash = null;
        pendingTotal = 0;
        pendingParts.clear();
    }
}
