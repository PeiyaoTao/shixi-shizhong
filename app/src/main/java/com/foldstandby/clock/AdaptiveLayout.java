package com.foldstandby.clock;

/** Layout policy depends on available space, including height and text scaling. */
final class AdaptiveLayout {
    static final int GALLERY_GAP = 10;
    static final float COMPONENT_PREVIEW_ASPECT = 5f / 4f;
    static final float PAGE_PREVIEW_ASPECT = 8f / 5f;

    final boolean parallel;
    final int rail,left;
    AdaptiveLayout(int width,int height,float fontScale){
        int detailMin=(int)(290*Math.max(1f,fontScale*.85f));
        rail=0;
        parallel=width>height&&width>=260+detailMin+22&&height>=230;
        left=parallel?Math.min(width-detailMin-22,Math.min(520,Math.max(260,(int)(width*.44f)))):width;
    }
    static boolean settingsUseTwoPanes(boolean landscape, int width, int height, float fontScale) {
        int minWidth = Math.round(550 * Math.max(1f, fontScale * .8f));
        return landscape && width >= minWidth && height >= 230;
    }
    static boolean settingsCanSpanHinge(int categoriesWidth, int detailWidth, int height, float fontScale) {
        float textFactor = Math.max(1f, fontScale * .8f);
        return categoriesWidth >= Math.round(152 * textFactor)
                && detailWidth >= Math.round(300 * textFactor) && height >= 230;
    }
    static int galleryColumns(int width, int height, float fontScale, int optionCount,
                              boolean landscape, float aspect) {
        float textFactor = Math.max(1f, fontScale * .8f);
        int preferredWidth = Math.round((landscape ? 280 : 320) * textFactor);
        int minWidth = Math.min(preferredWidth, galleryPreviewWidth(height, fontScale, landscape, aspect));
        int fittingColumns = Math.max(1, Math.min(optionCount, (width + GALLERY_GAP) / (minWidth + GALLERY_GAP)));
        if (!landscape) fittingColumns = Math.min(2, fittingColumns);
        // Keep the same row count while distributing options more evenly.
        int rows = (optionCount + fittingColumns - 1) / fittingColumns;
        return (optionCount + rows - 1) / rows;
    }
    static int galleryWidth(int availableWidth, int height, float fontScale, int columns,
                            boolean landscape, float aspect) {
        int maxWidth = galleryPreviewWidth(height, fontScale, landscape, aspect);
        return Math.min(availableWidth, columns * maxWidth + (columns - 1) * GALLERY_GAP);
    }

    private static int galleryPreviewWidth(int height, float fontScale, boolean landscape, float aspect) {
        // Leave room for the toolbar, scroll padding, and caption in the first row.
        int controlsHeight = 110 + Math.round(60 * Math.max(0f, fontScale - 1f));
        int width = Math.round((height - controlsHeight) * aspect);
        return Math.max(224, Math.min(landscape ? 440 : 560, width));
    }
    static int previewHeight(int width,int height,boolean keyboard,float fontScale,float aspect){
        int reserve=Math.round(210*Math.max(1f,fontScale*.75f));
        int carouselChrome = 200;
        int fitted = Math.round(Math.max(120, width - 36) / aspect) + carouselChrome;
        int readablePreview = Math.round(width * .74f / aspect) + carouselChrome;
        int proportionalCap = Math.max(Math.round(height * .56f),
                Math.min(readablePreview, Math.round(height * .64f)));
        int target = keyboard ? 110 : Math.min(fitted, proportionalCap);
        return Math.max(76,Math.min(target,Math.max(76,height-reserve)));
    }
}
