package com.foldstandby.clock;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.AlarmClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.time.*;
import java.util.*;

/** Isolated rendering and intent checks; never creates, disables or rings a real alarm. */
final class AlarmChecks {
    private final Instrumentation runner;
    private Context context;
    private android.content.SharedPreferences prefs;
    private File directory;
    private int checks,renders;
    private final long now=ZonedDateTime.of(2026,11,14,22,15,0,0,ZoneId.systemDefault()).toInstant().toEpochMilli();
    private AlarmChecks(Instrumentation runner){this.runner=runner;}
    static Bundle run(Instrumentation runner){return new AlarmChecks(runner).run();}
    private void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
    private Bundle run(){
        context=runner.getTargetContext();prefs=context.getSharedPreferences("alarm_checks",0);
        directory=new File(context.getFilesDir(),"qa-0.3.8");directory.mkdirs();Bundle result=new Bundle();
        runner.runOnMainSync(()->{
            AlarmData.State before=AlarmData.state;
            try{
                prefs.edit().clear().putBoolean("night",false).commit();DeskData.updateSystemState(context,true);
                formatting();model();observing();rendering();interactions();gallery();
                result.putBoolean("passed",true);result.putString("result","PASS: "+checks+" alarm checks, "+renders+" offscreen renders; no system alarms modified");
            }catch(Throwable error){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(error));}
            finally{AlarmData.state=before;prefs.edit().clear().commit();}
        });return result;
    }
    private Scene scene(int style){
        Scene scene=Scene.load(prefs);Scene.Page page=scene.pages.get(0);page.layout=0;
        ComponentStyles.select(page.slots[0],"alarm",style);return scene;
    }
    private void model(){
        Scene scene=scene(0);Scene.Component c=scene.pages.get(0).slots[0];
        Scene.put(c.options,"h24",false);Scene.put(c.options,"alarmDate",false);Scene.put(c.options,"alarmCountdown",false);
        Scene.put(c.options,"scale",125);Scene.put(c.theme,"accent",0xffaaffcc);Scene.put(c.theme,"timeGradient",2);
        for(int style=0;style<ComponentStyles.ids("alarm").length;style++){
            ComponentStyles.select(c,"alarm",style);Scene restored=Scene.parse(scene.json());
            check(restored!=null,"alarm scene parses");Scene.Component copy=restored.pages.get(0).slots[0];
            check(copy.type.equals("alarm")&&ComponentStyles.selectedIndex(copy)==style,"alarm style saved");
            check(!copy.bool("h24",true)&&!copy.bool("alarmDate",true)&&!copy.bool("alarmCountdown",true),"display toggles saved");
            check(copy.options.optInt("scale")==125&&copy.theme.optInt("timeGradient")==2,"size and gradient saved");
            c.setType("weather");c.setType("alarm");check(ComponentStyles.selectedIndex(c)==style,"type change remembers alarm style");
            Scene.Page duplicate=scene.pages.get(0).duplicate();check(!duplicate.slots[0].id.equals(c.id)&&duplicate.slots[0].type.equals("alarm"),"duplicate gets a new identity");
        }
        scene.pages.get(0).refreshName();check(scene.pages.get(0).name.equals("闹钟"),"page name follows component");
        scene.save(prefs);check(Scene.load(prefs).json().equals(scene.json()),"alarm scene preference round trip");
        prefs.edit().remove(Scene.KEY).commit();check(!DeskData.wants(Scene.load(prefs),"alarm"),"old default scene unchanged");
    }
    private void formatting(){
        ZoneId la=ZoneId.of("America/Los_Angeles");ZonedDateTime night=ZonedDateTime.of(2026,12,31,23,59,0,0,la),midnight=night.plusMinutes(1);
        check(AlarmData.date(midnight,night).startsWith("明天"),"year boundary is tomorrow");
        check(AlarmData.date(night,night).startsWith("今天"),"same local date is today");
        check(!AlarmData.date(night.plusDays(2),night).startsWith("明天"),"later date has no tomorrow label");
        check(AlarmData.time(midnight,true).equals("00:00")&&AlarmData.time(midnight,false).equals("12:00"),"midnight in both hour formats");
        check(AlarmData.period(midnight).equals("上午")&&AlarmData.period(midnight.plusHours(12)).equals("下午"),"AM and PM are unambiguous");
        check(AlarmData.remaining(59999,0).equals("即将响铃"),"less than one minute");
        check(AlarmData.remaining(60000,0).equals("1分钟"),"one minute");
        check(AlarmData.remaining(60001,0).equals("2分钟"),"partial minute rounds up");
        check(AlarmData.remaining(3600000,0).equals("1小时"),"whole hour");
        check(AlarmData.remaining(90060000,0).equals("1天 1小时 1分钟"),"days hours and minutes");
        ZonedDateTime spring=ZonedDateTime.of(2026,3,7,12,0,0,0,la),next=spring.plusDays(1);
        check(AlarmData.date(next,spring).startsWith("明天"),"DST date uses calendar days");
        check(AlarmData.remaining(next.toInstant().toEpochMilli(),spring.toInstant().toEpochMilli()).equals("23小时"),"DST countdown uses elapsed duration");
        ZonedDateTime fall=ZonedDateTime.of(2026,10,31,12,0,0,0,la);
        check(AlarmData.remaining(fall.plusDays(1).toInstant().toEpochMilli(),fall.toInstant().toEpochMilli()).equals("1天 1小时"),"25 hour DST day");
        check(!new AlarmData.State(10,null,false).upcoming(10),"expired alarm is not shown as upcoming");
        check(!new AlarmData.State(20,null,true).upcoming(10),"unavailable state ignores stale time");
    }
    private final class RecordingContext extends ContextWrapper {
        Intent launched;IntentFilter filter;BroadcastReceiver receiver;int registrations,removals;boolean missing;
        RecordingContext(){super(context);}
        public Context getApplicationContext(){return this;}
        public void startActivity(Intent intent){if(missing)throw new ActivityNotFoundException();launched=intent;}
        public Intent registerReceiver(BroadcastReceiver observer,IntentFilter value){registrations++;filter=value;receiver=observer;return null;}
        public void unregisterReceiver(BroadcastReceiver receiver){removals++;}
    }
    private void observing(){
        RecordingContext c=new RecordingContext();int[] notices={0};Runnable first=()->notices[0]++,second=()->{};
        try{
            AlarmData.state=new AlarmData.State(-1,null,true);AlarmData.observe(c,first);AlarmData.observe(c,second);
            check(c.registrations==1,"surfaces share one alarm receiver");
            check(c.filter.hasAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)&&c.filter.hasAction(Intent.ACTION_TIME_CHANGED)
                &&c.filter.hasAction(Intent.ACTION_TIMEZONE_CHANGED)&&c.filter.hasAction(Intent.ACTION_TIME_TICK),"receiver covers alarm date zone and minute changes");
            check(notices[0]>0,"changed system snapshot notifies preview");
            AlarmManager.AlarmClockInfo actual=context.getSystemService(AlarmManager.class).getNextAlarmClock();
            check(AlarmData.state.triggerAt==(actual==null?0:actual.getTriggerTime()),"snapshot matches actual system query");
            int before=notices[0];c.receiver.onReceive(c,new Intent(Intent.ACTION_TIMEZONE_CHANGED));
            check(notices[0]>before,"time zone change redraws even when alarm instant is unchanged");
            AlarmData.forget(c,first);check(c.removals==0,"remaining surface keeps receiver");
            AlarmData.forget(c,second);check(c.removals==1,"last surface releases receiver");
            AlarmData.forget(c,second);check(c.removals==1,"repeated detach is harmless");
        }finally{AlarmData.forget(c,first);AlarmData.forget(c,second);}
    }
    private Bitmap render(View view,int width,int height){
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));renders++;return b;
    }
    private ClockFace face(Context c,Scene scene,boolean demo,boolean night){
        ClockFace f=new ClockFace(c,prefs);f.bind(scene,0);f.demo=demo;f.nightPreview=night;f.testTime=now;return f;
    }
    private void save(Bitmap b,String name)throws IOException{try(FileOutputStream stream=new FileOutputStream(new File(directory,name))){b.compress(Bitmap.CompressFormat.PNG,100,stream);}}
    private void rendering()throws Exception{
        AlarmData.state=new AlarmData.State(now+33300000,null,false);
        int count=ComponentStyles.ids("alarm").length;
        Bitmap sheet=Bitmap.createBitmap(1080,372*((count+1)/2),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);canvas.drawColor(0xff111820);
        Paint label=new Paint(3);label.setColor(0xffdbe5ef);label.setTextSize(24);label.setTextAlign(Paint.Align.CENTER);
        ArrayList<Bitmap> designs=new ArrayList<>();
        for(int style=0;style<count;style++){
            Scene scene=scene(style);Scene.Page page=scene.pages.get(0);Scene.Component c=page.slots[0];Scene.put(c.theme,"timeGradient",1);
            Bitmap sample=render(face(context,scene,false,false),1080,684);designs.add(sample);
            canvas.drawBitmap(sample,null,new Rect(style%2*540,style/2*372,style%2*540+540,style/2*372+342),null);
            canvas.drawText(ComponentStyles.names("alarm")[style],style%2*540+270,style/2*372+367,label);
            for(int layout=0;layout<4;layout++)for(int size:new int[]{75,100,125})for(boolean night:new boolean[]{false,true}){
                page.layout=layout;
                for(int slot=0;slot<4;slot++){page.slots[slot]=Scene.Component.from(c.json());Scene.put(page.slots[slot].options,"scale",size);}
                Bitmap b=render(face(context,scene,false,night),768,486);check(b.getPixel(0,0)==Color.BLACK,"layout scale night stays black");
                if(layout==3&&size==125&&!night)save(b,"alarm-quad-"+style+".png");b.recycle();
            }
            page.layout=0;page.slots[0]=c;ClockFace f=face(context,scene,false,false);Bitmap initial=render(f,768,486);
            Scene.put(c.options,"h24",false);Bitmap h12=render(f,768,486);check(!initial.sameAs(h12),"12 hour format changes visible output");h12.recycle();Scene.put(c.options,"h24",true);
            for(String option:new String[]{"alarmDate","alarmCountdown"}){
                Scene.put(c.options,option,false);Bitmap off=render(f,768,486);check(!initial.sameAs(off),"display option changes output: "+option);off.recycle();Scene.put(c.options,option,true);
            }
            AlarmData.state=new AlarmData.State(now-1,null,false);Bitmap expired=render(f,768,486);check(!initial.sameAs(expired),"expired alarm clears time");expired.recycle();
            AlarmData.state=new AlarmData.State(0,null,false);Bitmap empty=render(f,768,486);if(style==0)save(empty,"alarm-empty.png");
            AlarmData.state=new AlarmData.State(0,null,true);Bitmap error=render(f,768,486);check(!empty.sameAs(error),"unavailable differs from empty");if(style==0)save(error,"alarm-unavailable.png");empty.recycle();error.recycle();
            AlarmData.state=new AlarmData.State(now+33300000,null,false);Bitmap restored=render(f,768,486);check(initial.sameAs(restored),"snapshot change restores next alarm");restored.recycle();initial.recycle();
        }
        for(int a=0;a<count;a++)for(int b=a+1;b<count;b++)check(!designs.get(a).sameAs(designs.get(b)),"distinct designs");
        save(sheet,"styles-alarm.png");sheet.recycle();for(Bitmap b:designs)b.recycle();
    }
    private void tap(ClockFace f){
        long at=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(at,at,0,540,342,0),up=MotionEvent.obtain(at,at+80,1,540,342,0);
        f.onTouchEvent(down);f.onTouchEvent(up);down.recycle();up.recycle();
    }
    private void interactions(){
        RecordingContext c=new RecordingContext();
        AlarmData.open(c,true);check(c.launched.getAction().equals(AlarmClock.ACTION_SET_ALARM),"create opens system alarm editor");
        check(c.launched.getExtras()==null,"creation passes no time or silent scheduling extras");
        check((c.launched.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0,"non activity context gets launch flag");
        AlarmData.open(c,false);check(c.launched.getAction().equals(AlarmClock.ACTION_SHOW_ALARMS),"manage opens alarm list");
        long future=System.currentTimeMillis()+3600000;
        PendingIntent cancelled=PendingIntent.getActivity(context,7308,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);cancelled.cancel();
        AlarmData.state=new AlarmData.State(future,cancelled,false);AlarmData.openNext(c);
        check(c.launched.getAction().equals(AlarmClock.ACTION_SHOW_ALARMS),"cancelled alarm deep link falls back to list");
        for(int style=0;style<ComponentStyles.ids("alarm").length;style++){
            Scene scene=scene(style);ClockFace f=face(c,scene,false,false);Bitmap b=render(f,1080,684);b.recycle();
            AlarmData.state=new AlarmData.State(future,null,false);c.launched=null;tap(f);check(c.launched!=null&&c.launched.getAction().equals(AlarmClock.ACTION_SHOW_ALARMS),"tap manages alarm in every style");
            AlarmData.state=new AlarmData.State(0,null,false);c.launched=null;tap(f);check(c.launched!=null&&c.launched.getAction().equals(AlarmClock.ACTION_SET_ALARM),"empty tap opens editor");
            c.launched=null;final int[] selected={0};f.slotTap=slot->selected[0]++;tap(f);check(selected[0]==1&&c.launched==null,"workspace tap selects slot");f.slotTap=null;
            f.demo=true;f.setOnClickListener(v->selected[0]++);tap(f);check(selected[0]==2&&c.launched==null,"gallery tap only applies style");
            f.demo=false;AccessibilityNodeInfo info=AccessibilityNodeInfo.obtain();f.onInitializeAccessibilityNodeInfo(info);int action=0;
            for(AccessibilityNodeInfo.AccessibilityAction item:info.getActionList())if("管理闹钟".equals(String.valueOf(item.getLabel())))action=item.getId();
            check(action!=0,"alarm is accessible without a music session");
            c.launched=null;check(f.performAccessibilityAction(action,null)&&c.launched!=null,"accessible action opens alarm editor");info.recycle();
        }
        c.missing=true;check(!AlarmData.open(c,false),"missing clock app is handled");
    }
    private void gallery()throws Exception{
        MainActivity activity=new MainActivity();activity.prepareForCheck(context);activity.libraryForCheck("alarm");
        float density=context.getResources().getDisplayMetrics().density;
        for(int[] size:new int[][]{{554,876},{876,554}}){
            String before=activity.draft.json();View root=activity.createUi(size[0],size[1]);Bitmap b=render(root,Math.round(size[0]*density),Math.round(size[1]*density));
            save(b,"alarm-gallery-"+(size[0]<size[1]?"portrait":"landscape")+".png");b.recycle();
            int width=-1;for(String id:ComponentStyles.ids("alarm")){
                View card=root.findViewWithTag("style:alarm:"+id);check(card!=null,"gallery includes alarm design");
                if(width<0)width=card.getMeasuredWidth();check(Math.abs(card.getMeasuredWidth()-width)<=2,"gallery cards have equal widths");
            }
            check(activity.draft.json().equals(before),"alarm preview never mutates scene");
        }
        ComponentStyles.select(activity.draft.pages.get(activity.draft.homeIndex()).slots[0],"alarm",0);
        AlarmData.state=new AlarmData.State(System.currentTimeMillis()+33300000,null,false);
        activity.selectForCheck(1,0);activity.inspectorForCheck(0);
        for(int[] size:new int[][]{{554,876},{876,554}}){
            View root=activity.createUi(size[0],size[1]);Bitmap b=render(root,Math.round(size[0]*density),Math.round(size[1]*density));
            check(root.findViewWithTag("alarm:status")!=null,"alarm editor includes live system status");
            save(b,"alarm-editor-"+(size[0]<size[1]?"portrait":"landscape")+".png");b.recycle();
            View status=root.findViewWithTag("alarm:status");android.view.ViewParent parent=status.getParent();
            while(parent!=null&&!(parent instanceof android.widget.ScrollView))parent=parent.getParent();
            check(parent instanceof android.widget.ScrollView,"alarm options share the parameter scroll area");
            android.widget.ScrollView scroll=(android.widget.ScrollView)parent;scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
            b=render(root,Math.round(size[0]*density),Math.round(size[1]*density));
            save(b,"alarm-options-"+(size[0]<size[1]?"portrait":"landscape")+".png");b.recycle();
        }
    }
}
