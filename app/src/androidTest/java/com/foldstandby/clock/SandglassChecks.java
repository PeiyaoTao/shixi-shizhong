package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;

/** Conserved volume, continuous fall and real reset events, rendered offscreen with isolated settings. */
final class SandglassChecks {
    private static final int W=1080,H=684;
    private final Instrumentation runner;
    private Context context;
    private SharedPreferences prefs;
    private File directory;
    private int assertions,renders;
    private SandglassChecks(Instrumentation runner){this.runner=runner;}
    static Bundle run(Instrumentation runner){return new SandglassChecks(runner).run();}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private void sync(Runnable action){
        final Throwable[] error={null};runner.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private Bundle run(){
        Bundle result=new Bundle();context=runner.getTargetContext();prefs=context.getSharedPreferences("sandglass_checks",0);
        directory=new File(context.getFilesDir(),"qa-0.3.7");directory.mkdirs();
        try{
            sync(()->{try{prefs.edit().clear().putBoolean("motion",true).putBoolean("night",false).putInt("focusMinutes",25).commit();physics();timerEvents();frames();}catch(Exception e){throw new AssertionError(e);}});
            longPress();result.putBoolean("passed",true);result.putString("result","PASS: "+assertions+" sandglass checks, "+renders+" device renders");
        }catch(Throwable error){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(error));}
        finally{prefs.edit().clear().commit();}
        return result;
    }
    private void physics(){
        for(float strength:new float[]{0,.32f,1}){
            float previousSurface=Sandglass.TOP-20,previousPile=Sandglass.BOTTOM+1;
            for(int i=100;i>=0;i--){
                float left=i/100f,level=Sandglass.upperLevel(left,strength),peak=Sandglass.lowerPeak(1-left);
                float upper=Sandglass.upperArea(level,strength),lower=Sandglass.lowerArea(peak);
                check(Math.abs(upper/Sandglass.volume()-left)<.001,"upper sand volume matches remaining "+i);
                check(Math.abs(lower/Sandglass.volume()-(1-left))<.001,"lower sand volume matches elapsed "+i);
                check(Math.abs((upper+lower)/Sandglass.volume()-1)<.002,"sand conserved "+i);
                check(level>=previousSurface-.001f,"upper sand surface progressively sinks");
                check(peak<=previousPile+.001f,"lower sand pile progressively rises");
                previousSurface=level;previousPile=peak;
            }
        }
        float level=Sandglass.upperLevel(.5f,1);
        check(Sandglass.upperSurface(level,0,1)>Sandglass.upperSurface(level,40,1),"upper surface forms a central drainage funnel");
        check(Sandglass.lowerSurface(250,0)<Sandglass.lowerSurface(250,40),"lower sand forms a pile at the impact point");
        check(Sandglass.fallingY(.3f)-Sandglass.fallingY(.2f)>Sandglass.fallingY(.2f)-Sandglass.fallingY(.1f),"grains accelerate while falling");
        check(Sandglass.ease(0)==0&&Sandglass.ease(1)==1,"turn has exact endpoints");
    }
    private void timerEvents(){
        prefs.edit().putLong("focusLeft",600000).putLong("focusEnd",0).commit();
        DeskData.resetFocus(prefs);
        check(prefs.getLong("focusResetAt",-1)>=0,"reset records a shared animation event");
        check(Math.abs(prefs.getFloat("focusResetFrom",-1)-.4f)<.001f,"reset captures the old sand amount");
        check(DeskData.remaining(prefs)==1500000&&prefs.getLong("focusEnd",-1)==0,"reset fills the timer and remains paused");
        long event=prefs.getLong("focusResetAt",-1);DeskData.toggleFocus(prefs);DeskData.toggleFocus(prefs);
        check(prefs.getLong("focusResetAt",-1)==event,"start and pause do not turn the glass");
        prefs.edit().putLong("focusLeft",750000).putLong("focusEnd",0).commit();DeskData.setFocusMinutes(prefs,5);
        check(DeskData.remaining(prefs)==750000&&DeskData.focusTotal(prefs)==1500000,
            "changing the default preserves a paused round and its original total");
        DeskData.toggleFocus(prefs);
        long activeEnd=prefs.getLong("focusEnd",0);
        DeskData.setFocusMinutes(prefs,15);
        check(prefs.getLong("focusEnd",0)==activeEnd&&DeskData.focusTotal(prefs)==1500000,
            "changing the default preserves an active round");
        DeskData.toggleFocus(prefs);
        DeskData.setFocusMinutes(prefs,5);
        DeskData.resetFocus(prefs);
        check(DeskData.remaining(prefs)==300000,"explicit reset uses the new duration");
        check(Math.abs(prefs.getFloat("focusResetFrom",-1)-.5f)<.01f,"explicit reset captures the previous sand ratio");
        prefs.edit().putLong("focusEnd",1).commit();DeskData.toggleFocus(prefs);
        check(prefs.getLong("focusEnd",0)>SystemClock.elapsedRealtime(),"restart after completion starts a fresh timer");
        check(prefs.getFloat("focusResetFrom",-1)==0,"restart after completion also triggers an empty-glass turn");
        prefs.edit().putInt("focusMinutes",25).putLong("focusEnd",0).remove("focusResetAt").commit();DeskData.resetFocus(prefs);
    }
    private Scene scene(){
        Scene scene=Scene.load(prefs);scene.pages.get(0).layout=0;Scene.Component c=scene.pages.get(0).slots[0];
        ComponentStyles.select(c,"focus",3);Scene.put(c.theme,"timeGradient",5);return scene;
    }
    private ClockFace face(){ClockFace f=new ClockFace(context,prefs);f.bind(scene(),0);f.nightPreview=false;return f;}
    private Bitmap render(ClockFace face){
        face.measure(View.MeasureSpec.makeMeasureSpec(W,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(H,View.MeasureSpec.EXACTLY));face.layout(0,0,W,H);
        Bitmap image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);face.draw(new Canvas(image));renders++;return image;
    }
    private void save(Bitmap image,String name)throws IOException{try(FileOutputStream stream=new FileOutputStream(new File(directory,name))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}}
    private void frames()throws Exception{
        check(android.animation.ValueAnimator.areAnimatorsEnabled(),"system animations enabled for visual checks");
        prefs.edit().remove("focusResetAt").commit();ClockFace f=face();
        // Accelerated remaining time, but ordinary frame cadence and gravity, for an inspectable full cycle.
        for(int frame=0;frame<=72;frame++){
            long left=Math.round(1500000*(1-frame/72f));prefs.edit().putLong("focusEnd",SystemClock.elapsedRealtime()+left).putLong("focusLeft",left).apply();
            f.testTime=1000+frame*40;Bitmap image=render(f);
            save(image,"flow-"+frame+".png");
            if(frame%18==0)save(image,"sand-"+(100-frame*100/72)+".png");
            check(Math.abs(f.sandglassForCheck().fractionForCheck()-left/1500000f)<.002,"view sand amount follows the real timer");image.recycle();
        }
        prefs.edit().putLong("focusEnd",0).putLong("focusLeft",0).commit();DeskData.resetFocus(prefs);
        long at=prefs.getLong("focusResetAt",-1);float previous=0;
        for(int frame=0;frame<=42;frame++){
            f.testTime=at+frame*40;Bitmap image=render(f);save(image,"reset-"+frame+".png");
            Sandglass glass=f.sandglassForCheck();float angle=glass.angleForCheck();
            if(frame<27){check(angle<=previous+.001,"reset turn moves continuously in one direction");previous=angle;}
            if(frame==13)check(angle<-70&&angle>-110,"glass physically rotates through the horizontal position");
            if(frame==28)check(glass.isResetting(),"sand settles after the turn");
            if(frame==42)check(!glass.isResetting()&&glass.fractionForCheck()==1,"reset finishes upright with all sand above");
            image.recycle();
        }
        for(int stop=0;stop<3;stop++){
            ClockFace paused=face();paused.testTime=at+500;
            if(stop==0)paused.nightPreview=true;if(stop==1)paused.setMotionActive(false);if(stop==2)prefs.edit().putBoolean("motion",false).commit();
            Bitmap first=render(paused);paused.testTime=at+800;Bitmap next=render(paused);
            check(first.sameAs(next),"night, rest and motion-off skip rotation "+stop);first.recycle();next.recycle();
            paused.nightPreview=false;paused.setMotionActive(true);prefs.edit().putBoolean("motion",true).commit();
            paused.testTime=at+850;Bitmap image=render(paused);check(!paused.sandglassForCheck().isResetting(),"skipped turn never replays on wake");image.recycle();
        }
        prefs.edit().putLong("focusResetAt",10000).putFloat("focusResetFrom",.65f).commit();
        ClockFace a=face();a.testTime=10800;Bitmap image=render(a);image.recycle();
        ClockFace b=face();b.testTime=10800;image=render(b);image.recycle();
        check(Math.abs(a.sandglassForCheck().angleForCheck()-b.sandglassForCheck().angleForCheck())<.001,"recreated previews continue the same reset event");
        for(int frame=0;frame<=48;frame++){
            a.testTime=10000+frame*40;image=render(a);save(image,"partial-reset-"+frame+".png");image.recycle();
        }
        // Previews must visibly empty, then turn, instead of looping particles at a fixed 60%.
        ClockFace demo=face();demo.demo=true;demo.testTime=3000;Bitmap early=render(demo);float initial=demo.sandglassForCheck().fractionForCheck();
        demo.testTime=12000;Bitmap late=render(demo);check(initial>demo.sandglassForCheck().fractionForCheck()+.4f,"preview sand volume progressively decreases");check(!early.sameAs(late),"preview displays different sand levels");early.recycle();late.recycle();
        demo.testTime=Sandglass.DEMO_DRAIN_MS+540;image=render(demo);check(demo.sandglassForCheck().angleForCheck()<-85,"preview includes the reset turn");image.recycle();
        for(int layout=0;layout<4;layout++)for(int scale:new int[]{75,100,125}){
            Scene model=scene();model.pages.get(0).layout=layout;for(Scene.Component c:model.pages.get(0).slots){ComponentStyles.select(c,"focus",3);Scene.put(c.options,"scale",scale);}
            ClockFace sample=new ClockFace(context,prefs);sample.bind(model,0);sample.nightPreview=false;sample.testTime=10800;image=render(sample);
            check(image.getPixel(0,0)==Color.BLACK,"rotating glass stays clipped inside page "+layout+" / "+scale);image.recycle();
        }
    }
    private void longPress()throws Exception{
        final ClockFace[] f={null};final long[] downAt={0};
        sync(()->{
            prefs.edit().putLong("focusEnd",0).putLong("focusLeft",450000).remove("focusResetAt").commit();f[0]=face();f[0].testTime=-1;Bitmap image=render(f[0]);image.recycle();
            downAt[0]=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(downAt[0],downAt[0],MotionEvent.ACTION_DOWN,W*.7f,H*.65f,0);f[0].onTouchEvent(down);down.recycle();
        });
        SystemClock.sleep(650);
        sync(()->{
            MotionEvent up=MotionEvent.obtain(downAt[0],SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,W*.7f,H*.65f,0);f[0].onTouchEvent(up);up.recycle();
            check(DeskData.remaining(prefs)==1500000,"real long press resets timer");
            check(Math.abs(prefs.getFloat("focusResetFrom",-1)-.3f)<.001,"long press captures the old sand amount");
            f[0].testTime=prefs.getLong("focusResetAt",-1)+960;Bitmap image=render(f[0]);image.recycle();
            check(f[0].sandglassForCheck().angleForCheck()<-85,"real long press starts the flip animation");
        });
    }
}
