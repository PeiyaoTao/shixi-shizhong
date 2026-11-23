package com.foldstandby.clock;

/** Continuous page coordinates keep a second swipe from restarting at a stale frame. */
final class PageMotion {
    static final String[] NAMES={"纵向滑动","柔和淡化","关闭"};
    static int index(int virtual,int count){return Math.floorMod(virtual,count);}
    static float destination(float position,int next,int direction,int count){float target=next;if(direction>0)while(target<position-.001f)target+=count;else if(direction<0)while(target>position+.001f)target-=count;return target;}
}
