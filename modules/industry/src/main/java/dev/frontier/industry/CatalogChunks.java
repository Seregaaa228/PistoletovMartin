package dev.frontier.industry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** UTF-8 byte accounting for catalog JSON chunks. */
public final class CatalogChunks {
    public record Range(int from, int to) {}

    private CatalogChunks() {}

    public static List<Range> partition(List<String> rows, int emptyJsonBytes, int maxJsonBytes) {
        if (emptyJsonBytes < 0 || maxJsonBytes < emptyJsonBytes) {
            throw new IllegalArgumentException("Invalid catalog JSON byte budget");
        }
        if (rows.isEmpty()) return List.of(new Range(0, 0));

        List<Range> ranges = new ArrayList<>();
        int from = 0;
        long bytes = emptyJsonBytes;
        for (int i = 0; i < rows.size(); i++) {
            int rowBytes = utf8Bytes(rows.get(i));
            long firstRowBytes = emptyJsonBytes + (long) rowBytes;
            if (firstRowBytes > maxJsonBytes) {
                throw new IllegalArgumentException("Catalog row exceeds the JSON byte budget");
            }
            long candidateBytes = bytes + rowBytes + (i == from ? 0 : 1);
            if (candidateBytes > maxJsonBytes) {
                ranges.add(new Range(from, i));
                from = i;
                bytes = firstRowBytes;
            } else {
                bytes = candidateBytes;
            }
        }
        ranges.add(new Range(from, rows.size()));
        return List.copyOf(ranges);
    }

    public static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static int requireJsonBytes(String value, int maxJsonBytes) {
        int bytes = utf8Bytes(value);
        if (bytes > maxJsonBytes) throw new IllegalArgumentException("Catalog JSON exceeds the byte budget");
        return bytes;
    }

    public static boolean hasMatchingHash(String candidateHash, String completeHash) {
        return candidateHash != null && candidateHash.equals(completeHash);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
