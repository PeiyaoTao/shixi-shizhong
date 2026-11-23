package com.foldstandby.clock;
public class AdaptiveLayoutTest {
    private static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
    public static void main(String[] args){
        check(!new AdaptiveLayout(440,820,1).parallel,"narrow cover uses focused workspace");
        check(!new AdaptiveLayout(820,1088,1).parallel,"inner portrait shares the cover portrait structure");
        check(new AdaptiveLayout(1088,820,1).parallel,"landscape inner");
        check(new AdaptiveLayout(876,500,1).parallel,"landscape cover");
        check(!new AdaptiveLayout(700,900,2).parallel,"large text needs wider inspector");
        check(new AdaptiveLayout(1600,1000,1).left<=520,"preview pane has a width cap");
        check(!new AdaptiveLayout(560,700,1).parallel,"narrow window keeps one pane");
        check(new AdaptiveLayout(580,400,1).parallel,"usable minimum parallel panes");
        check(new AdaptiveLayout(679,400,1).parallel==new AdaptiveLayout(680,400,1).parallel,"no unused rail threshold jump");
        check(!new AdaptiveLayout(820,210,1).parallel,"extremely short windows prioritize content");
        for(int height:new int[]{240,320,500,820})for(float font:new float[]{1,1.3f,2.08f}){
            int preview=AdaptiveLayout.previewHeight(400,height,false,font,2.05f);
            check(preview>=76&&preview<=height,"preview fits available height");
            check(AdaptiveLayout.previewHeight(400,height,true,font,2.05f)<=110,"keyboard preview remains compact");
        }
        check(AdaptiveLayout.previewHeight(400,820,false,1,2.05f)>340,"portrait budget preserves large page and neighbor edges");
        check(AdaptiveLayout.previewHeight(820,1088,false,1,1088f/820)>630,"wide portrait grants a nearly square preview more height");
        check(!AdaptiveLayout.settingsUseTwoPanes(false,820,1088,1),"expanded portrait settings keep a single pane");
        check(AdaptiveLayout.settingsUseTwoPanes(true,820,400,1),"wide landscape settings show category and detail");
        check(AdaptiveLayout.settingsUseTwoPanes(true,560,350,1),"Fold8 landscape widths supported in 0.5.3 still use two panes");
        check(!AdaptiveLayout.settingsUseTwoPanes(true,549,350,1),"a narrow split-screen window can use a single pane");
        check(AdaptiveLayout.settingsUseTwoPanes(true,1000,236,1),"a short safe half can still show scrollable side-by-side panes");
        check(!AdaptiveLayout.settingsUseTwoPanes(true,820,400,2.08f),"large type reserves width for settings");
        check(!AdaptiveLayout.settingsUseTwoPanes(true,1000,229,1),"very short windows keep settings scrollable in one pane");
        check(AdaptiveLayout.settingsCanSpanHinge(390,440,480,1.3f),"a vertical hinge can separate settings panes");
        check(!AdaptiveLayout.settingsCanSpanHinge(390,280,480,1.3f),"a detail pane must fit beside the hinge");
        check(AdaptiveLayout.galleryColumns(296, 700, 1, 6) == 2, "two styles fit on a 320dp cover display");
        check(AdaptiveLayout.galleryColumns(296, 700, 2, 6) == 1, "large text reduces gallery columns");
        check(AdaptiveLayout.galleryColumns(856, 320, 1, 5) == 5, "short landscape can show all five styles in one row");
        check(AdaptiveLayout.galleryColumns(856, 320, 1, 6) == 3, "six styles balance across two rows when one is too narrow");
        check(AdaptiveLayout.galleryWidth(1400, 900, 3) == 590, "wide windows cap card width");
        check(AdaptiveLayout.galleryWidth(856, 320, 5) == 740, "short windows use smaller cards");
        check(AdaptiveLayout.galleryWidth(108, 520, 1) == 108, "narrow windows constrain the card");
        System.out.println("PASS: adaptive panes, threshold continuity, preview budgets and keyboard sizes");
    }
}
