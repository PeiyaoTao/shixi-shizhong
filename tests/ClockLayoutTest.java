package com.foldstandby.clock;

import java.util.List;

public final class ClockLayoutTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<ClockLayout.Area[]> wide = ClockLayout.arrange(1000, 360, null, 3);
        check(wide.size() == 1 && wide.get(0)[3].top == wide.get(0)[0].top, "ultrawide uses one row");
        List<ClockLayout.Area[]> square = ClockLayout.arrange(600, 580, null, 3);
        check(square.size() == 1 && square.get(0)[3].top > square.get(0)[0].top, "square uses a grid");
        List<ClockLayout.Area[]> main = ClockLayout.arrange(700, 340, null, 2);
        check(main.get(0)[0].width() > main.get(0)[1].width(), "main component retains priority");
        ClockLayout.Area hinge = new ClockLayout.Area(202, 0, 222, 240);
        List<ClockLayout.Area[]> folded = ClockLayout.arrange(620, 240, hinge, 3);
        check(folded.size() == 1 && folded.get(0)[0].right <= hinge.left
                && folded.get(0)[3].left >= hinge.right, "off-center hinge separates whole slots");
        List<ClockLayout.Area[]> tabletop = ClockLayout.arrange(720, 580,
                new ClockLayout.Area(0, 150, 720, 166), 1);
        check(tabletop.get(0)[0].bottom <= 150 && tabletop.get(0)[1].top >= 166, "tabletop uses both halves");
        ClockLayout.Area physical = new ClockLayout.Area(0, 65, 240, 72);
        ClockLayout.Area local = ClockLayout.landscapeFold(physical, 240, 620);
        check(local.left == 65 && local.right == 72 && local.top == 0 && local.bottom == 240,
                "portrait-only window rotates the hinge to the landscape canvas");
        check(ClockLayout.landscapeFold(hinge, 620, 240) == hinge, "landscape window keeps its hinge");
        for (int width : new int[]{120, 290, 360, 540, 1200}) {
            for (int height : new int[]{90, 160, 330, 570, 900}) {
                List<ClockLayout.Area[]> pages = ClockLayout.arrange(width, height, null, 3);
                int nextSlot = 0;
                for (ClockLayout.Area[] page : pages) {
                    for (int slot = 0; slot < page.length; slot++) {
                        ClockLayout.Area area = page[slot];
                        if (area == null) continue;
                        check(slot == nextSlot++, "paging retains scene order");
                        check(area.left >= 0 && area.top >= 0 && area.right <= width && area.bottom <= height
                                && area.width() > 0 && area.height() > 0, "all slots remain inside the viewport");
                    }
                }
                check(nextSlot == 4, "every configured slot remains reachable");
            }
        }
        check(ClockLayout.arrange(320, 140, null, 3).size() == 2, "tiny view uses temporary subpages");
        System.out.println("PASS: grid, wide and folded layouts, fixed landscape fallback, and 25 bounded viewport sizes");
    }
}
