package dev.frontier.ifv.catalog;

import java.util.Set;

/** Stable IDs for acquisition categories; independent of translations and creative-tab order. */
public final class CatalogRules {
    private static final Set<String> INDUSTRIAL_SITES = Set.of(
            "power_station", "substation", "drone_factory", "vehicle_factory",
            "equipment_factory", "missile_factory", "research_center");
    private CatalogRules() {}

    public static boolean isFactory(String namespace, String path) {
        if (namespace.equals("frontier_industry")) {
            String id = path.endsWith("_deployer") ? path.substring(0, path.length() - "_deployer".length()) : path;
            return INDUSTRIAL_SITES.contains(id) || path.startsWith("structure_") || path.startsWith("detail_");
        }
        return namespace.equals("deepstrike") && path.equals("missile_assembly_station")
                || namespace.equals("superbwarfare") && path.equals("vehicle_assembling_table");
    }

    public static boolean isArmor(String namespace, String path, String itemClass, boolean armorItem) {
        if (armorItem || itemClass.contains("armoritem") || itemClass.contains("armouritem")) return true;
        if (namespace.equals("superbwarfare") && (path.startsWith("ru_chest_") || path.startsWith("us_chest_"))) return true;
        if (namespace.equals("tacteq") && has(path, "bronieplita", "bodyrazgruz", "nashlem", "zabralo", "balaclava", "balcklava", "neck", "plecho", "pah", "podvissistem")) return true;
        return has(path, "armor", "armour", "helmet", "vest", "chestplate", "leggings", "boots", "exoskeleton", "plate_carrier", "body_armor");
    }

    private static boolean has(String path, String... words) {
        for (String word : words) if (path.contains(word)) return true;
        return false;
    }
}
