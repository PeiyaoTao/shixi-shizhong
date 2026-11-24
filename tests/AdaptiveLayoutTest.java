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
        float component = AdaptiveLayout.COMPONENT_PREVIEW_ASPECT;
        float page = AdaptiveLayout.PAGE_PREVIEW_ASPECT;
        check(AdaptiveLayout.galleryColumns(296, 700, 1, 10, false, component) == 1, "component choices use full-width previews on narrow screens");
        check(AdaptiveLayout.galleryColumns(797, 1088, 2, 10, false, component) == 1, "large text reduces component picker columns");
        check(AdaptiveLayout.galleryColumns(856, 320, 1, 10, true, component) == 3, "landscape component picker limits density");
        check(AdaptiveLayout.galleryColumns(569, 440, 1, 6, true, component) == 1, "landscape stacks when two full previews cannot fit");
        check(AdaptiveLayout.galleryColumns(570, 440, 1, 6, true, component) == 2, "landscape adds a column when two 280dp previews fit");
        check(AdaptiveLayout.galleryColumns(856, 320, 1, 6, true, component) == 3, "three usable previews fit in short landscape");
        check(AdaptiveLayout.galleryColumns(1064, 821, 1, 6, true, component) == 3, "expanded landscape fits three large previews");
        check(AdaptiveLayout.galleryColumns(1064, 821, 2, 6, true, component) == 2, "large landscape text leaves room for style names");
        check(AdaptiveLayout.galleryWidth(852, 554, 1, 2, true, component) == 852, "large previews use the available landscape width");
        check(AdaptiveLayout.galleryWidth(856, 320, 1, 3, true, component) == 809, "short landscape leaves room for the toolbar and captions");
        check(AdaptiveLayout.galleryWidth(1600, 900, 1, 3, true, component) == 1340, "expansive landscape windows cap preview size");

        check(AdaptiveLayout.galleryColumns(296, 700, 1, 6, false, component) == 1, "narrow portrait style picker stacks previews");
        check(AdaptiveLayout.galleryWidth(296, 700, 1, 1, false, component) == 296, "narrow portrait previews use all available width");
        check(AdaptiveLayout.galleryColumns(530, 876, 1, 5, false, component) == 1, "cover portrait uses a single column");
        check(AdaptiveLayout.galleryWidth(530, 876, 1, 1, false, component) == 530, "cover portrait previews span the content width");
        check(AdaptiveLayout.galleryColumns(797, 1088, 1, 6, false, component) == 2, "inner portrait fits two large previews");
        check(AdaptiveLayout.galleryWidth(797, 1088, 1, 2, false, component) == 797, "inner portrait gives each preview about half the width");
        check(AdaptiveLayout.galleryColumns(1056, 1440, 1, 6, false, component) == 2, "expanded portrait preserves large previews in two columns");
        check(AdaptiveLayout.galleryColumns(797, 1088, 2, 6, false, component) == 1, "inner portrait stacks at large text sizes");
        for (int[] window : new int[][]{{240, 400}, {360, 760}, {412, 915}, {480, 760}}) {
            int contentWidth = window[0] - 24;
            for (float fontScale : new float[]{1f, 2f}) {
                int columns = AdaptiveLayout.galleryColumns(contentWidth, window[1], fontScale, 6, false, component);
                check(columns == 1, "phone and split-screen portrait keep one column");
                check(AdaptiveLayout.galleryWidth(contentWidth, window[1], fontScale, columns, false, component) == contentWidth,
                        "phone and split-screen portrait previews fill the content width");
            }
        }
        check(AdaptiveLayout.galleryWidth(1056, 1440, 1, 2, false, component) == 1056, "tablet portrait uses the available width");
        check(AdaptiveLayout.galleryColumns(456, 320, 1, 4, true, page) == 1, "narrow landscape stacks the page layouts");
        check(AdaptiveLayout.galleryColumns(852, 320, 1, 4, true, page) == 2, "short wide windows compare two page layouts");
        check(AdaptiveLayout.galleryWidth(852, 320, 1, 2, true, page) == 682, "page preview ratio determines its height budget");
        check(AdaptiveLayout.galleryWidth(530, 876, 1, 1, false, page) == 530, "cover portrait page previews span the content width");
        System.out.println("PASS: adaptive panes, threshold continuity, preview budgets and keyboard sizes");
    }
}
