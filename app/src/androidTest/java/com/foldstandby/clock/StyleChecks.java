package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.json.*;

/** Offscreen checks of all visual styles using isolated settings and a silent test media session. */
final class StyleChecks {
    private static final int W=1080,H=684;
    private final Instrumentation runner;
    private Context context;
    private SharedPreferences prefs;
    private File directory;
    private int assertions,renders;
    private StyleChecks(Instrumentation runner){this.runner=runner;}
    static Bundle run(Instrumentation runner){return new StyleChecks(runner).run();}
    static Bundle runGradients(Instrumentation runner){
        StyleChecks checks=new StyleChecks(runner);
        checks.context=runner.getTargetContext();
        checks.prefs=checks.context.getSharedPreferences("gradient_checks",0);
        Bundle result=new Bundle();
        try{
            checks.sync(checks::gradientChecks);
            result.putBoolean("passed",true);
            result.putString("result","PASS: "+checks.assertions+" gradient assertions across all 51 styles");
        }catch(Throwable error){
            result.putBoolean("passed",false);
            result.putString("result",android.util.Log.getStackTraceString(error));
        }finally{checks.prefs.edit().clear().commit();}
        return result;
    }
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private void sync(Runnable action){
        final Throwable[] error={null};runner.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private Bundle run(){
        Bundle result=new Bundle();context=runner.getTargetContext();prefs=context.getSharedPreferences("style_checks",0);
        directory=new File(context.getFilesDir(),"qa-0.3.6");directory.mkdirs();
        try{
            sync(()->{try{prefs.edit().clear().putBoolean("night",false).putBoolean("motion",true).commit();modelChecks();renderChecks();musicPresentationChecks();animationChecks();interactionChecks();galleryChecks();}catch(Exception e){throw new AssertionError(e);}});
            mediaChecks();
            int styles=0;
            for(String type:Scene.TYPES)if(!type.equals("clock"))styles+=count(type);
            result.putBoolean("passed",true);result.putString("result","PASS: "+assertions+" style checks, "+renders+" offscreen renders, "+styles+" non-clock styles");
        }catch(Throwable error){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(error));}
        finally{prefs.edit().clear().commit();}
        return result;
    }
    private int count(String type){return ComponentStyles.ids(type).length;}
    private Scene model(String type,int style){
        Scene scene=Scene.load(prefs);Scene.Page page=scene.pages.get(0);page.layout=0;
        ComponentStyles.select(page.slots[0],type,style);
        Scene.put(page.slots[0].options,"zone","Asia/Kathmandu");
        if(type.equals("todo"))for(String task:new String[]{"准备明天的计划","阅读二十分钟","完成设计复盘","整理桌面","早点休息"})TodoItems.add(page.slots[0],task);
        return scene;
    }
    private void modelChecks()throws Exception{
        for(String type:Scene.TYPES){
            if(type.equals("clock"))continue;
            check(count(type)>=5&&ComponentStyles.names(type).length==count(type)&&ComponentStyles.notes(type).length==count(type),"complete style catalog: "+type);
            Scene scene=model(type,0);Scene.Component c=scene.pages.get(0).slots[0];
            Scene.put(c.options,"showAgenda",false);Scene.put(c.options,"agendaToday",true);Scene.put(c.options,"tomorrow",true);Scene.put(c.options,"scale",115);Scene.put(c.theme,"accent",0xff7edfc7);
            for(int n=0;n<count(type);n++){
                ComponentStyles.select(c,type,n);Scene.put(c.styleOptions(),"marker",n+1);
                Scene restored=Scene.parse(scene.json());check(restored!=null,"parse "+type);
                Scene.Component copy=restored.pages.get(0).slots[0];
                check(copy.style.equals(ComponentStyles.ids(type)[n]),"style survives save "+type+" / "+n);
                check(!copy.bool("showAgenda",true)&&copy.bool("agendaToday",false)&&copy.bool("tomorrow",false),"content switches independent of styles");
                check(copy.options.optInt("scale")==115&&copy.theme.optInt("accent")==0xff7edfc7,"size and palette preserved");
                check(copy.options.optString("zone").equals("Asia/Kathmandu"),"time zone preserved");
                if(type.equals("todo"))check(TodoItems.all(copy).length()==5,"tasks preserved across styles");
                c.setType("clock");c.style="industrial";c.setType(type);
                check(c.style.equals(ComponentStyles.ids(type)[n]),"type switch remembers component style");
                c.setType("clock");check(c.style.equals("industrial"),"type switch remembers clock style");c.setType(type);
                check(c.styleOptions().optInt("marker")==n+1,"style options remain isolated");
            }
            ComponentStyles.select(c,type,0);check(c.styleOptions().optInt("marker")==1,"first style options retained");
            JSONObject old=c.json();Scene.put(old,"style","digital");Scene.Component migrated=Scene.Component.from(old);
            check(ComponentStyles.selectedIndex(migrated)==0,"legacy component uses its original design");
            check(migrated.bool("tomorrow",false)&&!migrated.bool("showAgenda",true),"legacy content options preserved");
            Scene.put(old,"style","unknown-future-style");check(ComponentStyles.selectedIndex(Scene.Component.from(old))==0,"unknown style falls back safely");
            scene.save(prefs);check(Scene.load(prefs).json().equals(scene.json()),"full scene save/load "+type);prefs.edit().remove(Scene.KEY).commit();
        }
        Scene.Component clock=new Scene.Component();clock.style="flip";Scene.put(clock.styleOptions(),"duration",700);
        clock.setType("weather");clock.setType("clock");check(clock.style.equals("flip")&&clock.styleOptions().optInt("duration")==700,"clock style details survive type switch");
        check(count("music") == 5, "vector artwork does not add gallery styles");
        for (String style : new String[]{"record", "music_tape"}) {
            JSONObject old = new Scene.Component().json();
            Scene.put(old, "type", "music");
            Scene.put(old, "style", style + "_vector");
            Scene.put(old.getJSONObject("options"), "style:music", style + "_vector");
            Scene.Component restored = Scene.Component.from(old);
            check(restored.style.equals(style) && ComponentStyles.vectorArtwork(restored), "active vector artwork migrates: " + style);
            restored.setType("clock");
            restored.setType("music");
            check(restored.style.equals(style) && ComponentStyles.vectorArtwork(restored), "vector artwork survives type changes: " + style);
            ComponentStyles.setVectorArtwork(restored, false);
            check(!ComponentStyles.vectorArtwork(Scene.Component.from(restored.json())), "realistic choice survives save: " + style);

            Scene.put(old, "type", "clock");
            Scene.put(old, "style", "digital");
            Scene.Component remembered = Scene.Component.from(old);
            remembered.setType("music");
            check(remembered.style.equals(style) && ComponentStyles.vectorArtwork(remembered), "remembered vector artwork migrates: " + style);
        }
    }
    private Bitmap render(View view,int width,int height){
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));renders++;return bitmap;
    }
    private ClockFace face(Scene scene,boolean demo,boolean night){
        ClockFace f=new ClockFace(context,prefs);f.bind(scene,0);f.demo=demo;f.nightPreview=night;
        f.testTime=ZonedDateTime.of(2026,11,14,10,28,0,0,ZoneId.systemDefault()).toInstant().toEpochMilli();return f;
    }
    private void save(Bitmap bitmap,String name)throws IOException{try(FileOutputStream stream=new FileOutputStream(new File(directory,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}}
    private void renderChecks()throws Exception{
        for(String type:Scene.TYPES){
            if(type.equals("clock"))continue;
            Bitmap sheet=Bitmap.createBitmap(1080,372*((count(type)+1)/2),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(sheet);canvas.drawColor(0xff111820);
            Paint label=new Paint(3);label.setColor(0xffdbe5ef);label.setTextSize(24);label.setTextAlign(Paint.Align.CENTER);
            ArrayList<Bitmap> samples=new ArrayList<>();
            for(int n=0;n<count(type);n++){
                Scene scene=model(type,n);Scene.Page page=scene.pages.get(0);Scene.Component item=page.slots[0];
                if(type.equals("todo"))TodoItems.toggle(item,4);
                Scene.put(item.theme,"timeGradient",1);Bitmap image=render(face(scene,true,false),W,H);samples.add(image);
                canvas.drawBitmap(image,null,new Rect(n%2*540,n/2*372,n%2*540+540,n/2*372+342),null);
                canvas.drawText(Scene.typeName(type)+" · "+ComponentStyles.names(type)[n],n%2*540+270,n/2*372+367,label);
                for(int layout=0;layout<4;layout++)for(int night=0;night<2;night++){
                    page.layout=layout;for(int i=1;i<4;i++)page.slots[i]=Scene.Component.from(item.json());
                    Bitmap b=render(face(scene,true,night==1),768,486);check(b.getPixel(0,0)==Color.BLACK,"black background "+type+" / "+n);b.recycle();
                }
                for(int size:new int[]{75,125}){
                    page.layout=3;for(Scene.Component c:page.slots)Scene.put(c.options,"scale",size);
                    Bitmap b=render(face(scene,true,false),768,486);check(b.getPixel(0,0)==Color.BLACK,"scaled rendering "+size);b.recycle();
                }
                page.layout=0;Scene.put(item.options,"scale",100);
                for(int gradient=1;gradient<TextGradients.NAMES.length;gradient++){
                    Scene.put(item.theme,"timeGradient",gradient);Bitmap b=render(face(scene,true,false),768,486);b.recycle();
                }
                if(type.equals("date"))for(int month:new int[]{2,8}){
                    ClockFace f=face(scene,true,false);f.testTime=ZonedDateTime.of(2028,month,month==2?29:31,12,0,0,0,ZoneId.systemDefault()).toInstant().toEpochMilli();
                    Bitmap b=render(f,768,486);b.recycle();
                }
            }
            for(int a=0;a<samples.size();a++)for(int b=a+1;b<samples.size();b++)check(!samples.get(a).sameAs(samples.get(b)),"distinct visual designs "+type+" / "+a+" / "+b);
            save(sheet,"styles-"+type+".png");sheet.recycle();for(Bitmap sample:samples)sample.recycle();
        }
        // Real missing-data and privacy states must be shared by every visual design.
        // A neighboring renderer's brush settings must not alter this component.
        for(String type:Scene.TYPES){
            if(type.equals("clock"))continue;
            for(int n=0;n<count(type);n++){
                Scene pair=model("clock",0);Scene.Page page=pair.pages.get(0);page.layout=1;
                ComponentStyles.select(page.slots[0],"clock",4);
                page.slots[1]=model(type,n).pages.get(0).slots[0];
                Bitmap before=render(face(pair,true,false),768,486);
                ComponentStyles.select(page.slots[0],"battery",0);
                Bitmap after=render(face(pair,true,false),768,486);
                int[] first=new int[364*486],second=new int[first.length];
                before.getPixels(first,0,364,404,0,364,486);after.getPixels(second,0,364,404,0,364,486);
                check(Arrays.equals(first,second),"neighbor paint isolated "+type+" / "+n);before.recycle();after.recycle();
            }
        }
        boolean locked=DeskData.keyguardLocked;JSONObject weather=DeskData.weather;
        try{
            DeskData.keyguardLocked=true;
            for(String type:new String[]{"todo","calendar","date","weather","music"})for(int n=0;n<count(type);n++){
                Bitmap b=render(face(model(type,n),false,false),768,486);b.recycle();
            }
            DeskData.weather=new JSONObject("{\"current\":{\"temperature_2m\":-12,\"weather_code\":73},\"daily\":{\"temperature_2m_max\":[-5],\"temperature_2m_min\":[-18]}}");
            prefs.edit().putString("city","测试城市").putBoolean("fahrenheit",true).commit();
            for(int n=0;n<count("weather");n++){Scene scene=model("weather",n);Scene.put(scene.pages.get(0).slots[0].options,"tomorrow",true);Bitmap b=render(face(scene,false,false),768,486);b.recycle();}
        }finally{DeskData.keyguardLocked=locked;DeskData.weather=weather;prefs.edit().remove("city").remove("fahrenheit").commit();}
    }
    private void tap(ClockFace f,float x,float y,int layout,int size,boolean shifted){
        float gap=Math.min(W,H)*.025f,pad=gap*1.4f;
        float cw=layout==0?W-2*pad:layout==3?(W-2*pad-gap)/2:(W-2*pad-gap)*(layout==2?.62f:.5f),ch=layout==3?(H-2*pad-gap)/2:H-2*pad;
        float scale=Math.min(cw/540f,ch/432f);long minute=System.currentTimeMillis()/60000;
        float sx=shifted?(minute%7-3)*6:0,sy=shifted?((minute/7)%5-2)*7:0;
        float actualX=pad+cw/2+((x-250)*size/100f+sx)*scale,actualY=pad+ch/2+((y-200)*size/100f+sy)*scale;
        long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,0,actualX,actualY,0),up=MotionEvent.obtain(time,time+80,1,actualX,actualY,0);
        f.onTouchEvent(down);f.onTouchEvent(up);down.recycle();up.recycle();
    }
    private void interactionChecks(){
        boolean locked=DeskData.keyguardLocked;DeskData.keyguardLocked=false;
        try{
            for(int style=0;style<count("todo");style++)for(int layout=0;layout<4;layout++)for(int size:new int[]{75,100,125}){
                Scene scene=model("todo",style);Scene.Page page=scene.pages.get(0);page.layout=layout;Scene.Component c=page.slots[0];Scene.put(c.options,"scale",size);
                ClockFace f=face(scene,false,false);f.externalTick=true;Bitmap b=render(f,W,H);b.recycle();
                TodoLayout body=TodoLayout.of(c);
                tap(f,body.cellLeft(0)+body.cellWidth()/2,body.top+18,layout,size,true);
                check(TodoItems.all(c).optJSONObject(0).optBoolean("done"),"todo first row hit "+style+" / "+layout+" / "+size);
                tap(f,250,345,layout,size,true);int completed=0;for(int i=0;i<5;i++)if(TodoItems.all(c).optJSONObject(i).optBoolean("done"))completed++;
                check(completed==1,"todo footer never toggles a task");
                if(c.style.equals("todo_board")){
                    tap(f,250,body.top+18,layout,size,true);
                    check(TodoItems.all(c).optJSONObject(0).optBoolean("done")&&!TodoItems.all(c).optJSONObject(1).optBoolean("done"),"board divider never toggles a task");
                    tap(f,body.cellLeft(1)+body.cellWidth()/2,body.top+18,layout,size,true);
                    check(!TodoItems.all(c).optJSONObject(0).optBoolean("done"),"right lane restores completed task");
                }
            }
            for(String type:new String[]{"todo","music","focus"})for(int n=0;n<count(type);n++){
                ClockFace f=face(model(type,n),true,false);final int[] clicks={0};f.setOnClickListener(v->clicks[0]++);Bitmap b=render(f,W,H);b.recycle();
                tap(f,250,310,0,100,false);check(clicks[0]==1,"gallery preview selects style for interactive component "+type);
            }
            for(int n=0;n<count("focus");n++){
                DeskData.resetFocus(prefs);ClockFace f=face(model("focus",n),false,false);Bitmap b=render(f,W,H);b.recycle();
                tap(f,250,310,0,100,false);check(prefs.getLong("focusEnd",0)>0,"focus starts "+n);
                tap(f,250,310,0,100,false);check(prefs.getLong("focusEnd",0)==0,"focus pauses "+n);
            }
        }finally{DeskData.keyguardLocked=locked;}
    }
    private void animationChecks()throws Exception{
        check(android.animation.ValueAnimator.areAnimatorsEnabled(),"animations enabled for frame checks");
        Scene scene=model("focus",3);Scene.put(scene.pages.get(0).slots[0].theme,"timeGradient",1);
        ClockFace hourglass=face(scene,true,false);hourglass.testTime=0;
        Bitmap first=render(hourglass,W,H);hourglass.testTime=300;Bitmap next=render(hourglass,W,H);
        check(!first.sameAs(next),"falling sand particles move between frames");first.recycle();next.recycle();
        for(int frame=0;frame<24;frame++){
            hourglass.testTime=frame*100L;Bitmap image=render(hourglass,W,H);save(image,"hourglass-frame-"+frame+".png");image.recycle();
        }
        for(int mode=0;mode<3;mode++){
            hourglass.nightPreview=mode==0;hourglass.setMotionActive(mode!=1);prefs.edit().putBoolean("motion",mode!=2).commit();
            hourglass.testTime=0;first=render(hourglass,W,H);hourglass.testTime=500;next=render(hourglass,W,H);
            check(first.sameAs(next),"sand animation stops for night, rest and motion switch "+mode);first.recycle();next.recycle();
        }
        prefs.edit().putBoolean("motion",true).putLong("focusEnd",0).putLong("focusLeft",900000).commit();
        hourglass=face(scene,false,false);hourglass.testTime=0;first=render(hourglass,W,H);hourglass.testTime=500;next=render(hourglass,W,H);
        check(first.sameAs(next),"paused sand stays still");save(next,"hourglass-paused.png");first.recycle();next.recycle();
        for(long left:new long[]{1500000,750000,0}){
            prefs.edit().putLong("focusLeft",left).commit();Bitmap image=render(hourglass,W,H);save(image,"hourglass-remaining-"+left+".png");image.recycle();
        }
        Scene batteryScene=model("battery",0);Scene.put(batteryScene.pages.get(0).slots[0].theme,"timeGradient",1);
        ClockFace battery=face(batteryScene,true,false);
        for(long at:new long[]{0,600,1200,1800,2390,2410}){
            battery.testTime=at;Bitmap image=render(battery,W,H);
            double angle=Math.toRadians(-90+360*(at%2400)/2400f);
            float x=180+(float)Math.cos(angle)*126,y=200+(float)Math.sin(angle)*126;
            float scale=Math.min((W-2*H*.035f)/540f,(H-2*H*.035f)/432f);
            int px=Math.round(W/2f+(x-250)*scale),py=Math.round(H/2f+(y-200)*scale);
            int pixel=image.getPixel(px,py);
            check(Color.red(pixel)+Color.green(pixel)+Color.blue(pixel)>140,"charging particle completes circle and crosses loop boundary "+at);image.recycle();
        }
        for(int frame=0;frame<24;frame++){
            battery.testTime=frame*100L;Bitmap image=render(battery,W,H);save(image,"charging-frame-"+frame+".png");image.recycle();
        }
        DeskData.resetFocus(prefs);
    }
    private ClockFace findFace(View v){if(v instanceof ClockFace)return (ClockFace)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ClockFace f=findFace(((ViewGroup)v).getChildAt(i));if(f!=null)return f;}return null;}
    private void galleryChecks()throws Exception{
        MainActivity activity=new MainActivity();activity.prepareForCheck(context);
        int[][] windows={{320,700},{554,876},{876,554},{821,1088},{1088,821},{480,444},{880,320}};
        for(int[] window:windows)for(String type:Scene.TYPES){
            activity.libraryForCheck(type);String before=activity.draft.json();
            View root=activity.createUi(window[0],window[1]);float density=context.getResources().getDisplayMetrics().density;
            Bitmap b=render(root,Math.round(window[0]*density),Math.round(window[1]*density));
            if(window[0]==554&&type.equals("weather")||window[0]==876&&type.equals("todo"))save(b,"gallery-"+type+(window[0]==554?"-portrait":"-landscape")+".png");b.recycle();
            int cardWidth=-1;
            View firstCard = null;
            for(String id:ComponentStyles.ids(type)){
                View card=root.findViewWithTag("style:"+type+":"+id);check(card!=null,"gallery card exists "+type+" / "+id);
                if (firstCard == null) firstCard = card;
                else if (window[0] == 320 || window[0] == 480 || window[0] == 554) {
                    check(card.getParent() != firstCard.getParent(), "narrow windows stack style previews");
                }
                if(cardWidth<0)cardWidth=card.getMeasuredWidth();
                check(Math.abs(card.getMeasuredWidth()-cardWidth)<=2,"gallery cards have equal width "+type+" / "+window[0]);
                check(card.getBackground() == null, "style has no outer background");
                ClockFace face=findFace(card);
                check(face.getMeasuredWidth()==card.getMeasuredWidth(),"preview fills the tile width");
                check(Math.abs(face.getMeasuredWidth()-face.getMeasuredHeight()*1.25f)<3,"preview ratio is independent of the screen");
                check(face.getMeasuredWidth() > UiKit.dp(context,190), "style previews exceed the old width cap");
                if (window[0] == 320 || window[0] == 554) {
                    check(Math.abs(face.getMeasuredWidth()-UiKit.dp(context,window[0]-24))<=1, "cover portrait previews span the content width");
                }
                if (window[0] == 821) check(face.getMeasuredWidth() >= UiKit.dp(context,390), "inner portrait previews remain large");
            }
            if (window[0] == 1088) {
                View second = root.findViewWithTag("style:"+type+":"+ComponentStyles.ids(type)[1]);
                check(second.getParent() == firstCard.getParent(), "expanded landscape places previews side by side");
            }
            check(activity.draft.json().equals(before),"gallery preview never mutates draft");
        }
        Scene.Page page=activity.draft.pages.get(activity.draft.homeIndex());page.layout=1;
        ComponentStyles.select(page.slots[1],"world",2);Scene.put(page.slots[1].options,"zone","Pacific/Chatham");Scene.put(page.slots[1].theme,"time",0xfffedcba);
        Field slot=MainActivity.class.getDeclaredField("slot");slot.setAccessible(true);slot.setInt(activity,1);
        float density = context.getResources().getDisplayMetrics().density;
        activity.foldForCheck(new Rect(Math.round(400*density),0,Math.round(420*density),Math.round(554*density)));
        activity.libraryForCheck("world");View root=activity.createUi(876,554);Bitmap b=render(root,Math.round(876*density),Math.round(554*density));b.recycle();
        check(root.findViewWithTag("style:world:world_split").isSelected(),"selected style remains marked next to a hinge");
        ClockFace preview=findFace(root.findViewWithTag("style:world:world_dial"));Field field=ClockFace.class.getDeclaredField("scene");field.setAccessible(true);Scene sample=(Scene)field.get(preview);
        Scene.Component c=sample.pages.get(sample.homeIndex()).slots[0];
        check(c.options.optString("zone").equals("Pacific/Chatham")&&c.theme.optInt("time")==0xfffedcba,"style previews copy the selected slot's data and palette");
        Field fold = ClockFace.class.getDeclaredField("targetFold");
        fold.setAccessible(true);
        check(fold.get(preview) == null, "component preview has no simulated hinge");
    }
    private void mediaChecks()throws Exception{
        final MediaSession[] session={null};final ClockFace[] f={null};final AtomicIntegerArray commands=new AtomicIntegerArray(3);
        MediaController previous=DeskData.media;boolean allowed=DeskData.musicAllowed;
        try{
            sync(()->{
                session[0]=new MediaSession(context,"Style validation");session[0].setCallback(new MediaSession.Callback(){public void onSkipToPrevious(){commands.incrementAndGet(0);}public void onPause(){commands.incrementAndGet(1);}public void onSkipToNext(){commands.incrementAndGet(2);}},new Handler(Looper.getMainLooper()));
                session[0].setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"样式控制检查").putString(MediaMetadata.METADATA_KEY_ARTIST,"本地静音会话").putLong(MediaMetadata.METADATA_KEY_DURATION,213000).build());
                session[0].setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,74000,1).setActions(PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_PAUSE).build());
                DeskData.musicAllowed=true;DeskData.media=new MediaController(context,session[0].getSessionToken());
            });
            for(int style=0;style<count("music");style++){
                final int n=style;
                sync(()->{f[0]=face(model("music",n),false,false);Bitmap b=render(f[0],W,H);b.recycle();tap(f[0],150,202,0,100,false);});runner.waitForIdleSync();
                check(commands.get(0)==style&&commands.get(1)==style&&commands.get(2)==style,"progress tap sends no command "+style);
                for(int button=0;button<3;button++){
                    final int action=button;sync(()->tap(f[0],MediaControls.X[action],MediaControls.Y,0,100,false));runner.waitForIdleSync();
                    long deadline=SystemClock.uptimeMillis()+1000;
                    while(commands.get(button)<style+1&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(10);
                    check(commands.get(button)==style+1,"music style button reaches session "+style+" / "+button);
                }
            }
        }finally{sync(()->{if(session[0]!=null)session[0].release();DeskData.media=previous;DeskData.musicAllowed=allowed;});}
    }

    private void musicPresentationChecks() {
        for (int style = 0; style < count("music"); style++) {
            Scene scene = model("music", style);
            Scene.Component component = scene.pages.get(0).slots[0];
            ClockFace face = face(scene, true, false);
            face.testTime = 0;
            Bitmap withoutLyrics = render(face, W, H);
            Scene.put(component.options, MusicLyrics.SHOW, true);
            Bitmap withLyrics = render(face, W, H);
            check(!withoutLyrics.sameAs(withLyrics), "lyrics appear in music style " + style);
            Scene restored = Scene.parse(scene.json());
            check(restored.pages.get(0).slots[0].bool(MusicLyrics.SHOW, false), "lyric choice survives saving");
            withoutLyrics.recycle();
            withLyrics.recycle();
        }

        Scene scene = model("music", 0);
        Scene.Component component = scene.pages.get(0).slots[0];
        Scene.put(component.theme, "accent", 0xffc43031);
        ClockFace face = face(scene, true, false);
        Bitmap solid = render(face, W, H);
        Scene.put(component.theme, "timeGradient", 1);
        Bitmap gradient = render(face, W, H);
        float inset = H * .035f;
        float scale = Math.min((W - 2 * inset) / 540f, (H - 2 * inset) / 432f);
        int y = Math.round(H / 2f + (MediaControls.Y - 200) * scale);
        for (int button : new int[]{0, 2}) {
            int x = Math.round(W / 2f + (MediaControls.X[button] - 250) * scale);
            check(solid.getPixel(x, y) == 0xffc43031, "skip button uses the custom solid accent");
            check(gradient.getPixel(x, y) != solid.getPixel(x, y), "skip button glyph uses the theme gradient");
        }
        solid.recycle();
        gradient.recycle();
    }

    private void gradientChecks(){
        prefs.edit().clear().putBoolean("motion",false).commit();
        for(String type:Scene.TYPES){
            for(int style=0;style<count(type);style++){
                Scene scene=model(type,style);
                Scene.Component component=scene.pages.get(0).slots[0];
                if(type.equals("clock")){
                    Scene.put(component.options,"seconds",true);
                    Scene.put(component.styleOptions(),"labels",2);
                }
                ClockFace face=face(scene,true,false);
                face.setLayoutTarget(W,H,null);
                Bitmap solid=render(face,W,H);
                Scene.put(component.theme,"timeGradient",1);
                Bitmap gradient=render(face,W,H);
                String name=type+" / "+component.style;
                try{
                    check(!solid.sameAs(gradient),"daytime gradient changes "+name);
                    if(type.equals("world")&&style==3){
                        check(gradientPixels(solid,gradient,new Rect(195,64,305,73))>8,"daylight orbit arc uses gradient");
                    }else if(type.equals("world")&&style==4){
                        for(int y:new int[]{119,267}){
                            check(gradientPixels(solid,gradient,new Rect(70,y-3,136,y+3))>8,"day band night segment uses gradient");
                            check(gradientPixels(solid,gradient,new Rect(190,y-3,310,y+3))>8,"day band daylight segment uses gradient");
                            check(gradientPixels(solid,gradient,new Rect(55,y-16,445,y-8))>8,"day band current-time marker uses gradient");
                        }
                    }else if(type.equals("alarm")&&style==4){
                        check(gradientPixels(solid,gradient,new Rect(70,242,430,248))>8,"alarm ruler uses gradient");
                        check(gradientPixels(solid,gradient,new Rect(160,214,192,229))>8,"alarm ruler marker uses gradient");
                    }else if(type.equals("calendar")&&style==1){
                        check(gradientPixels(solid,gradient,new Rect(114,139,120,158))>8,"agenda timeline spine uses gradient");
                    }else if(type.equals("weather")&&style==1){
                        check(gradientPixels(solid,gradient,new Rect(54,98,198,238))>20,"weather icon uses gradient");
                    }else if(type.equals("weather")&&style==4){
                        check(gradientPixels(solid,gradient,new Rect(113,173,184,178))>8,"humidity waterline uses gradient");
                    }else if(type.equals("battery")&&style==4){
                        check(gradientPixels(solid,gradient,new Rect(94,141,105,147))>8,"battery ruler marker uses gradient");
                    }
                }finally{
                    solid.recycle();gradient.recycle();
                }
                face.nightPreview=true;
                Bitmap nightGradient=render(face,W,H);
                Scene.put(component.theme,"timeGradient",0);
                Bitmap nightSolid=render(face,W,H);
                try{check(nightSolid.sameAs(nightGradient),"night palette remains solid for "+name);}
                finally{nightSolid.recycle();nightGradient.recycle();}
            }
        }
    }

    private int gradientPixels(Bitmap before,Bitmap after,Rect componentArea){
        ClockLayout.Area cell=ClockLayout.arrange(W,H,null,0).get(0)[0];
        float scale=Math.min(cell.width()/540f,cell.height()/432f);
        float x=(cell.left+cell.right)/2-250*scale,y=(cell.top+cell.bottom)/2-200*scale;
        int left=Math.round(x+componentArea.left*scale),right=Math.round(x+componentArea.right*scale);
        int top=Math.round(y+componentArea.top*scale),bottom=Math.round(y+componentArea.bottom*scale);
        int changed=0;
        for(int row=top;row<bottom;row++)for(int col=left;col<right;col++){
            if(before.getPixel(col,row)!=after.getPixel(col,row))changed++;
        }
        return changed;
    }
}
