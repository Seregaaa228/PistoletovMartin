package dev.frontier.industry.client.ui;

public final class UiPageTest {
    public static void main(String[] args) {
        check(UiPage.of(3, 0, 4).equals(new UiPage(0, 1, 0, 0)), "empty refresh never addresses a removed row");
        check(UiPage.of(12, 7, 4).equals(new UiPage(1, 2, 4, 7)), "shrinking server list clamps the active page");
        check(UiPage.of(2, 17, 6).equals(new UiPage(2, 3, 12, 17)), "all rows on last page remain reachable");
        check(UiPage.of(-1, 7, 4).equals(new UiPage(0, 2, 0, 4)), "back navigation cannot move before the first page");
        check(UiPage.of(2, 7, 0).equals(new UiPage(2, 7, 2, 3)), "small viewport always exposes at least one row");
        check(UiPage.of(Integer.MAX_VALUE, Integer.MAX_VALUE, 6).to() == Integer.MAX_VALUE, "bounded payload arithmetic cannot overflow");
        System.out.println("UiPageTest: 6 checks passed");
    }
    private static void check(boolean valid, String reason) { if (!valid) throw new AssertionError(reason); }
}
