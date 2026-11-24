package com.foldstandby.clock;

import android.media.session.PlaybackState;
import java.util.Locale;

/** The drawn circles, hit regions and declared session capabilities share one model. */
final class MediaControls {
    static final float[] X={135,250,365};
    static final float Y=310;
    static final float COMPACT_Y=262;
    static int hit(float x,float y){return hit(x,y,Y,44f);}
    static int hit(float x,float y,float centerY,float radius){for(int i=0;i<X.length;i++)if((x-X[i])*(x-X[i])+(y-centerY)*(y-centerY)<=radius*radius)return i;return -1;}
    static boolean enabled(long actions,boolean playing,int button){long required=button==0?PlaybackState.ACTION_SKIP_TO_PREVIOUS:button==2?PlaybackState.ACTION_SKIP_TO_NEXT:PlaybackState.ACTION_PLAY_PAUSE|(playing?PlaybackState.ACTION_PAUSE:PlaybackState.ACTION_PLAY);return (actions&required)!=0;}
    static String time(long millis){long seconds=Math.max(0,millis)/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
}
