package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.os.Bundle;
import android.view.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import org.json.JSONObject;

/** Exercises bound, attached fullscreen clocks with isolated settings and real feedback output. */
final class FlipFeedbackChecks {
    private final Instrumentation host;
    private int assertions;
    private FlipFeedbackChecks(Instrumentation host){this.host=host;}
    static Bundle run(Instrumentation host){return new FlipFeedbackChecks(host).run();}
    private void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private void sync(Runnable action){
        Throwable[] error={null};host.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private static Object field(ClockFace face,String name){
        try{Field field=ClockFace.class.getDeclaredField(name);field.setAccessible(true);return field.get(face);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static long minute(ClockFace face,String name){return (Long)field(face,name);}
    private static ClockFace find(View view){
        if(view instanceof ClockFace)return (ClockFace)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ClockFace found=find(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static void draw(ClockFace face,long time){
        face.testTime=time;
        Bitmap bitmap=Bitmap.createBitmap(Math.max(1,face.getWidth()),Math.max(1,face.getHeight()),Bitmap.Config.ARGB_8888);
        face.draw(new Canvas(bitmap));bitmap.recycle();
    }
    private static JSONObject options(Scene.Component component,boolean sound,boolean haptic){
        component.type="clock";component.style="flip";Scene.put(component.options,"seconds",true);
        JSONObject options=component.styleOptions();Scene.put(options,"flipSeconds",true);Scene.put(options,"sound",sound);Scene.put(options,"haptic",haptic);return options;
    }
    private Bundle run(){
        Bundle result=new Bundle();Context context=host.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("ui_checks",0);
        Map<String,?> original=prefs.getAll();ClockActivity activity=null;
        try{
            prefs.edit().clear().putBoolean("night",false).putBoolean("motion",true).putInt("homeAfter",0).commit();
            Scene scene=Scene.load(prefs);scene.pages.get(0).layout=0;options(scene.pages.get(0).slots[0],false,false);scene.home=scene.pages.get(0).id;scene.save(prefs);
            activity=(ClockActivity)host.startActivitySync(new Intent(context,ClockActivity.class).putExtra("uiCheck",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            host.waitForIdleSync();final ClockActivity screen=activity;final ClockFace face=find(screen.getWindow().getDecorView());
            check(face!=null,"fullscreen clock exists");
            long start=ZonedDateTime.of(2026,11,16,10,28,58,0,ZoneId.systemDefault()).toInstant().toEpochMilli();
            sync(()->{
                check((Boolean)field(face,"bound")&&face.flipFeedbackEnabled,"bound runtime clock explicitly enables feedback");
                check(face.isAttachedToWindow()&&face.isShown(),"runtime clock is visible and attached");
                face.nightPreview=false;face.bind(scene,0);draw(face,start);
                check(field(face,"tone")==null,"initial draw is silent");
                options(scene.pages.get(0).slots[0],true,true);draw(face,start+1000);
                check(minute(face,"soundMinute")==((start+1000)/60000),"bound clock sends sound on digit change");
                check(minute(face,"hapticMinute")==((start+1000)/60000),"bound clock sends clock-tick haptics");
                check(field(face,"tone") instanceof ToneGenerator,"runtime creates real tone output");
                draw(face,start+2000);long expected=(start+2000)/60000;
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"next minute permits feedback again");
                draw(face,start+3000);check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"seconds stay limited to one feedback per minute");
                face.nightPreview=true;draw(face,start+62000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"night suppresses sound and haptics");
                face.nightPreview=false;face.setMotionActive(false);draw(face,start+122000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"rest suppresses feedback");
                face.setMotionActive(true);face.demo=true;draw(face,start+182000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"demo rendering is silent");
                face.demo=false;face.setVisibility(View.INVISIBLE);draw(face,start+242000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"hidden clock is silent");
                face.setVisibility(View.VISIBLE);options(scene.pages.get(0).slots[0],false,false);draw(face,start+302000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"disabled options stay silent");

                // Disabled or sound-only slots must not consume another slot's haptic feedback.
                scene.pages.get(0).layout=1;options(scene.pages.get(0).slots[1],true,true);face.bind(scene,0);draw(face,start+360000);draw(face,start+362000);
                expected=(start+362000)/60000;
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"disabled first clock does not swallow second clock feedback");
                options(scene.pages.get(0).slots[0],true,false);options(scene.pages.get(0).slots[1],false,true);draw(face,start+422000);
                expected=(start+422000)/60000;
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"sound and haptic limits are independent across components");

                // Both pages render during transitions, but only the selected page may make feedback.
                options(scene.pages.get(0).slots[0],false,false);options(scene.pages.get(0).slots[1],false,false);
                scene.pages.get(1).layout=0;options(scene.pages.get(1).slots[0],true,true);
                face.bind(scene,0);face.positionForCheck(.5f,0);draw(face,start+480000);draw(face,start+482000);
                check(minute(face,"soundMinute")==expected&&minute(face,"hapticMinute")==expected,"neighboring transition page cannot emit feedback");
                face.selectPage(1);draw(face,start+540000);draw(face,start+542000);
                check(minute(face,"soundMinute")==((start+542000)/60000),"selected page can emit feedback after switching");

                ClockFace thumbnail=new ClockFace(context,prefs);thumbnail.managedRefresh=true;thumbnail.bind(scene,1);thumbnail.nightPreview=false;
                ViewGroup root=(ViewGroup)face.getParent();root.addView(thumbnail,new ViewGroup.LayoutParams(320,200));
                thumbnail.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY));thumbnail.layout(0,0,320,200);
                draw(thumbnail,start);draw(thumbnail,start+2000);
                check(thumbnail.isAttachedToWindow()&&field(thumbnail,"tone")==null&&minute(thumbnail,"hapticMinute")==-1,"attached editor thumbnail remains silent");
                thumbnail.flipFeedbackEnabled=true;root.removeView(thumbnail);draw(thumbnail,start+62000);
                check(field(thumbnail,"tone")==null&&minute(thumbnail,"hapticMinute")==-1,"detached view remains silent even when enabled");
                result.putInt("audioSessionId",((ToneGenerator)field(face,"tone")).getAudioSessionId());
                face.setMotionActive(false);
            });
            AudioManager audio=context.getSystemService(AudioManager.class);
            result.putInt("ringerMode",audio.getRingerMode());result.putBoolean("notificationMuted",audio.isStreamMute(AudioManager.STREAM_NOTIFICATION));
            result.putBoolean("mediaMuted",audio.isStreamMute(AudioManager.STREAM_MUSIC));result.putInt("mediaVolume",audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            sync(()->{screen.finish();screen.overridePendingTransition(0,0);});host.waitForIdleSync();
            for(int retry=0;retry<40;retry++){
                boolean[] attached={false};sync(()->attached[0]=face.isAttachedToWindow());if(!attached[0])break;
                android.os.SystemClock.sleep(50);
            }
            sync(()->check(field(face,"tone")==null,"detaching fullscreen releases tone resources"));
            activity=(ClockActivity)host.startActivitySync(new Intent(context,ClockActivity.class).putExtra("uiCheck",true).putExtra("scenePreview",scene.json()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            host.waitForIdleSync();final ClockActivity preview=activity;
            sync(()->{ClockFace full=find(preview.getWindow().getDecorView());check(full!=null&&full.flipFeedbackEnabled,"fullscreen scene preview also enables feedback");full.nightPreview=false;full.bind(scene,1);draw(full,start);draw(full,start+2000);check(minute(full,"soundMinute")==((start+2000)/60000),"fullscreen preview sends feedback on digit change");full.setMotionActive(false);});
            result.putBoolean("passed",true);result.putString("result","PASS: "+assertions+" flip feedback checks");
        }catch(Throwable error){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(error));}
        finally{
            if(activity!=null){final ClockActivity closing=activity;sync(closing::finish);host.waitForIdleSync();}
            SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){
                Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,new HashSet<>((Set<String>)value));
            }editor.commit();
        }
        return result;
    }
}
