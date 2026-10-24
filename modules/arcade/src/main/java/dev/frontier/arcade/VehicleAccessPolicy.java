package dev.frontier.arcade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * Acquisition policy shared by creative, JEI, and Industry purchasing.
 * These predicates never unregister, remove, or modify an existing entity.
 */
public final class VehicleAccessPolicy {
    /** Paths are kept public for audits and compatibility with the smoke harness. */
    public static final Set<String> BLOCKED_SBW_PATHS = Set.of(
        "m_1a_2", "t_90a", "ztz_99a", "yx_100", "prism_tank",
        "fh_77bw", "plz_05", "lav_150",
        "sodayo_pick_up", "sodayo_pick_up_hmg", "sodayo_pick_up_tow",
        "sodayo_pick_up_rocket", "truck", "wheel_chair"
    );

    /** Mods intentionally removed from the pack; fallback if they are reinstalled. */
    public static final Set<String> REMOVED_MODS = Set.of("trajanstanks", "simpleplanes");

    private VehicleAccessPolicy() {}

    public static boolean isBlocked(String entityId) {
        if (entityId == null || entityId.isEmpty()) return false;
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        return id != null && isBlocked(id);
    }

    public static boolean isBlocked(ResourceLocation entityId) {
        return entityId != null && (REMOVED_MODS.contains(entityId.getNamespace())
            || (entityId.getNamespace().equals("superbwarfare")
                && BLOCKED_SBW_PATHS.contains(entityId.getPath())));
    }

    public static boolean isBlocked(EntityType<?> type) {
        return type != null && isBlocked(EntityType.getKey(type));
    }

    public static boolean isBlocked(Entity entity) {
        return entity != null && isBlocked(entity.getType());
    }

    public static boolean isBlocked(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId != null && REMOVED_MODS.contains(itemId.getNamespace())) return true;
        if (stack.getItem() instanceof SpawnEggItem egg && isBlocked(egg.getType(stack.getTag()))) return true;

        CompoundTag root = stack.getTag();
        if (root == null) return false;
        // Native SBW containers use BlockEntityTag.EntityType. Recovered vehicles
        // also have BlockEntityTag.Entity.id; creative skins retain the same type.
        if (blockedPayload(root)) return true;
        if (root.contains("BlockEntityTag", Tag.TAG_COMPOUND)
                && blockedPayload(root.getCompound("BlockEntityTag"))) return true;
        return root.contains("EntityTag", Tag.TAG_COMPOUND)
            && blockedPayload(root.getCompound("EntityTag"));
    }

    private static boolean blockedPayload(CompoundTag tag) {
        if (isBlocked(tag.getString("EntityType")) || isBlocked(tag.getString("id"))) return true;
        return tag.contains("Entity", Tag.TAG_COMPOUND)
            && isBlocked(tag.getCompound("Entity").getString("id"));
    }
}
