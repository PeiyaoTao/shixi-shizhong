package com.foldstandby.clock;

public class CarouselDepthTest {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        check(CarouselDepth.scale(0)==1&&CarouselDepth.alpha(0)==1,"center remains full size and opacity");
        float previousScale=1,previousAlpha=1,previousAngle=0;
        for(float distance:new float[]{.25f,.5f,1,2,3,5,10}){
            float scale=CarouselDepth.scale(distance),alpha=CarouselDepth.alpha(distance),angle=Math.abs(CarouselDepth.rotation(distance));
            check(scale>0&&scale<previousScale,"every farther layer gets smaller");
            check(alpha>=0&&alpha<previousAlpha,"every farther layer gets dimmer");
            check(angle>previousAngle&&angle<=48,"rotation increases smoothly within a bound");
            check(Math.abs(scale-CarouselDepth.scale(-distance))<.00001f,"scale is symmetric");
            check(Math.abs(CarouselDepth.rotation(distance)+CarouselDepth.rotation(-distance))<.00001f,"tilt is symmetric");
            check(Math.abs(CarouselDepth.shift(distance))<.5f,"center shift stays bounded");
            previousScale=scale;previousAlpha=alpha;previousAngle=angle;
        }
        check(CarouselDepth.scale(2)!=CarouselDepth.scale(3),"remote pages are distinct");
        System.out.println("PASS: continuous carousel depth, symmetry and bounds");
    }
}
