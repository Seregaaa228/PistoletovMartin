package dev.frontier.industry.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frontier.industry.OperationsNet;
import dev.frontier.industry.OperationsPayload;
import java.io.IOException;

/** Client game thread only; never publish a partially received snapshot or split UTF-8 text. */
public final class OperationsViewBuffer {
    private static long latest, completed;
    private static byte[] bytes;
    private static int nextPart;
    private OperationsViewBuffer() {}
    public static void clear() { latest = completed = 0; bytes = null; nextPart = 0; }
    public static void accept(OperationsNet.View part) {
        if (part.id() <= completed || part.id() < latest) return;
        if (part.id() > latest) {
            latest = part.id(); nextPart = 0; bytes = new byte[part.totalBytes()];
        }
        if (bytes == null || bytes.length != part.totalBytes() || part.part() != nextPart) return;
        System.arraycopy(part.data(), 0, bytes, nextPart * OperationsPayload.PART_BYTES, part.data().length);
        if (++nextPart != part.parts()) return;
        byte[] complete = bytes; bytes = null; completed = latest;
        try {
            OperationsScreen.receive(JsonParser.parseString(OperationsPayload.decode(complete)).getAsJsonObject(), part.open(), part.camera());
        } catch (IOException | RuntimeException invalid) {
            JsonObject error = new JsonObject(); error.addProperty("transportError", true);
            error.addProperty("message", "Не удалось получить данные КПК. Повтори открытие; предыдущие данные сохранены.");
            OperationsScreen.receive(error, part.open(), false);
        } finally { OperationsNet.received(part.id()); }
    }
}
