package dev.frontier.industry.client;

import java.util.List;

public final class FactoryUiStateTest {
    public static void main(String[] args) {
        var ui = new FactoryUiState();
        var ids = List.of("tacz:ak47", "tacz:m4", "minecraft:shield", "ammo/tacz:762", "superbwarfare:bradley", "simpleplanes:plane");
        ui.reconcile(ids); check(ui.selected().equals(ids.get(0)), "first product is usable without typing an ID");
        ui.select(ids.get(4)); ui.scroll(100, ids.size()); check(ui.offset() == 2, "last page is full");
        ui.reconcile(ids); check(ui.selected().equals(ids.get(4)), "server refresh preserves selection");
        ui.reconcile(List.of(ids.get(1))); check(ui.offset() == 0 && ui.selected().equals(ids.get(1)), "search removes stale selection and offset");
        ui.reconcile(List.of()); check(ui.selected().isEmpty() && ui.offset() == 0, "empty search cannot submit a stale product");
        ui.quantity(0); check(ui.quantity() == 1, "minimum quantity");
        ui.quantity(1000); check(ui.quantity() == 64, "maximum quantity matches server packet bound");
        ui.quantity(8); check(ui.quantity() == 8, "preset quantity");
        ui.scroll(-100, 6); check(ui.offset() == 0, "scroll cannot go negative");
        System.out.println("FactoryUiStateTest: 9 checks passed");
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
