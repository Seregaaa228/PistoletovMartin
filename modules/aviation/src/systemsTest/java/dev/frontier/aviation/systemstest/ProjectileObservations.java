package dev.frontier.aviation.systemstest;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

final class ProjectileObservations {
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private ProjectileObservations() {}

    static void observed(Entity entity) {
        if (entity.getServer() == null || !FreshSystemsProfile.authorized(entity.getServer())) return;
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id != null) COUNTS.merge(id.toString(), 1, Integer::sum);
    }

    static int count(String type) { return COUNTS.getOrDefault(type, 0); }
    static void clear() { COUNTS.clear(); }
}
