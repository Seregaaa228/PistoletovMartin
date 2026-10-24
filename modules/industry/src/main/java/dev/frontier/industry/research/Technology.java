package dev.frontier.industry.research;

import java.util.List;
public enum Technology {
    SMALL_ARMS("small_arms", "Стрелковое вооружение", 90, 4, 4),
    RECON("recon", "Разведывательные БПЛА", 90, 4, 8),
    STRIKE_DRONES("strike_drones", "Ударные БПЛА", 150, 8, 8, "recon"),
    ARMOR("armor", "Бронетехника", 180, 8, 8, "small_arms"),
    AIR_DEFENSE("air_defense", "Базовое ПВО", 180, 8, 12, "recon"),
    MISSILES("missiles", "Ракетные технологии", 180, 12, 12, "strike_drones"),
    AVIATION("aviation", "Авиация", 240, 12, 16, "armor", "missiles"),
    ADVANCED_AA("advanced_aa", "Продвинутое ПВО", 240, 16, 16, "air_defense", "missiles");
    public final String id, title;
    public final int seconds, emeralds, redstone;
    public final List<String> prerequisites;
    Technology(String id, String title, int seconds, int emeralds, int redstone, String... prerequisites) {
        this.id=id;this.title=title;this.seconds=seconds;this.emeralds=emeralds;this.redstone=redstone;
        this.prerequisites=List.of(prerequisites);
    }
    public int ticks() { return seconds * 20; }
    public static Technology byId(String id) { for (var t:values()) if(t.id.equals(id))return t;return null; }
}
