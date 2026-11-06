package dev.frontier.aviation.weapons;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Ordnance a pylon of a glide-bomb-capable airframe can be loaded with from the
 * DeepStrike tablet. Any pylon takes any entry; a new weapon type is one more
 * entry in {@link #ALL} plus its load/launch handling.
 *
 * DeepStrike's LOAD packet has no weapon field, so the tablet sends the chosen id
 * in the packet's otherwise empty name. {@link #setPendingLoadType} is called by
 * GlideBombOrdnanceRouting immediately before DeepStrike's dispatcher calls
 * {@code loadStore}, and GlideBombPylon consumes it inside that same call on the
 * server thread.
 */
public final class PylonOrdnance {
    public static final String KAB_500 = "kab_500";
    public static final String KAB_1500 = "kab_1500";
    public static final String KAB_3000 = "kab_3000";
    public static final String STORM_SHADOW = "storm_shadow";
    public static final List<String> ALL = List.of(KAB_500, KAB_1500, KAB_3000, STORM_SHADOW);

    /**
     * A КАБ class: the SBW aerial bombs it is made of (held in the pylon as that stack,
     * so saves stay plain items) and its blast. КАБ-3000 is two large bombs.
     */
    public record Kab(String id, Supplier<Item> item, int count, float radius, float blastDamage, float hitDamage) {
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() == item.get() && stack.getCount() == count;
        }
    }

    public static final List<Kab> KABS = List.of(
        new Kab(KAB_500, () -> ModItems.MEDIUM_AERIAL_BOMB.get(), 1, 16, 260, 600),
        // Blocks destroyed grow ~ radius^2 (measured: КАБ-3000 r38 3109, r52 5885 blocks in
        // the tablet test), so x1.57 radius = x2.5 destruction.
        new Kab(KAB_1500, () -> ModItems.LARGE_AERIAL_BOMB.get(), 1, 41, 480, 1200),
        new Kab(KAB_3000, () -> ModItems.LARGE_AERIAL_BOMB.get(), 2, 60, 800, 2000));

    /** Index into {@link #KABS} of the КАБ id, or -1 (Storm Shadow, unknown). */
    public static int kabIndex(String id) {
        for (int i = 0; i < KABS.size(); i++) if (KABS.get(i).id().equals(id)) return i;
        return -1;
    }

    /** Index into {@link #KABS} of a pylon store, or -1 when it is not a КАБ. */
    public static int kabIndex(ItemStack stack) {
        for (int i = 0; i < KABS.size(); i++) if (KABS.get(i).matches(stack)) return i;
        return -1;
    }

    private static String pendingLoadType;
    // Client-side tablet choice per (vehicle entity id, pylon); display/intent only.
    private static final Map<Long, String> clientSelection = new HashMap<>();

    private PylonOrdnance() {}

    /** Null outside a tablet LOAD packet (e.g. DeepStrike's right-click loading). */
    public static void setPendingLoadType(String type) {
        pendingLoadType = type != null && ALL.contains(type) ? type : null;
    }

    public static String consumePendingLoadType() {
        String type = pendingLoadType;
        pendingLoadType = null;
        return type;
    }

    public static String clientSelected(int vehicleId, int slot) {
        return clientSelection.getOrDefault(clientKey(vehicleId, slot), KAB_500);
    }

    public static void setClientSelected(int vehicleId, int slot, String type) {
        clientSelection.put(clientKey(vehicleId, slot), type);
    }

    private static long clientKey(int vehicleId, int slot) {
        return ((long) vehicleId << 8) | (slot & 0xFF);
    }
}
