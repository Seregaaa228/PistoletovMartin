package dev.frontier.industry.client.ui;

/** Regression: a factory tooltip at its right edge must not enter JEI's item panel. */
public final class TooltipBoundsTest {
    public static void main(String[] args) {
        TooltipBounds factory = new TooltipBounds(142, 46, 356, 268);
        var observed = factory.position(359, 187, 176, 63);
        check(observed.x() < 359, "observed right-side tooltip moves to the left of the pointer");
        int checks = 1;
        for (TooltipBounds bounds : new TooltipBounds[] {factory, new TooltipBounds(-18, -14, 356, 268),
                new TooltipBounds(40, 9, 560, 342), new TooltipBounds(-120, -51, 560, 342)}) {
            for (int[] pointer : new int[][] {{bounds.left(), bounds.top()}, {bounds.left() + bounds.width(), bounds.top()},
                    {bounds.left(), bounds.top() + bounds.height()}, {bounds.left() + bounds.width(), bounds.top() + bounds.height()}}) {
                for (int[] size : new int[][] {{176, 63}, {bounds.wrapWidth(), 110}}) {
                    var point = bounds.position(pointer[0], pointer[1], size[0], size[1]);
                    check(point.x() >= bounds.left() + 6 && point.x() + size[0] <= bounds.left() + bounds.width() - 6,
                        "tooltip, including wrapped Russian text, stays within modal horizontally");
                    check(point.y() >= bounds.top() + 6 && point.y() + size[1] <= bounds.top() + bounds.height() - 6,
                        "bottom tooltip remains within modal vertically");
                    checks += 2;
                }
            }
        }
        System.out.println("TooltipBoundsTest: " + checks + " checks passed");
    }
    private static void check(boolean valid, String reason) { if (!valid) throw new AssertionError(reason); }
}
