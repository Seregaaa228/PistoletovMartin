package dev.frontier.ifv.catalog;
public class CatalogRuleCheck {
    static void expect(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (String p : new String[]{"drone_factory", "drone_factory_deployer", "missile_factory_deployer", "power_station", "substation", "research_center", "detail_press", "structure_floor"})
            expect(CatalogRules.isFactory("frontier_industry", p), p);
        expect(CatalogRules.isFactory("deepstrike", "missile_assembly_station"), "assembly station");
        expect(!CatalogRules.isFactory("frontier_industry", "ballistic_missile"), "missile is not a factory");
        expect(CatalogRules.isArmor("superbwarfare", "ru_chest_6b43", "", false), "body armor");
        expect(CatalogRules.isArmor("superbwarfare", "armor_plate", "", false), "armor plate");
        expect(CatalogRules.isArmor("tacteq", "bodyrazgruz", "", false), "vest");
        expect(CatalogRules.isArmor("tacteq", "bronieplita", "", false), "plate");
        expect(!CatalogRules.isArmor("tacteq", "foodmre", "", false), "food is not armor");
        expect(!CatalogRules.isArmor("superbwarfare", "steel_plate", "", false), "metal plate is not armor");
        System.out.println("Factory and armor classification checks passed");
    }
}
