package dev.frontier.industry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded transport only: the complete PDA projection, including catalogues, is preserved. */
public final class OperationsPayload {
    public static final int PART_BYTES = 32_768, MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_RAW_BYTES = 16 * 1024 * 1024;
    private OperationsPayload() {}

    public static byte[] encode(String json) throws IOException {
        if (json.length() > MAX_RAW_BYTES) throw new IOException("PDA snapshot exceeds limit");
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_RAW_BYTES) throw new IOException("PDA snapshot exceeds limit");
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        if (bytes.size() > MAX_BYTES) throw new IOException("PDA transfer exceeds limit");
        return bytes.toByteArray();
    }

    public static String decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("PDA transfer exceeds limit");
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] raw = gzip.readNBytes(MAX_RAW_BYTES + 1);
            if (raw.length > MAX_RAW_BYTES) throw new IOException("PDA snapshot exceeds limit");
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
