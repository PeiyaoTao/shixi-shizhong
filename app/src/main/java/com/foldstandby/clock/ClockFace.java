package com.foldstandby.clock;

import android.content.*;
import android.animation.*;
import android.app.KeyguardManager;
import android.graphics.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.*;

public final class ClockFace extends View {
    private static final DateTimeFormatter FMT_24=DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FMT_12=DateTimeFormatter.ofPattern("hh:mm");
    private static final DateTimeFormatter FMT_SEC=DateTimeFormatter.ofPattern("ss");
    private static final DateTimeFormatter FMT_DATE=DateTimeFormatter.ofPattern("M月d日  EEEE",Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter FMT_MONTH=DateTimeFormatter.ofPattern("yyyy年 M月");
    private static final DateTimeFormatter FMT_WORLD=DateTimeFormatter.ofPattern("M月d日 · XXX");
    private static final DateTimeFormatter FMT_WEEKDAY=DateTimeFormatter.ofPattern("EEEE",Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter FMT_SHORT_DATE=DateTimeFormatter.ofPattern("M月d日");
    private static final String[] WEEKDAYS={"一","二","三","四","五","六","日"};
    private static final float[] SIN_60=new float[60],COS_60=new float[60];
    static{for(int i=0;i<60;i++){double a=i*Math.PI/30;SIN_60[i]=(float)Math.sin(a);COS_60[i]=(float)Math.cos(a);}}
    private static final String[][] PREVIEW_SLOTS={
        {"split"},{"digital","date"},{"analog","weather"},{"music","digital"},{"analog","focus"},{"digital","weather","calendar","battery"}
    };

    private final TextPaint p=new TextPaint(3);
    private final Rect digitBounds=new Rect(),separatorBounds=new Rect();
    private final Shader[] unitGradients=new Shader[TextGradients.COLORS.length];
    private final Matrix gradientMatrix=new Matrix();
    private final float[] gradientValues=new float[9];
    private int cachedGradientId=-1;private float gradientLeft,gradientTop,gradientRight,gradientBottom;
    private int cachedYear=Integer.MIN_VALUE;private final int[] monthOffsets=new int[12],monthLengths=new int[12];
    private final Typeface regular=Typeface.create("sans-serif",Typeface.NORMAL);
    private final Typeface bold=Typeface.create("sans-serif",Typeface.BOLD);
    private final Typeface numbers=Typeface.create("sans-serif-condensed",Typeface.BOLD);
    private final RectF[] cells={new RectF(),new RectF(),new RectF(),new RectF()};
    private final RectF arcRect=new RectF(115,65,385,335);
    private final RectF batteryArc=new RectF();
    private final RectF barBgRect=new RectF(80,290,420,298);
    private final RectF barFgRect=new RectF();
    private final RectF artRect=new RectF(185,30,315,160);
    private final RectF cardRect=new RectF(12,12,488,388);
    private final Path artClip=new Path();
    private final Map<String,Sandglass> sandglasses=new HashMap<>();
    private final AlarmArtwork alarmArtwork=new AlarmArtwork();
    private final WeatherArtwork weatherArtwork=new WeatherArtwork();
    private MusicArtwork musicArtwork;
    private MusicSpectrum musicSpectrum;
    private boolean spectrumUsed;
    private final Rect visibleMusicRect = new Rect();
    private String lyricSource;
    private LrcLyrics lyrics;
    private long nextLyricAt, scheduledLyricAt = Long.MAX_VALUE;
    private final Runnable lyricTick = () -> { scheduledLyricAt = Long.MAX_VALUE; invalidate(); };
    private final SharedPreferences prefs;
    private final Dashboard config;
    private int ink,muted,gradient; private boolean dark;
    private Scene scene; private Scene.Page page; private Scene.Component component;
    private boolean bound; private String loaded=""; private int pageIndex;
    private float pagePosition,sceneShiftX,sceneShiftY;private int pageMotionStyle;
    private ValueAnimator pageAnimator;private boolean motionActive=true;private int fadeFromPage=-1;private float fadeAmount;
    private final Runnable batteryChanged=this::invalidate;
    private final Runnable alarmChanged=()->{invalidate();if(this.externalTick)ClockActivity.onPostureOrDisplayChanged();};
    private int drawingSlot,pressedMusicSlot=-1,pressedMusicButton=-1,feedbackSlot=-1,feedbackButton=-1;private long feedbackAt;
    private final Path iconPath=new Path();
    private MediaController observedMedia;
    private static final int[] MUSIC_ACCESS_ACTIONS={0x01020001,0x01020002,0x01020003};
    private static final int ALARM_ACCESS_ACTION=0x01020004;
    private static final int TODO_ACCESS_ACTION=0x01020010;
    private final MediaController.Callback mediaEvents=new MediaController.Callback(){public void onPlaybackStateChanged(PlaybackState state){invalidate();if(externalTick)ClockActivity.onPostureOrDisplayChanged();}public void onMetadataChanged(MediaMetadata metadata){invalidate();if(externalTick)ClockActivity.onPostureOrDisplayChanged();}public void onSessionDestroyed(){if(DeskData.media!=null&&(DeskData.media==observedMedia||(observedMedia!=null&&DeskData.media.getSessionToken().equals(observedMedia.getSessionToken()))))DeskData.media=null;invalidate();if(externalTick)ClockActivity.onPostureOrDisplayChanged();}};
    private void observeMedia(){MediaController next=null;if(isAttachedToWindow()&&DeskData.musicAllowed)for(int i=0;i<page.count();i++)if(page.slots[i].type.equals("music"))next=DeskData.media;if(next==observedMedia)return;if(next!=null&&observedMedia!=null&&next.getSessionToken().equals(observedMedia.getSessionToken()))return;if(observedMedia!=null)observedMedia.unregisterCallback(mediaEvents);observedMedia=next;if(next!=null)next.registerCallback(mediaEvents,handler);}
    Boolean nightPreview; long testTime=-1;boolean demo;
    private boolean longPressed;
    private final Runnable longPress=()->{if(isLongClickable())longPressed=performLongClick();};
    interface PageSwipe{void swipe(int direction);}
    PageSwipe pageSwipe; Runnable interaction;
    Runnable sceneChanged;
    interface OpenTodo {
        void open(Scene.Component component, TodoStore.View view, String task, boolean add);
    }
    TodoStore todoStore;
    OpenTodo openTodo;
    java.util.function.Consumer<TodoTask> toggleTodo;
    private final Set<String> expandedTodo = new HashSet<>();
    private final Map<String,Float> todoOffsets=new HashMap<>();
    private int todoTouchSlot=-1;
    private boolean todoDragging;
    private float todoDownOffset;
    private final Map<String,FlipState> flips=new HashMap<>();
    private final Camera camera=new Camera(); private final Matrix matrix=new Matrix();
    // Scene binding also serves the live clock; feedback is enabled only by its host.
    boolean flipFeedbackEnabled;
    private long soundMinute=-1,hapticMinute=-1; private ToneGenerator tone;
    private static final class FlipState {String old,value;long started;}
    private float downX,downY; private long downTime;
    interface SlotTap{void tap(int slot);}
    SlotTap slotTap;
    int editorSlot=-1;
    private float targetWidthDp, targetHeightDp;
    private ClockLayout.Area targetFold;
    private float arrangedWidthDp, arrangedHeightDp;
    private ClockLayout.Area arrangedFold;
    private int arrangedLayout = -1, subpage;
    private java.util.List<ClockLayout.Area[]> arrangedPages;

    void setLayoutTarget(float widthDp, float heightDp, ClockLayout.Area fold) {
        if (targetWidthDp == widthDp && targetHeightDp == heightDp && sameArea(targetFold, fold)) return;
        targetWidthDp = widthDp;
        targetHeightDp = heightDp;
        targetFold = fold;
        invalidate();
    }

    private static boolean sameArea(ClockLayout.Area first, ClockLayout.Area second) {
        return first == second || first != null && second != null && first.left == second.left
                && first.top == second.top && first.right == second.right && first.bottom == second.bottom;
    }

    private java.util.List<ClockLayout.Area[]> layoutPages(int layout) {
        float density = getResources().getDisplayMetrics().density;
        float width = targetWidthDp > 0 ? targetWidthDp : getWidth() / density;
        float height = targetHeightDp > 0 ? targetHeightDp : getHeight() / density;
        if (layout != arrangedLayout || width != arrangedWidthDp || height != arrangedHeightDp
                || !sameArea(targetFold, arrangedFold)) {
            arrangedPages = ClockLayout.arrange(width, height, targetFold, layout);
            arrangedLayout = layout;
            arrangedWidthDp = width;
            arrangedHeightDp = height;
            arrangedFold = targetFold;
        }
        return arrangedPages;
    }

    boolean moveSubpage(int direction) {
        resolve();
        int count = layoutPages(page.layout).size();
        int next = Math.min(subpage, count - 1) + direction;
        if (next < 0 || next >= count) return false;
        subpage = next;
        pressedMusicSlot = -1;
        invalidate();
        return true;
    }

    boolean hasSubpages() {
        resolve();
        return layoutPages(page.layout).size() > 1;
    }

    void selectLastSubpage() {
        resolve();
        restoreSubpage(layoutPages(page.layout).size() - 1);
    }

    int currentSubpage() {
        resolve();
        return Math.min(subpage, layoutPages(page.layout).size() - 1);
    }

    void restoreSubpage(int value) {
        subpage = Math.max(0, value);
        invalidate();
    }
    String pageIdForEditor(){return page==null?"":page.id;}
    int previewPreset=-1;
    boolean externalTick;
    boolean managedRefresh;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){
        if(externalTick||managedRefresh)return;
        if(previewPreset<0&&!bound)DeskData.refresh(getContext(),false);
        invalidate();
        handler.postDelayed(this,nextDelayMs());
    }};
    public ClockFace(Context c){this(c,c.getSharedPreferences("clock",0));}
    ClockFace(Context c,SharedPreferences settings){
        super(c);prefs=settings;config=new Dashboard(settings);
        artClip.addRoundRect(artRect,16,16,Path.Direction.CW);
        setContentDescription("桌面时钟");
    }
    void bind(Scene value,int index){bound=true;scene=value;selectPage(Math.max(0,Math.min(index,value.pages.size()-1)));}
    private void cancelPageAnimator(){if(pageAnimator!=null){pageAnimator.removeAllListeners();pageAnimator.cancel();pageAnimator=null;}}
    void selectPage(int index){cancelPageAnimator();pageIndex=index;pagePosition=index;fadeFromPage=-1;subpage=0;flips.clear();pressedMusicSlot=-1;invalidate();}
    void animatePage(int index,int direction,int style,int duration){if(scene==null||index<0||index>=scene.pages.size())return;if(style==2||!motionActive||!prefs.getBoolean("motion",true)||!ValueAnimator.areAnimatorsEnabled()||!isAttachedToWindow()){selectPage(index);return;}int previous=fadeFromPage>=0&&fadeAmount<.5f?fadeFromPage:pageIndex;cancelPageAnimator();pageIndex=index;subpage=0;pageMotionStyle=style;fadeFromPage=style==1?previous:-1;fadeAmount=0;pressedMusicSlot=-1;float target=PageMotion.destination(pagePosition,index,direction,scene.pages.size());if(Math.abs(target-pagePosition)<.001f){selectPage(index);return;}pageAnimator=ValueAnimator.ofFloat(pagePosition,target);pageAnimator.setDuration(Math.max(120,Math.min(800,duration)));pageAnimator.setInterpolator(new android.view.animation.PathInterpolator(.22f,.75f,.25f,1f));pageAnimator.addUpdateListener(animation->{pagePosition=(float)animation.getAnimatedValue();fadeAmount=animation.getAnimatedFraction();invalidate();});pageAnimator.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator animation){pageAnimator=null;pagePosition=pageIndex;fadeFromPage=-1;invalidate();}});pageAnimator.start();}
    boolean pageAnimatingForCheck(){return pageAnimator!=null;}
    float pagePositionForCheck(){return pagePosition;}
    void positionForCheck(float position,int style){cancelPageAnimator();pagePosition=position;pageMotionStyle=style;fadeFromPage=-1;invalidate();}
    void setMotionActive(boolean active){motionActive=active;if(!active){resetMusicSpectrum();updateLyricTick(Long.MAX_VALUE);int savedSubpage=subpage;selectPage(pageIndex);subpage=savedSubpage;handler.removeCallbacks(longPress);}invalidate();}
    private void resolve(){
        if(!bound){String json=prefs.getString(Scene.KEY,"");if(scene==null||json.isEmpty()||!json.equals(loaded)){scene=Scene.load(prefs);loaded=json;}pageIndex=Math.max(0,Math.min(pageIndex,scene.pages.size()-1));}
        page=scene.pages.get(pageIndex);config.layout=page.layout;for(int i=0;i<4;i++)config.slots[i]=page.slots[i].type;
    }
    private long wallTime(){return testTime>=0?testTime:System.currentTimeMillis();}
    private ZonedDateTime now(){return Instant.ofEpochMilli(wallTime()).atZone(ZoneId.systemDefault());}
    protected void onAttachedToWindow(){super.onAttachedToWindow();if(!managedRefresh){DeskData.observeBattery(getContext(),batteryChanged);AlarmData.observe(getContext(),alarmChanged);}handler.removeCallbacks(refresh);if(!externalTick&&!managedRefresh)handler.post(refresh);}
    protected void onDetachedFromWindow(){resetMusicSpectrum();updateLyricTick(Long.MAX_VALUE);AlarmData.forget(getContext(),alarmChanged);DeskData.forgetBattery(getContext(),batteryChanged);if(observedMedia!=null){observedMedia.unregisterCallback(mediaEvents);observedMedia=null;}cancelPageAnimator();pagePosition=pageIndex;handler.removeCallbacksAndMessages(null);if(tone!=null){tone.release();tone=null;}super.onDetachedFromWindow();}
    protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE){resetMusicSpectrum();updateLyricTick(Long.MAX_VALUE);cancelPageAnimator();pagePosition=pageIndex;}else invalidate();}
    long nextDelayMs(){
        long now=System.currentTimeMillis();
        if(previewPreset>=0)return 60000L-(now%60000L)+50L;
        resolve();
        boolean fast=false;for(int i=0;i<page.count();i++)if(page.slots[i].bool("seconds",false)&&!night())fast=true;
        if(!fast){
            boolean focusRunning=prefs.getLong("focusEnd",0)>0&&DeskData.remaining(prefs)>0;
            boolean musicPlaying=DeskData.isMusicPlaying();
            for(int i=0;i<config.count();i++){
                String s=config.slots[i];
                if((focusRunning&&"focus".equals(s))||(musicPlaying&&"music".equals(s))){fast=true;break;}
            }
        }
        return fast?(1000L-(now%1000L)+5L):(60000L-(now%60000L)+5L);
    }
    public boolean night(){if(nightPreview!=null)return nightPreview;LocalTime t=LocalTime.now();int minute=t.getHour()*60+t.getMinute(),start=prefs.getInt("nightStart",22)*60+prefs.getInt("nightStartMinute",0),end=prefs.getInt("nightEnd",7)*60+prefs.getInt("nightEndMinute",0);return prefs.getBoolean("night",true)&&(start<end?minute>=start&&minute<end:minute>=start||minute<end);}
    private int color(String role){return scene.color(page,component,role,dark,prefs.getBoolean("nightOwnColors",false));}
    private void textColor(int color,float l,float t,float r,float b){
        p.setColor(color);
        if(p.getShader()==null&&color!=Color.BLACK&&(color==ink||color==muted)){
            themeColor(color,color==ink?255:176,l,t,r,b);
        }
    }
    private void txt(Canvas c,String s,float x,float y,float size,int color,Typeface font,float width){p.setStyle(Paint.Style.FILL);p.setTypeface(font);p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);if(p.measureText(s)>width)p.setTextSize(size*width/p.measureText(s));textColor(color,x-width/2,y-size,x+width/2,y);c.drawText(s,x,y,p);p.setShader(null);}
    private void timeText(Canvas c,String s,float x,float y,float size,float width){themeShader(x-width/2,y-size,x+width/2,y);txt(c,s,x,y,size,ink,font(component),width);p.setShader(null);}
    /** Keep the separator compact and vertically centered within the time numerals. */
    private void digitalTimeText(Canvas c,String s,float x,float y,float size,float width){
        int colon=s.indexOf(':');
        if(colon<0){timeText(c,s,x,y,size,width);return;}
        p.setStyle(Paint.Style.FILL);p.setTypeface(font(component));p.setTextSize(size);
        float hoursWidth=p.measureText(s,0,colon),minutesWidth=p.measureText(s,colon+1,s.length());
        p.setTextSize(size*.6f);float colonWidth=p.measureText(":");
        float measured=hoursWidth+colonWidth+minutesWidth,fit=Math.min(1f,width/Math.max(1f,measured));
        float left=x-measured*fit/2;
        p.setColor(ink);p.setTextAlign(Paint.Align.LEFT);p.setTextSize(size*fit);
        p.getTextBounds("0",0,1,digitBounds);
        themeShader(x-width/2,y-size,x+width/2,y);
        c.drawText(s,0,colon,left,y,p);
        c.drawText(s,colon+1,s.length(),left+(hoursWidth+colonWidth)*fit,y,p);
        p.setTextSize(size*.6f*fit);p.getTextBounds(":",0,1,separatorBounds);
        float baseline=y+(digitBounds.top+digitBounds.bottom-separatorBounds.top-separatorBounds.bottom)*.5f;
        c.drawText(":",left+hoursWidth*fit,baseline,p);
        p.setShader(null);
    }
    private void themeShader(float x,float y,float right,float bottom){
        int index=gradient;
        if(index==0){p.setShader(null);return;}
        Shader shader=unitGradients[index];
        if(shader==null){shader=TextGradients.unitShader(index);unitGradients[index]=shader;}
        if(cachedGradientId!=index||gradientLeft!=x||gradientTop!=y||gradientRight!=right||gradientBottom!=bottom){
            cachedGradientId=index;gradientLeft=x;gradientTop=y;gradientRight=right;gradientBottom=bottom;
            TextGradients.applyBounds(shader,gradientMatrix,gradientValues,x,y,right,bottom);
        }
        p.setShader(shader);
    }
    private void themeColor(int color,int opacity,float l,float t,float r,float b){
        p.setColor(color);
        themeShader(l,t,r,b);
        if(gradient>0)p.setAlpha(Math.min(p.getAlpha(),opacity));
    }
    private void line(Canvas c,float x,float y,float ex,float ey,int color,float weight){p.setColor(color);strokeLine(c,x,y,ex,ey,weight);}
    private void strokeLine(Canvas c,float x,float y,float ex,float ey,float weight){p.setStrokeWidth(weight);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x,y,ex,ey,p);}
    private void themedLine(Canvas c,float x,float y,float ex,float ey,int color,float weight,int opacity){
        themeColor(color,opacity,x,y,ex,ey);strokeLine(c,x,y,ex,ey,weight);p.setShader(null);
    }
    private String time(ZonedDateTime t){return t.format(component==null||component.bool("h24",true)?FMT_24:FMT_12);}
    private void header(Canvas c,String s){txt(c,s,250,48,20,muted,regular,430);}
    private void foot(Canvas c,String s){txt(c,s,250,365,18,muted,regular,430);}
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(DeskData.systemStateAt==0)DeskData.updateSystemState(getContext(),true);
        resolve();
        observeMedia();
        spectrumUsed = false;
        nextLyricAt = Long.MAX_VALUE;
        canvas.drawColor(Color.BLACK);int lower=(int)Math.floor(pagePosition);float fraction=pagePosition-lower;
        if(fadeFromPage>=0&&pageAnimator!=null){drawFadedPage(canvas,fadeFromPage,1-fadeAmount);drawFadedPage(canvas,pageIndex,fadeAmount);}else if(fraction<.001f)drawPage(canvas,PageMotion.index(lower,scene.pages.size()));else{drawMovingPage(canvas,lower,1-fraction);drawMovingPage(canvas,lower+1,fraction);}
        if (!spectrumUsed) resetMusicSpectrum();
        updateLyricTick(nextLyricAt);
        resolve();
    }
    private void drawFadedPage(Canvas canvas,int index,float alpha){int save=canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),Math.round(255*alpha));drawPage(canvas,index);canvas.restoreToCount(save);}
    private void drawMovingPage(Canvas canvas,int virtual,float visibility){int save=canvas.save();canvas.clipRect(0,0,getWidth(),getHeight());if(pageMotionStyle==1)canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),Math.round(255*visibility));else{canvas.translate(0,(virtual-pagePosition)*getHeight());float scale=.985f+.015f*visibility;canvas.scale(scale,scale,getWidth()/2f,getHeight()/2f);}drawPage(canvas,PageMotion.index(virtual,scene.pages.size()));canvas.restoreToCount(save);}
    private void drawPage(Canvas canvas,int index){
        page=scene.pages.get(index);config.layout=page.layout;for(int i=0;i<4;i++)config.slots[i]=page.slots[i].type;
        if(previewPreset>=0){
            String[] ps=PREVIEW_SLOTS[previewPreset];
            System.arraycopy(ps,0,config.slots,0,ps.length);
            config.layout=previewPreset==0?0:previewPreset==5?3:1;
        }
        dark=night();component=null;
        java.util.List<ClockLayout.Area[]> pages = layoutPages(config.layout);
        int displayedSubpage = index == pageIndex ? Math.min(subpage, pages.size() - 1) : 0;
        if (index == pageIndex && editorSlot >= 0 && editorSlot < config.count()) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i)[editorSlot] != null) displayedSubpage = i;
            }
        }
        ClockLayout.Area[] areas = pages.get(displayedSubpage);
        float scaleX = getWidth() / Math.max(1f, arrangedWidthDp);
        float scaleY = getHeight() / Math.max(1f, arrangedHeightDp);
        int lastVisibleSlot = 0;
        for (int i = 0; i < cells.length; i++) {
            ClockLayout.Area area = i < areas.length ? areas[i] : null;
            if (area == null) cells[i].setEmpty();
            else {
                cells[i].set(area.left * scaleX, area.top * scaleY, area.right * scaleX, area.bottom * scaleY);
                lastVisibleSlot = i;
            }
        }
        long minute=System.currentTimeMillis()/60000L;
        float shiftX=externalTick?((minute%7)-3)*6f:0f;
        float shiftY=externalTick?(((minute/7)%5)-2)*7f:0f;
        sceneShiftX=shiftX;sceneShiftY=shiftY;
        for(int i=0;i<config.count();i++){
            if (cells[i].isEmpty()) continue;
            drawingSlot=i;
            p.reset();p.setFlags(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            component=page.slots[i];ink=color(component.type.equals("clock")||component.type.equals("world")?"time":"accent");muted=color("secondary");
            gradient=dark?0:scene.gradient(page,component);
            RectF r=cells[i];canvas.save();canvas.clipRect(r);
            float scale=Math.min(r.width()/540f,r.height()/432f);
            canvas.translate(r.centerX()+(shiftX-250f)*scale,r.centerY()+(shiftY-200f)*scale);
            canvas.scale(scale,scale);
            if(!component.type.equals("clock")){float cs=component.options.optInt("scale",100)/100f;if(Math.abs(cs-1f)>.001f){canvas.translate(250,200);canvas.scale(cs,cs);canvas.translate(-250,-200);}}
            if(!dark&&component.bool("card",!prefs.getBoolean("simple",true))){
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);
                themeColor(color("plate"),56,cardRect.left,cardRect.top,cardRect.right,cardRect.bottom);
                canvas.drawRoundRect(cardRect,24,24,p);
                p.setShader(null);p.setStyle(Paint.Style.FILL);
            }
            if(component.type.equals("clock"))clock(canvas,component);else render(canvas,config.slots[i]);canvas.restore();
        }
        if(slotTap!=null&&editorSlot>=0&&editorSlot<config.count()){
            RectF area=new RectF(cells[editorSlot]);area.inset(2,2);p.setShader(null);p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2,getResources().getDisplayMetrics().density*1.5f));p.setColor(UiKit.ACCENT);
            canvas.drawRoundRect(area,8,8,p);p.setStyle(Paint.Style.FILL);
        }
        if (pages.size() > 1 && !cells[lastVisibleSlot].isEmpty()) {
            RectF area = cells[lastVisibleSlot];
            float textSize = Math.min(area.height() * .12f, 12 * getResources().getDisplayMetrics().scaledDensity);
            p.reset();
            p.setAntiAlias(true);
            p.setColor(dark ? ink : UiKit.MUTED);
            p.setTextSize(textSize);
            p.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText((displayedSubpage + 1) + "/" + pages.size(),
                    area.right - textSize * .35f, area.bottom - textSize * .25f, p);
        }
    }
    private void render(Canvas c,String type){ZonedDateTime now=now();String date=now.format(FMT_DATE);
        switch(type){
            case "split": case "digital":
                if(prefs.getBoolean("date",true))header(c,date);
                if(type.equals("split")){
                    String[] t=time(now).split(":");txt(c,t[0],137,277,200,ink,numbers,215);themeShader(255,77,470,277);txt(c,t[1],363,277,200,dark?ink:0xffe8eaf0,numbers,215);
                    dot(c,250,194,4);dot(c,250,235,4);
                }else txt(c,time(now),250,263,158,ink,numbers,450);
                if(prefs.getBoolean("seconds",false)&&!dark)foot(c,now.format(FMT_SEC));else if(!prefs.getBoolean("h24",true))foot(c,now.getHour()<12?"上午":"下午");
                break;
            case "analog":
                for(int n=0;n<60;n++){
                    boolean major=n%5==0;float r=major?151:160;
                    themeColor(major?ink:muted,major?255:150,82,32,418,368);
                    strokeLine(c,250+SIN_60[n]*r,200-COS_60[n]*r,250+SIN_60[n]*168,200-COS_60[n]*168,major?3:1);
                }
                themeShader(82,32,418,368);
                hand(c,(now.getHour()%12+now.getMinute()/60f)*30,88,7,ink);hand(c,now.getMinute()*6,131,4,dark?ink:0xffeeeeed);
                if(prefs.getBoolean("seconds",false)&&!dark)hand(c,now.getSecond()*6,140,2,muted);
                dot(c,250,200,7);break;
            case "date":month(c,now);break;
            case "calendar":
                agenda(c);break;
            case "todo":todo(c);break;
            case "alarm":alarm(c,now);break;
            case "weather":weather(c);break;
            case "music":music(c);break;
            case "focus":
                focus(c);break;
            case "battery":battery(c);break;
            case "world":
                world(c,now);break;
        }
    }
    private Typeface font(Scene.Component slot){int f=slot.options.optInt("font",0);return f==1?regular:f==2?Typeface.MONOSPACE:f==3?Typeface.SERIF:numbers;}
    private void clock(Canvas c,Scene.Component slot){
        ZonedDateTime t=now();JSONObject o=slot.styleOptions();boolean seconds=slot.bool("seconds",false)&&!dark;
        String value=time(t);float size=slot.options.optInt("scale",100)/100f;
        c.save();c.translate(250,200);c.scale(size,size);c.translate(-250,-200);
        if(slot.style.equals("analog")||slot.style.equals("industrial")){analog(c,t,o,slot.style.equals("industrial"),seconds);c.restore();return;}
        if(slot.bool("date",true)&&!dark)txt(c,t.format(FMT_DATE),250,48,20,ink,regular,430);
        switch(slot.style){
            case "flip":
                String[] parts=value.split(":");flip(c,slot.id+"h",parts[0],20,94,216,228,170,o,true);flip(c,slot.id+"m",parts[1],264,94,216,228,170,o,true);
                p.setColor(ink);themeShader(20,94,480,322);c.drawCircle(250,189,3,p);c.drawCircle(250,226,3,p);p.setShader(null);
                if(seconds){if(o.optBoolean("flipSeconds",false))flip(c,slot.id+"s",t.format(FMT_SEC),216,334,68,49,38,o,true);else foot(c,t.format(FMT_SEC));}
                break;
            case "led":electronic(c,value,seconds?t.format(FMT_SEC):null,o,false);break;
            case "dot":electronic(c,value,seconds?t.format(FMT_SEC):null,o,true);break;
            default:if(seconds){digitalTimeText(c,value,208,260,142,360);timeText(c,t.format(FMT_SEC),438,260,54,72);}else digitalTimeText(c,value,250,260,158,450);
        }
        if(!seconds&&!slot.bool("h24",true))foot(c,t.getHour()<12?"上午":"下午");c.restore();
    }
    private void analog(Canvas c,ZonedDateTime t,JSONObject o,boolean industrial,boolean seconds){
        if(industrial&&!dark){p.setColor(Color.BLACK);c.drawRoundRect(67,17,433,383,24,24,p);themedOutline(c,67,17,433,383,24,color("plate"),1.5f,56);}
        boolean major=o.optBoolean("major",true),minor=o.optBoolean("minor",true);int labels=o.optInt("labels",industrial?1:0);
        for(int n=0;n<60;n++){boolean hour=n%5==0;if(hour&&!major||!hour&&!minor)continue;float outer=164;
            float inner=outer-(hour?o.optInt("majorLength",industrial?19:15):o.optInt("minorLength",6));float weight=hour?o.optInt("majorWidth",industrial?5:3):o.optInt("minorWidth",1);
            themeColor(color(hour?"major":"minor"),hour?255:150,86,36,414,364);
            strokeLine(c,250+SIN_60[n]*inner,200-COS_60[n]*inner,250+SIN_60[n]*outer,200-COS_60[n]*outer,weight);
        }
        p.setShader(null);
        String[] roman={"XII","I","II","III","IV","V","VI","VII","VIII","IX","X","XI"};
        if(labels>0)for(int n=0;n<12;n++){if(labels==1&&n%3!=0)continue;int idx=n*5;float x=250+SIN_60[idx]*125,y=200-COS_60[idx]*125+8;themeShader(86,36,414,364);txt(c,labels==3?roman[n]:String.valueOf(n==0?12:n),x,y,industrial?23:21,color("numerals"),font(component),54);}
        boolean smooth=seconds&&o.optBoolean("smooth",false);float sec=t.getSecond()+(smooth?t.getNano()/1000000000f:0);
        float strength=o.optInt("handWidth",industrial?9:6);
        themeShader(100,50,400,350);hand(c,(t.getHour()%12+t.getMinute()/60f)*30,labels>0?72:88,strength,color("hour"));hand(c,(t.getMinute()+sec/60f)*6,labels>0?109:131,Math.max(2,strength*.55f),color("minute"));
        if(seconds){hand(c,sec*6,143,industrial?2.5f:1.5f,color("second"));if(smooth&&testTime<0)animateEffect(33);}
        themeShader(240,190,260,210);p.setColor(color("hour"));c.drawCircle(250,200,industrial?8:6,p);p.setShader(null);if(component.bool("date",true)&&!dark)txt(c,t.format(FMT_DATE),250,382,12,ink,regular,400);
    }
    private void flip(Canvas c,String key,String value,float x,float y,float w,float h,float textSize,JSONObject o,boolean animate){
        long current=wallTime();FlipState f=flips.get(key);if(f==null){f=new FlipState();f.value=value;f.old=value;flips.put(key,f);}
        if(!value.equals(f.value)){f.old=f.value;f.value=value;f.started=current;
            flipFeedback(o,current);
        }
        float progress=Math.min(1,(current-f.started)/(float)o.optInt("duration",420));boolean moving=animate&&progress<1&&!f.old.equals(f.value)&&!dark;
        c.save();c.translate(x,y);p.setColor(Color.BLACK);c.drawRoundRect(0,0,w,h,12,12,p);
        if(!moving){flipHalf(c,value,w,h,textSize,0,false);flipHalf(c,value,w,h,textSize,1,false);}
        else{
            flipHalf(c,f.value,w,h,textSize,0,false);flipHalf(c,f.old,w,h,textSize,1,false);
            c.save();camera.save();camera.setLocation(0,0,-9);camera.rotateX(progress<.5f?-180*progress:180*(1-progress));camera.getMatrix(matrix);camera.restore();matrix.preTranslate(-w/2,-h/2);matrix.postTranslate(w/2,h/2);c.concat(matrix);
            int half=progress<.5f?0:1;flipHalf(c,half==0?f.old:f.value,w,h,textSize,half,true);p.setColor(Color.argb((int)(110*Math.sin(progress*Math.PI)),0,0,0));c.drawRect(0,half==0?0:h/2,w,half==0?h/2:h,p);c.restore();
            if(testTime<0)postInvalidateOnAnimation();
        }
        line(c,1,h/2,w-1,h/2,0xff070a0e,2);
        themeColor(0xff414955,110,0,h/2,w,h/2);
        c.drawRoundRect(0,h/2-6,4,h/2+6,2,2,p);c.drawRoundRect(w-4,h/2-6,w,h/2+6,2,2,p);
        p.setShader(null);c.restore();
    }
    private void flipFeedback(JSONObject options,long current){
        if(!flipFeedbackEnabled||demo||dark||!motionActive||!isAttachedToWindow()||!isShown()||getWindowVisibility()!=VISIBLE||page!=scene.pages.get(pageIndex))return;
        long minute=current/60000;
        if(options.optBoolean("haptic",false)&&hapticMinute!=minute){
            hapticMinute=minute;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if(options.optBoolean("sound",false)&&soundMinute!=minute){
            soundMinute=minute;
            try{
                if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_MUSIC,25);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP,35);
            }catch(RuntimeException error){
                android.util.Log.w("ClockFace","Unable to play flip sound",error);
            }
        }
    }
    private void flipHalf(Canvas c,String value,float w,float h,float textSize,int half,boolean moving){
        c.save();c.clipRect(0,half==0?0:h/2,w,half==0?h/2:h);p.setColor(Color.BLACK);c.drawRoundRect(0,0,w,h,12,12,p);
        p.setTypeface(font(component));p.setTextSize(textSize);p.setTextAlign(Paint.Align.CENTER);p.setColor(ink);float measured=p.measureText(value);if(measured>w-18)p.setTextSize(textSize*(w-18)/measured);
        Paint.FontMetrics fm=p.getFontMetrics();themeShader(0,0,w,h);c.drawText(value,w/2,h/2-(fm.ascent+fm.descent)/2,p);p.setShader(null);
        themedOutline(c,.5f,.5f,w-.5f,h-.5f,12,color("plate"),1,52);c.restore();
    }
    private static final int[] SEGMENTS={0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
    private void electronic(Canvas c,String value,String seconds,JSONObject o,boolean dot){c.save();if(seconds!=null){c.translate(0,32);c.scale(.8f,.8f);}if(dot)dots(c,value,o);else segments(c,value,o);c.restore();if(seconds!=null){c.save();c.translate(402,174);c.scale(.34f,.34f);if(dot)dots(c,seconds,o);else segments(c,seconds,o);c.restore();}}
    private void segments(Canvas c,String value,JSONObject o){
        float x=24,y=132,unit=1.5f;for(char ch:value.toCharArray()){
            if(ch==':'){p.setColor(ink);themeShader(24,132,460,260);c.drawCircle(x+12,y+40,4,p);c.drawCircle(x+12,y+94,4,p);p.setShader(null);x+=35;continue;}
            int bits=SEGMENTS[ch-'0'];float[][] lines={{9,3,42,3},{46,8,46,38},{46,46,46,76},{9,81,42,81},{4,46,4,76},{4,8,4,38},{9,42,42,42}};
            for(int i=0;i<7;i++){boolean on=(bits&(1<<i))!=0;float[] l=lines[i];p.setStrokeCap(Paint.Cap.SQUARE);p.setStrokeWidth(o.optInt("thickness",5)*unit);p.setColor(on?ink:softInk(24));themeShader(24,132,460,260);c.drawLine(x+l[0]*unit,y+l[1]*unit,x+l[2]*unit,y+l[3]*unit,p);}x+=100;p.setShader(null);
        }
    }
    private static final String[] DOTS={"01110100011001110101110011000101110","00100011000010000100001000010001110","01110100010000100010001000100011111","11110000010000101110000010000111110","00010001100101010010111110001000010","11111100001111000001000011000101110","00110010001000011110100011000101110","11111000010001000100010000100001000","01110100011000101110100011000101110","01110100011000101111000010001001100"};
    private void dots(Canvas c,String value,JSONObject o){float start=28,top=133,step=17;for(char ch:value.toCharArray()){
        if(ch==':'){p.setColor(ink);themeShader(28,133,460,250);c.drawCircle(start+8,top+step*2,4,p);c.drawCircle(start+8,top+step*4,4,p);p.setShader(null);start+=34;continue;}
        String map=DOTS[ch-'0'];for(int row=0;row<7;row++)for(int col=0;col<5;col++){boolean on=map.charAt(row*5+col)=='1';p.setColor(on?ink:softInk(18));themeShader(28,133,460,250);float x=start+col*step,y=top+row*step,r=o.optInt("radius",4);if(o.optBoolean("square",false))c.drawRect(x-r,y-r,x+r,y+r,p);else c.drawCircle(x,y,r,p);}start+=100;p.setShader(null);}}
    private void hand(Canvas c,float angle,float length,float weight,int color){double a=Math.toRadians(angle);line(c,250,200,250+(float)Math.sin(a)*length,200-(float)Math.cos(a)*length,color,weight);}
    private String privateAgenda(boolean today){if(demo)return "09:30  规划今日\n14:00  项目讨论\n18:30  阅读";if(getContext().checkSelfPermission(android.Manifest.permission.READ_CALENDAR)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return "未授权日历";if(DeskData.keyguardLocked&&!prefs.getBoolean("private",false))return "解锁查看事项";String value=today?DeskData.todayAgenda:DeskData.agenda;return value.isEmpty()?"读取中":value;}
    private int variant(){return ComponentStyles.selectedIndex(component);}
    private void themedOutline(Canvas c,float l,float t,float r,float b,float radius,int color,float width,int opacity){
        themeColor(color,opacity,l,t,r,b);strokeOutline(c,l,t,r,b,radius,width);p.setShader(null);
    }
    private void strokeOutline(Canvas c,float l,float t,float r,float b,float radius,float width){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);
        c.drawRoundRect(l,t,r,b,radius,radius,p);p.setStyle(Paint.Style.FILL);
    }
    private void primaryRect(Canvas c,float l,float t,float r,float b,float radius){
        themedRect(c,l,t,r,b,radius,ink,255);
    }
    private void themedRect(Canvas c,float l,float t,float r,float b,float radius,int color,int opacity){
        if(r<=l||b<=t)return;
        p.setStyle(Paint.Style.FILL);themeColor(color,opacity,l,t,r,b);
        c.drawRoundRect(l,t,r,b,radius,radius,p);p.setShader(null);
    }
    private void progress(Canvas c,float l,float y,float width,float fraction,float weight){
        themedLine(c,l,y,l+width,y,(muted&0xffffff)|0x40000000,weight,64);
        if(fraction>0){themeShader(l,y-weight,l+width,y+weight);line(c,l,y,l+width*Math.min(1,fraction),y,ink,weight);p.setShader(null);}
    }
    private void textLeft(Canvas c,String text,float x,float y,float size,int color,Typeface font,float width){
        p.setShader(null);p.setStyle(Paint.Style.FILL);p.setTypeface(font);p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        float measured=p.measureText(text);
        if(measured>width)p.setTextSize(size*width/measured);
        textColor(color,x,y-size,x+width,y);
        c.drawText(text,x,y,p);p.setShader(null);
    }
    private void fill(Canvas c,float l,float t,float r,float b,float radius,int color){
        p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);
        c.drawRoundRect(l,t,r,b,radius,radius,p);
    }
    private void dot(Canvas c,float x,float y,float radius){
        p.setStyle(Paint.Style.FILL);p.setColor(ink);themeShader(x-radius,y-radius,x+radius,y+radius);
        c.drawCircle(x,y,radius,p);p.setShader(null);
    }
    private int softInk(int opacity){return (ink&0xffffff)|(opacity<<24);}
    private void agendaPreview(Canvas c,float y){
        if(component.bool("showAgenda",true))txt(c,shorten(privateAgenda(true).split("\n")[0],30),250,y,19,muted,regular,430);
    }
    private void dateVariant(Canvas c,ZonedDateTime now,int style){
        LocalDate today=now.toLocalDate();
        if(style==1){
            themedOutline(c,53,78,447,300,16,muted,1.5f,160);
            for(int i=0;i<12;i++)themedLine(c,280,94+i*17,280,100+i*17,color("plate"),2,54);
            fill(c,45,179,61,195,8,Color.BLACK);fill(c,439,179,455,195,8,Color.BLACK);
            timeText(c,String.format(Locale.ROOT,"%02d",today.getDayOfMonth()),166,243,151,208);
            textLeft(c,today.getYear()+"年",303,137,23,muted,regular,123);
            textLeft(c,today.getMonthValue()+"月",303,190,41,ink,bold,120);
            textLeft(c,now.format(FMT_WEEKDAY),303,245,24,muted,regular,123);
            agendaPreview(c,347);
        }else if(style==2){
            header(c,now.format(FMT_MONTH));
            txt(c,now.format(FMT_DATE),250,112,34,ink,bold,430);
            LocalDate monday=today.minusDays(today.getDayOfWeek().getValue()-1);
            for(int i=0;i<7;i++){
                LocalDate day=monday.plusDays(i);float x=46+i*68;
                txt(c,WEEKDAYS[i],x,182,19,muted,regular,56);
                if(day.equals(today))primaryRect(c,x-27,202,x+27,260,15);
                txt(c,""+day.getDayOfMonth(),x,241,30,day.equals(today)?Color.BLACK:muted,numbers,50);
                if(day.getMonth()!=today.getMonth())txt(c,day.getMonthValue()+"月",x,286,13,muted,regular,56);
            }
            agendaPreview(c,352);
        }else if(style==3){
            int year=today.getYear();
            if(year!=cachedYear){
                cachedYear=year;
                for(int m=1;m<=12;m++){
                    LocalDate first=today.withMonth(m).withDayOfMonth(1);
                    monthOffsets[m-1]=first.getDayOfWeek().getValue()-1;
                    monthLengths[m-1]=first.lengthOfMonth();
                }
            }
            header(c,year+" 年 · 全年一览");
            int normalColor=(muted&0xffffff)|0x78000000;
            for(int m=1;m<=12;m++){
                int col=(m-1)%4,row=(m-1)/4;float x=30+col*120,y=88+row*85;
                txt(c,m+"月",x+39,y,16,m==today.getMonthValue()?ink:muted,bold,95);
                int offset=monthOffsets[m-1],len=monthLengths[m-1];
                for(int d=1;d<=len;d++){
                    int pos=offset+d-1;float dx=x+(pos%7)*13,dy=y+15+(pos/7)*8;
                    if(m==today.getMonthValue()&&d==today.getDayOfMonth()){
                        p.setColor(ink);themeShader(dx-4,dy-4,dx+4,dy+4);
                        c.drawCircle(dx,dy,4,p);
                    }else{
                        themeColor(normalColor,120,x,y,x+95,y+64);
                        c.drawCircle(dx,dy,2,p);
                    }
                }
                p.setShader(null);
            }
            if(component.bool("showAgenda",true))agendaPreview(c,365);else foot(c,now.format(FMT_DATE));
        }else{
            txt(c,today.getYear()+" 年",250,65,23,muted,regular,380);
            calendarBlock(c,today.withDayOfMonth(1),today,54);
            themedLine(c,250,83,250,310,color("plate"),1.5f,54);
            calendarBlock(c,today.withDayOfMonth(1).plusMonths(1),today,265);
            agendaPreview(c,347);
        }
    }
    private void calendarBlock(Canvas c,LocalDate first,LocalDate today,float left){
        String month=first.getYear()==today.getYear()?first.getMonthValue()+"月":first.format(FMT_MONTH);
        textLeft(c,month,left,107,30,ink,bold,175);
        for(int i=0;i<7;i++)txt(c,WEEKDAYS[i],left+12+i*26,141,15,muted,regular,24);
        themedLine(c,left,153,left+180,153,color("plate"),1,54);
        int offset=first.getDayOfWeek().getValue()-1;
        for(int d=1;d<=first.lengthOfMonth();d++){
            int pos=offset+d-1;float x=left+12+pos%7*26,y=179+pos/7*24;
            boolean current=d==today.getDayOfMonth()&&first.getMonth()==today.getMonth()&&first.getYear()==today.getYear();
            if(current)dot(c,x,y-6,11);
            txt(c,Integer.toString(d),x,y,18,current?Color.BLACK:muted,numbers,24);
        }
    }
    private void agenda(Canvas c){
        header(c,component.bool("agendaToday",false)?"今日事项":"接下来 24 小时");
        String value=privateAgenda(component.bool("agendaToday",false));
        String[] rows=value.split("\n");int style=variant();
        // Permission, privacy and empty-state messages never become fabricated agenda cards.
        if(!rows[0].contains("  ")){txt(c,rows[0],250,215,27,muted,regular,420);return;}
        if(style==4){agendaDial(c,rows);return;}
        if(style==2){
            for(int i=0;i<Math.min(4,rows.length);i++){
                float x=50+i%2*207,y=79+i/2*133;
                String[] event=rows[i].split("  ",2);
                themedRect(c,x,y,x+193,y+118,7,softInk(i==0?27:12),255);
                primaryRect(c,x,y+13,x+3,y+45,1);
                textLeft(c,event[0],x+15,y+43,35,ink,numbers,163);
                themedLine(c,x+15,y+56,x+177,y+56,color("plate"),1,54);
                textLeft(c,shorten(event.length>1?event[1]:"日程",24),x+15,y+89,23,i==0?ink:muted,regular,163);
            }
            return;
        }
        if(style==3){
            String[] first=rows[0].split("  ",2);
            txt(c,"范围内首项",250,94,17,muted,regular,420);
            timeText(c,first[0],250,184,86,420);
            txt(c,first[1],250,249,35,ink,bold,430);
            themedLine(c,180,283,320,283,color("plate"),2,54);
            txt(c,rows.length>1?"随后 · "+rows[1]:"暂无更多事项",250,333,20,muted,regular,430);
            return;
        }
        if(style==1)themedLine(c,117,92,117,319,color("plate"),2,90);
        for(int i=0;i<Math.min(4,rows.length);i++){
            float y=113+i*65;String[] parts=rows[i].split("  ",2);
            if(style==0){
                txt(c,rows[i],250,y,26,i==0?ink:muted,regular,420);
                if(i<rows.length-1)themedLine(c,40,y+24,460,y+24,color("plate"),1,54);
            }else{
                txt(c,parts[0],64,y,21,muted,numbers,83);
                p.setColor(ink);themeShader(111,y-13,123,y-1);c.drawCircle(117,y-7,i==0?6:4,p);p.setShader(null);
                txt(c,parts.length>1?parts[1]:"日程",299,y,25,i==0?ink:muted,regular,320);
            }
        }
    }
    private void agendaDial(Canvas c,String[] rows){
        float cx=150,cy=208,r=91;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
        themeColor(color("plate"),80,cx-r,cy-r,cx+r,cy+r);
        c.drawCircle(cx,cy,r,p);p.setStyle(Paint.Style.FILL);
        for(int h=0;h<24;h++){
            double angle=h*Math.PI/12;
            float sin=(float)Math.sin(angle),cos=(float)Math.cos(angle),inner=h%6==0?r-9:r-4;
            themeColor(muted,h%6==0?172:114,cx-r,cy-r,cx+r,cy+r);
            strokeLine(c,cx+sin*inner,cy-cos*inner,cx+sin*r,cy-cos*r,h%6==0?2:1);
        }
        p.setShader(null);
        txt(c,"00",cx,106,17,muted,numbers,34);txt(c,"12",cx,322,17,muted,numbers,34);
        txt(c,"18",78,214,15,muted,numbers,27);txt(c,"06",222,214,15,muted,numbers,27);
        themedLine(c,263,87,263,324,color("plate"),1,54);
        for(int i=0;i<Math.min(4,rows.length);i++){
            String[] event=rows[i].split("  ",2);float y=110+i*61;
            dot(c,283,y-6,10);txt(c,Integer.toString(i+1),283,y-1,14,Color.BLACK,numbers,16);
            textLeft(c,event[0],301,y,22,ink,numbers,143);
            textLeft(c,shorten(event.length>1?event[1]:"日程",24),301,y+25,19,muted,regular,149);
            if(event[0].equals("全天"))continue;
            try{
                LocalTime time=LocalTime.parse(event[0]);
                double angle=time.toSecondOfDay()*Math.PI/43200;
                float markerRadius=62-i*15;
                float x=cx+(float)Math.sin(angle)*markerRadius,pointY=cy-(float)Math.cos(angle)*markerRadius;
                dot(c,x,pointY,7);txt(c,Integer.toString(i+1),x,pointY+4,10,Color.BLACK,numbers,12);
            }catch(java.time.format.DateTimeParseException ignored){}
        }
    }
    private void alarm(Canvas c,ZonedDateTime now){
        AlarmData.State state=AlarmData.state;
        long trigger=demo?now.plusDays(1).withHour(7).withMinute(30).withSecond(0).withNano(0).toInstant().toEpochMilli():state.triggerAt;
        boolean available=demo||state.upcoming(wallTime());
        if(!available){
            header(c,"闹钟");alarmIcon(c,250,135,.85f);
            txt(c,state.unavailable?"闹钟暂不可用":"未设置闹钟",250,235,34,muted,regular,420);
            txt(c,state.unavailable?"可前往系统时钟查看":"添加闹钟后自动显示下次响铃",250,282,19,muted,regular,430);
            foot(c,state.unavailable?"轻触管理闹钟":"轻触设置闹钟");return;
        }
        ZonedDateTime at=Instant.ofEpochMilli(trigger).atZone(now.getZone());
        boolean h24=component.bool("h24",true),showDate=component.bool("alarmDate",true),countdown=component.bool("alarmCountdown",true);
        String value=AlarmData.time(at,h24),period=h24?"":" · "+AlarmData.period(at);
        String date=showDate?AlarmData.date(at,now):"";
        boolean soon=trigger-wallTime()<60000;
        String remaining=countdown?(soon?"即将响铃":"距响铃 "+AlarmData.remaining(trigger,wallTime())):"";
        int style=variant();
        header(c,"下次闹钟"+period);
        if(style==0){
            alarmIcon(c,250,109,.5f);
            timeText(c,value,250,240,132,440);
            txt(c,date,250,286,22,muted,regular,430);
            txt(c,remaining,250,324,22,ink,regular,430);
        }else if(style==1){
            alarmArtwork.palette(ink,muted,color("plate"),gradient);
            alarmArtwork.draw(c,250,175,1.02f,at.getHour(),at.getMinute(),true);
            timeText(c,value,250,292,30,190);
            txt(c,date,250,319,18,muted,regular,430);
            txt(c,remaining,250,342,18,ink,regular,430);
        }else if(style==2){
            long minutes=Math.max(1,(trigger-wallTime()+59999)/60000);
            boolean days=countdown&&minutes>=1440;
            txt(c,countdown?(soon?"即将响铃":"距离响铃"):"响铃时刻",250,103,21,muted,regular,400);
            if(days)alarmUnit(c,112,Long.toString(minutes/1440),"天",113);
            alarmUnit(c,days?250:151,countdown?String.format(Locale.ROOT,"%02d",minutes/60%24):value.substring(0,value.indexOf(':')),countdown?"小时":"时",days?113:155);
            alarmUnit(c,days?388:349,countdown?String.format(Locale.ROOT,"%02d",minutes%60):value.substring(value.indexOf(':')+1),countdown?"分钟":"分",days?113:155);
            txt(c,countdown?"响铃 "+value+period:"",250,293,27,ink,regular,400);
            txt(c,date,250,countdown?330:300,21,muted,regular,400);
        }else if(style==3){
            themedRect(c,53,82,447,331,12,softInk(16),255);
            primaryRect(c,53,82,447,87,2);
            alarmIcon(c,104,133,.53f);
            textLeft(c,date,145,139,23,muted,regular,270);
            timeText(c,value,250,236,111,350);
            themeColor(muted,148,67,264,430,264);
            for(int i=0;i<22;i++)strokeLine(c,67+i*17,264,73+i*17,264,1.3f);
            p.setShader(null);
            fill(c,45,256,61,272,8,Color.BLACK);fill(c,439,256,455,272,8,Color.BLACK);
            txt(c,remaining,250,306,24,ink,regular,365);
        }else{
            textLeft(c,value,53,171,102,ink,numbers,257);
            int dayLabelEnd=date.indexOf(" · ");
            if(dayLabelEnd>=0){
                textLeft(c,date.substring(0,dayLabelEnd),304,125,24,ink,bold,143);
                textLeft(c,date.substring(dayLabelEnd+3),304,158,20,muted,regular,143);
            }else textLeft(c,date,304,155,20,muted,regular,143);
            float marker=dayBand(c,53,447,245,at,h24,WorldDaylight.DEFAULT);
            dayMarker(c,53,447,marker,215,260,215,8);
            txt(c,remaining,250,326,24,ink,regular,400);
        }
    }
    private void alarmUnit(Canvas c,float x,String value,String label,float width){
        timeText(c,value,x,208,91,width);
        themedLine(c,x-width*.30f,222,x+width*.30f,222,color("plate"),2,54);
        txt(c,label,x,251,22,muted,regular,width);
    }
    private void alarmIcon(Canvas c,float x,float y,float size){
        alarmArtwork.palette(ink,muted,color("plate"),gradient);
        alarmArtwork.draw(c,x,y,size*.55f,10,10,false);
    }
    private void focus(Canvas c){
        header(c,"专注");
        int style=variant();
        long total=demo?DeskData.focusMinutes(prefs)*60000L:DeskData.focusTotal(prefs);
        long left=demo?total*3/5:DeskData.remaining(prefs);float fraction=Math.max(0,Math.min(1,left/(float)total));
        long frameTime=testTime>=0?testTime:SystemClock.elapsedRealtime(),resetAt=prefs.getLong("focusResetAt",-1);
        float resetFrom=prefs.getFloat("focusResetFrom",0);
        if(demo){
            resetAt=-1;
            if(style==3&&effectsEnabled()){
                long phase=frameTime%Sandglass.DEMO_CYCLE_MS;
                fraction=phase<Sandglass.DEMO_DRAIN_MS?1-phase/(float)Sandglass.DEMO_DRAIN_MS:1;
                left=Math.round(total*fraction);
                if(phase>=Sandglass.DEMO_DRAIN_MS){resetAt=frameTime-(phase-Sandglass.DEMO_DRAIN_MS);resetFrom=0;}
            }
        }
        String value=FocusDuration.formatCountdown(left);
        if(style==0){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);themeColor(color("plate"),48,arcRect.left,arcRect.top,arcRect.right,arcRect.bottom);c.drawArc(arcRect,-90,360,false,p);
            p.setColor(ink);themeShader(arcRect.left,arcRect.top,arcRect.right,arcRect.bottom);c.drawArc(arcRect,-90,360*fraction,false,p);p.setShader(null);p.setStyle(Paint.Style.FILL);
            timeText(c,value,250,222,65,250);
        }else if(style==1){
            timeText(c,value,250,232,129,440);
            progress(c,45,285,410,fraction,5);
            txt(c,FocusDuration.totalLabel(total),250,326,20,muted,regular,430);
        }else if(style==2){
            timeText(c,value,147,198,71,196);
            txt(c,"剩余 "+Math.round(fraction*100)+"%",147,243,21,muted,regular,195);
            for(int i=0;i<20;i++){
                float x=267+i%5*36,y=112+(3-i/5)*42;
                themedRect(c,x,y,x+28,y+34,4,color("plate"),48);
                float fillFraction=Math.max(0,Math.min(1,fraction*20-i));
                if(fillFraction>0)primaryRect(c,x,y,x+28*fillFraction,y+34,4);
            }
        }else if(style==3){
            boolean running=prefs.getLong("focusEnd",0)>0&&left>0;
            Sandglass glass=sandglasses.get(component.id);
            if(glass==null){glass=new Sandglass();sandglasses.put(component.id,glass);}
            glass.palette(ink,muted,color("time"),color("plate"),gradient);
            if(glass.draw(c,fraction,demo||running,effectsEnabled(),frameTime,resetAt,resetFrom))animateEffect(33);
            boolean resetting=glass.isResetting();
            timeText(c,value,358,204,69,246);
            txt(c,"剩余 "+Math.round(fraction*100)+"%",358,248,21,muted,regular,246);
            txt(c,resetting?"翻转复位中":left==0?"沙已落尽":demo||running?"落沙中":fraction<.999f?"已暂停":"准备开始",358,280,16,muted,regular,246);
        }else{
            String[] tiles=FocusDuration.flipTiles(left);
            focusTile(c,55,tiles[0],tiles[1]);
            focusTile(c,265,tiles[2],tiles[3]);
            dot(c,250,174,4);dot(c,250,203,4);
            txt(c,FocusDuration.totalLabel(total),250,319,20,muted,regular,400);
        }
        txt(c,demo?"预览":left==0?"已完成":prefs.getLong("focusEnd",0)>0?"专注中":"轻触开始 · 长按重置",250,351,18,muted,regular,400);
    }
    private void focusTile(Canvas c,float x,String value,String label){
        themedRect(c,x,109,x+180,190,9,softInk(25),255);
        themedRect(c,x,193,x+180,271,9,softInk(13),255);
        timeText(c,value,x+90,235,133,154);
        line(c,x+1,190,x+179,190,Color.BLACK,3);
        themeColor(muted,150,x,182,x+180,182);
        strokeLine(c,x,182,x+7,182,3);strokeLine(c,x+173,182,x+180,182,3);p.setShader(null);
        txt(c,label,x+90,299,21,muted,regular,155);
    }
    Sandglass sandglassForCheck(){return component==null?null:sandglasses.get(component.id);}
    private void world(Canvas c,ZonedDateTime local){
        ZoneId selectedZone=WorldDaylight.zone(component,prefs);
        String zone=selectedZone.getId();
        ZonedDateTime other=local.withZoneSameInstant(selectedZone);
        String city=zone.substring(zone.lastIndexOf('/')+1).replace('_',' ');
        int style=variant();
        if(style==4){worldBands(c,city,other,local);return;}
        header(c,city);
        if(style==0)timeText(c,time(other),250,250,134,440);
        else if(style==1){
            float cx=250,cy=202,r=126;
            for(int i=0;i<60;i++){
                boolean major=i%5==0;float inner=major?r-13:r-4;
                themeColor(major?ink:color("plate"),major?255:68,cx-r,cy-r,cx+r,cy+r);
                strokeLine(c,cx+SIN_60[i]*inner,cy-COS_60[i]*inner,cx+SIN_60[i]*r,cy-COS_60[i]*r,major?3:1);
            }
            p.setShader(null);
            for(int i=1;i<=4;i++){int idx=(i*15)%60;txt(c,""+(i*3),cx+SIN_60[idx]*94,cy-COS_60[idx]*94+7,21,muted,numbers,40);}
            themeShader(cx-r,cy-r,cx+r,cy+r);
            worldHand(c,cx,cy,(other.getHour()%12+other.getMinute()/60f)*30,64,7);
            worldHand(c,cx,cy,other.getMinute()*6,99,4);
            p.setColor(ink);c.drawCircle(cx,cy,6,p);p.setShader(null);txt(c,time(other),250,278,22,muted,numbers,140);
        }else if(style==2){
            txt(c,"所选时区",140,106,17,muted,regular,200);txt(c,"本地",368,106,17,muted,regular,200);
            timeText(c,time(other),140,208,79,208);timeText(c,time(local),368,208,79,208);
            themedLine(c,254,92,254,278,color("plate"),2,54);
            txt(c,other.format(FMT_SHORT_DATE),140,258,21,muted,regular,205);
            txt(c,local.format(FMT_SHORT_DATE),368,258,21,muted,regular,205);
            long minutes=(other.getOffset().getTotalSeconds()-local.getOffset().getTotalSeconds())/60;
            txt(c,minutes==0?"与本地同一时刻":"比本地"+(minutes>0?"快 ":"慢 ")+Math.abs(minutes)/60+"小时"+(Math.abs(minutes)%60==0?"":Math.abs(minutes)%60+"分"),250,313,20,ink,regular,430);
        }else{
            RectF orbit=new RectF(115,67,385,337);
            WorldDaylight.Times daylight=WorldDaylight.forTime(other,null);
            LocalTime otherTime=other.toLocalTime();
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);themeColor(color("plate"),52,orbit.left,orbit.top,orbit.right,orbit.bottom);c.drawOval(orbit,p);
            float sunriseAngle=daylight.sunrise.toSecondOfDay()/240f;
            float sunsetAngle=daylight.sunset.toSecondOfDay()/240f;
            themeColor(muted,178,orbit.left,orbit.top,orbit.right,orbit.bottom);
            c.drawArc(orbit,90+sunriseAngle,sunsetAngle-sunriseAngle,false,p);p.setStyle(Paint.Style.FILL);
            double a=Math.toRadians(otherTime.toSecondOfDay()/240f+90);
            float x=250+(float)Math.cos(a)*135,y=202+(float)Math.sin(a)*135;
            p.setColor(ink);c.drawCircle(x,y,8,p);p.setShader(null);
            timeText(c,time(other),250,217,76,249);
            boolean day=daylight.contains(otherTime);
            String period=day?"日间 · "+daylightLabel(daylight.sunrise,daylight.sunset)
                :"夜间 · "+daylightLabel(daylight.sunset,daylight.sunrise);
            txt(c,period+(daylight==WorldDaylight.DEFAULT?" · 默认":""),250,255,18,muted,regular,240);
            txt(c,"12",250,91,14,muted,regular,30);txt(c,"00",250,320,14,muted,regular,30);
        }
        foot(c,other.format(FMT_WORLD));
    }
    private String daylightLabel(LocalTime start,LocalTime end){return start.format(FMT_24)+"–"+end.format(FMT_24);}
    private void worldBands(Canvas c,String city,ZonedDateTime other,ZonedDateTime local){
        WorldDaylight.Times otherDay=WorldDaylight.forTime(other,null);
        WorldDaylight.Times localDay=WorldDaylight.forTime(local,DeskData.weather);
        worldBand(c,city,other,119,otherDay);
        themedLine(c,55,186,445,186,color("plate"),1,54);
        worldBand(c,"本地",local,267,localDay);
    }
    private void worldBand(Canvas c,String name,ZonedDateTime time,float y,WorldDaylight.Times daylight){
        textLeft(c,name,55,y-55,25,ink,bold,181);
        textLeft(c,time.format(FMT_WORLD),55,y-25,17,muted,regular,214);
        timeText(c,time(time),373,y-24,56,147);
        float marker=dayBand(c,55,445,y,time,true,daylight);
        dayMarker(c,55,445,marker,y-15,y+9,y-12,5);
        String label="日间 "+daylightLabel(daylight.sunrise,daylight.sunset);
        txt(c,label+(daylight==WorldDaylight.DEFAULT?" · 默认":""),250,y+57,19,muted,regular,400);
    }
    private float dayBand(Canvas c,float left,float right,float y,ZonedDateTime time,boolean h24,WorldDaylight.Times daylight){
        float width=right-left;
        themedRect(c,left,y-4,right,y+4,4,color("plate"),48);
        float sunrise=left+width*daylight.sunrise.toSecondOfDay()/86400f;
        float sunset=left+width*daylight.sunset.toSecondOfDay()/86400f;
        p.setStyle(Paint.Style.FILL);themeColor(softInk(99),255,left,y,right,y);
        c.drawRoundRect(sunrise,y-4,sunset,y+4,4,4,p);
        for(int i=0;i<=4;i++){
            float x=left+width*i/4;
            themeColor(muted,150,left,y,right,y);strokeLine(c,x,y+5,x,y+10,1.3f);p.setShader(null);
            String label=h24?String.format(Locale.ROOT,"%02d",i*6)
                :i==0||i==4?"午夜":i==1?"上午6时":i==2?"正午":"下午6时";
            txt(c,label,x,y+32,16,muted,regular,74);
        }
        return left+width*time.toLocalTime().toSecondOfDay()/86400f;
    }
    private void dayMarker(Canvas c,float left,float right,float x,float top,float bottom,float dotY,float radius){
        themeColor(ink,255,left,dotY,right,dotY);
        strokeLine(c,x,top,x,bottom,2);
        c.drawCircle(x,dotY,radius,p);p.setShader(null);
    }
    private void worldHand(Canvas c,float cx,float cy,float angle,float length,float width){
        double a=Math.toRadians(angle);
        line(c,cx,cy,cx+(float)Math.sin(a)*length,cy-(float)Math.cos(a)*length,ink,width);
    }
    private void month(Canvas c,ZonedDateTime now){if(variant()>0){dateVariant(c,now,variant());return;}boolean combined=component.bool("showAgenda",true);float center=combined?160:250,step=combined?41:62,left=combined?37:65;txt(c,now.format(FMT_MONTH),center,48,22,ink,bold,combined?280:430);LocalDate first=now.toLocalDate().withDayOfMonth(1);int offset=first.getDayOfWeek().getValue()-1;for(int i=0;i<7;i++)txt(c,WEEKDAYS[i],left+i*step,97,17,muted,regular,step-5);for(int day=1;day<=first.lengthOfMonth();day++){int pos=offset+day-1;float x=left+(pos%7)*step,y=141+(pos/7)*38;if(day==now.getDayOfMonth()){p.setColor(ink);themeShader(x-18,y-24,x+18,y+12);c.drawCircle(x,y-7,combined?15:18,p);p.setShader(null);}txt(c,""+day,x,y,combined?18:20,day==now.getDayOfMonth()?color("background"):muted,bold,step-5);}if(!combined)return;themedLine(c,318,44,318,354,color("plate"),2,54);txt(c,"今日事项",405,78,20,ink,bold,154);String[] rows=privateAgenda(true).split("\n");for(int i=0;i<Math.min(3,rows.length);i++){String[] parts=rows[i].split("  ",2);float y=132+i*76;txt(c,parts[0],405,y,18,muted,regular,150);if(parts.length>1)txt(c,shorten(parts[1],12),405,y+28,20,ink,regular,150);}if(rows.length>3)txt(c,"另有 "+(rows.length-3)+" 项",405,355,15,muted,regular,150);}
    private void todo(Canvas c){
        if(!demo&&!todoAllowed()){header(c,"待办");txt(c,"解锁查看待办",250,215,27,muted,regular,420);return;}
        if(todoStore!=null){savedTodo(c);return;}
        JSONArray tasks=TodoItems.all(component);
        int style=variant();
        int[] ordered=TodoItems.visible(component);
        int pending=todoPending(tasks,ordered),completed=ordered.length-pending;
        TodoLayout layout=TodoLayout.of(component);
        if(style==3){
            textLeft(c,"完成进度",55,54,24,ink,bold,143);
            textLeft(c,"待办事项",225,54,24,muted,bold,204);
        }else if(style==4){
            textLeft(c,"待完成  "+pending,57,64,23,ink,bold,186);
            textLeft(c,"已完成  "+completed,266,64,23,muted,bold,184);
            themedLine(c,250,47,250,327,color("plate"),1.5f,54);
        }else header(c,style==1?"待办卡片":style==2?"行动清单":"待办");
        if(ordered.length==0){txt(c,"暂无待办",250,204,30,ink,regular,420);txt(c,"在组件编辑中添加",250,348,18,muted,regular,400);return;}
        if(style==3)todoProgress(c,completed,ordered.length);
        if(style==4&&pending==0)txt(c,"全部完成",146,113,19,muted,regular,175);
        if(style==4&&completed==0)txt(c,"暂无完成项",354,113,19,muted,regular,175);
        float maxOffset=layout.maxOffset(ordered.length,pending),offset=todoOffset(component,maxOffset);
        c.save();c.clipRect(layout.left,layout.top,layout.right,TodoLayout.BOTTOM);
        int first=(int)(offset/layout.rowHeight);
        int last=Math.min(layout.rows(ordered.length,pending),(int)Math.ceil((offset+layout.viewport())/layout.rowHeight));
        for(int row=first;row<last;row++){
            for(int column=0;column<layout.columns;column++){
                int index=layout.index(row,column,ordered.length,pending);
                if(index<0)continue;
                JSONObject task=tasks.optJSONObject(ordered[index]);
                float x=layout.cellLeft(column),y=layout.top+row*layout.rowHeight-offset;
                todoItem(c,task.optString("text"),task.optBoolean("done"),false,style,index,x,y,layout.cellWidth());
            }
        }
        c.restore();
        todoScrollbar(c,layout,offset,maxOffset);
        if(style!=3&&style!=4)txt(c,completed+" / "+ordered.length+" 已完成",250,352,18,muted,regular,420);
    }

    private void savedTodo(Canvas c) {
        TodoStore.Display display = todoStore.display(todoStore.listFor(component));
        int style = variant();
        TodoLayout layout = TodoLayout.of(component);
        int todayCount = display.today.size(), undatedCount = display.undated.size();
        boolean board = style == 4;
        boolean expanded = board && expandedTodo.contains(component.id);
        int count = todayCount + (expanded ? undatedCount : 0);
        int leading = layout.managedLeadingItems(todayCount, display.completed);
        if (style == 3) {
            textLeft(c, "今日进度", 55, 54, 24, ink, bold, 143);
            textLeft(c, "今天", 225, 54, 24, muted, bold, 180);
            todoProgress(c, display.completed, todayCount);
        } else if (board) {
            textLeft(c, "今天 · " + todayCount, 57, 64, 23, ink, bold, 178);
            textLeft(c, "未安排 · " + undatedCount + (expanded ? " ▴" : " ▾"), 266, 64, 20, muted, bold, 150);
            line(c, 250, 78, 250, 327, color("plate"), 1.5f);
        } else {
            header(c, "今天");
        }
        if (openTodo != null) txt(c, "＋", 442, 55, 34, ink, bold, 58);
        if (todayCount == 0) {
            if (board) txt(c, "暂无待办", 145, 136, 20, muted, regular, 180);
            else if (style == 3) txt(c, "暂无待办", 340, 190, 23, muted, regular, 200);
            else txt(c, "今天暂无待办", 250, 198, 26, muted, regular, 410);
        }
        float maxOffset = layout.maxOffset(count, leading), offset = todoOffset(component, maxOffset);
        c.save();
        c.clipRect(layout.left, layout.top, layout.right, TodoLayout.BOTTOM);
        int first = (int) (offset / layout.rowHeight);
        int last = Math.min(layout.rows(count, leading), (int) Math.ceil((offset + layout.viewport()) / layout.rowHeight));
        for (int row = first; row < last; row++) {
            for (int column = 0; column < layout.columns; column++) {
                int index = layout.index(row, column, count, leading);
                if (index < 0) continue;
                TodoTask task = index < todayCount ? display.today.get(index) : display.undated.get(index - todayCount);
                float x = layout.cellLeft(column), y = layout.top + row * layout.rowHeight - offset;
                todoItem(c, task.title, task.completed != 0, true, style, index, x, y, layout.cellWidth());
            }
        }
        c.restore();
        todoScrollbar(c, layout, offset, maxOffset);
        if (display.overdue > 0) txt(c, display.overdue + " 项已过期 ›", 115, 356, 19, 0xffffd594, regular, 155);
        if (openTodo != null) {
            txt(c, "管理 ›", 250, 356, 19, ink, regular, 100);
            if (!board && undatedCount > 0) txt(c, "未安排 " + undatedCount + " ›", 401, 356, 19, muted, regular, 140);
        }
    }

    private void todoScrollbar(Canvas c, TodoLayout layout, float offset, float maxOffset) {
        if (maxOffset <= 0) return;
        float track = layout.viewport(), thumb = Math.max(12, track * track / (track + maxOffset));
        float x = layout.right + 8, top = layout.top + (track - thumb) * offset / maxOffset;
        line(c, x, layout.top, x, TodoLayout.BOTTOM, color("plate"), 2);
        themedLine(c, x, top, x, top + thumb, muted, 3, 160);
    }

    private void todoProgress(Canvas c,int completed,int count){
        float cx=121,cy=187,r=61;
        float fraction = count == 0 ? 0f : (float) completed / count;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6);themeColor(color("plate"),52,cx-r,cy-r,cx+r,cy+r);
        c.drawCircle(cx,cy,r,p);
        p.setColor(ink);themeShader(cx-r,cy-r,cx+r,cy+r);
        c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,360f*fraction,false,p);
        p.setShader(null);p.setStyle(Paint.Style.FILL);
        timeText(c,Math.round(100f*fraction)+"%",cx,203,44,107);
        txt(c,completed+" / "+count+" 已完成",cx,289,18,muted,regular,151);
        themedLine(c,204,78,204,326,color("plate"),1.5f,54);
    }
    private void todoItem(Canvas c,String value,boolean done,boolean managed,int style,int index,float x,float top,float width){
        int foreground=done?muted:ink;
        float y=top+30;
        if(style==1){
            themedRect(c,x,top+3,x+width,top+77,5,softInk(done?17:29),255);
            todoMark(c,x+10,top+11,done,15);
            p.setStrikeThruText(done);
            todoCardText(c,value,x+34,top+33,width-46,foreground);
            p.setStrikeThruText(false);
        }else if(style==2){
            if(managed)todoMark(c,x+7,y-17,done,19);
            txt(c,done&&!managed?"✓":String.format(Locale.ROOT,"%02d",index+1),x+(managed?57:38),y+4,30,foreground,numbers,53);
            p.setStrikeThruText(done);
            textLeft(c,shorten(value,26),x+86,y,25,foreground,regular,width-100);
            p.setStrikeThruText(false);
            themedLine(c,x+84,y+16,x+width-9,y+16,color("plate"),1,54);
        }else if(style==4){
            themedRect(c,x,top+2,x+width,top+44,5,softInk(done?11:24),255);
            if(done||managed)todoMark(c,x+10,top+15,done,14);
            else dot(c,x+17,top+23,3.5f);
            p.setStrikeThruText(done);
            textLeft(c,shorten(value,18),x+32,y,21,foreground,regular,width-40);
            p.setStrikeThruText(false);
        }else{
            float inset=style==3?24:30,size=style==3?21:25;
            todoMark(c,x+3,y-19,done,style==3?16:22);
            p.setStrikeThruText(done);
            textLeft(c,shorten(value,style==3?20:26),x+inset,y,size,foreground,regular,width-inset-6);
            p.setStrikeThruText(false);
        }
    }
    private void todoMark(Canvas c,float x,float y,boolean done,float size){
        themeColor(done?muted:ink,done?160:255,x,y,x+size,y+size);
        strokeOutline(c,x,y,x+size,y+size,3,1.5f);
        if(done){
            strokeLine(c,x+size*.18f,y+size*.49f,x+size*.43f,y+size*.74f,1.7f);
            strokeLine(c,x+size*.43f,y+size*.74f,x+size*.87f,y+size*.26f,1.7f);
        }
        p.setShader(null);
    }
    private void todoCardText(Canvas c,String value,float x,float y,float width,int color){
        p.setTypeface(regular);p.setTextSize(20);
        int limit=todoLineEnd(value,width);
        String first=value.substring(0,limit),rest=value.substring(limit).trim();
        if(!rest.isEmpty()){
            int space=first.lastIndexOf(' ');
            if(space>first.length()/2){
                rest=value.substring(space).trim();
                first=first.substring(0,space);
            }
            if(p.measureText(rest)>width)rest=rest.substring(0,todoLineEnd(rest,width-p.measureText("…")))+"…";
        }
        textLeft(c,first,x,y,20,color,regular,width);
        if(!rest.isEmpty())textLeft(c,rest,x,y+26,20,color,regular,width);
    }
    private int todoLineEnd(String value,float width){
        int end=p.breakText(value,true,width,null);
        return end>0&&Character.isHighSurrogate(value.charAt(end-1))?end-1:end;
    }
    private void weather(Canvas c){header(c,demo?"天气预览":WeatherCities.simplified(prefs.getString("city","天气")));JSONObject w=DeskData.weather;
        if(demo){weatherContent(c,23,24,58,0,26,19,25,18);foot(c,"示例数据 · 选择城市后使用实况");return;}
        if(!prefs.contains("city")){txt(c,"选择城市",250,204,30,muted,regular,420);foot(c,"在组件编辑或设置中选择");return;}
        if(w==null){txt(c,DeskData.weatherBusy?"正在更新天气":DeskData.weatherError.isEmpty()?"等待天气更新":DeskData.weatherError,250,204,26,muted,regular,420);foot(c,"可在设置中重试，或使用经纬度设置城市");return;}
        try{JSONObject cur=w.getJSONObject("current"),daily=w.getJSONObject("daily");
            JSONArray highs=daily.getJSONArray("temperature_2m_max"),lows=daily.getJSONArray("temperature_2m_min");
            weatherContent(c,cur.getDouble("temperature_2m"),cur.optDouble("apparent_temperature",Double.NaN),cur.isNull("relative_humidity_2m")?-1:cur.optInt("relative_humidity_2m",-1),cur.optInt("weather_code",-1),highs.optDouble(0,Double.NaN),lows.optDouble(0,Double.NaN),highs.optDouble(1,Double.NaN),lows.optDouble(1,Double.NaN));
            boolean stale=System.currentTimeMillis()-DeskData.weatherAt>3600000||!DeskData.weatherError.isEmpty();foot(c,(stale?"已缓存 · ":"")+"Open-Meteo · "+Instant.ofEpochMilli(DeskData.weatherAt).atZone(ZoneId.systemDefault()).format(FMT_24));
        }catch(Exception e){txt(c,"天气不可用",250,220,28,muted,regular,420);}
    }
    private String temperature(double value){return Double.isFinite(value)?Math.round(prefs.getBoolean("fahrenheit",false)?value*1.8+32:value)+(prefs.getBoolean("fahrenheit",false)?"°F":"°"):"—";}
    private void weatherContent(Canvas c,double temp,double feels,int humidity,int code,double high,double low,double nextHigh,double nextLow){
        int style=variant();String condition=code<0?"天气未知":DeskData.condition(code);
        String apparent="体感 "+temperature(feels),wet="湿度 "+(humidity<0?"—":humidity+"%");
        String range="↑ "+temperature(high)+"    ↓ "+temperature(low);
        String detail=component.bool("tomorrow",false)?"明日  "+temperature(nextLow)+" — "+temperature(nextHigh):wet+"  ·  "+range;
        if(style==0){
            timeText(c,temperature(temp),250,202,105,420);
            txt(c,condition+" · "+apparent,250,253,24,muted,regular,420);
            txt(c,detail,250,308,21,muted,regular,430);
        }else if(style==1){
            weatherIcon(c,126,168,code);
            timeText(c,temperature(temp),332,205,96,250);
            txt(c,condition+" · "+apparent,250,271,24,muted,regular,420);
            txt(c,detail,250,316,21,muted,regular,430);
        }else if(style==2){
            String[] labels={"实况 · "+condition,"体感","相对湿度"};
            String[] readings={temperature(temp),temperature(feels),humidity<0?"—":humidity+"%"};
            for(int i=0;i<3;i++){
                float y=95+i*68;
                themedRect(c,55,y,445,y+58,4,softInk(i==0?27:12),255);
                textLeft(c,labels[i],71,y+37,23,muted,regular,244);
                txt(c,readings[i],383,y+41,39,ink,numbers,105);
            }
            txt(c,component.bool("tomorrow",false)?detail:range,250,323,22,muted,regular,393);
        }else if(style==3){
            timeText(c,temperature(temp),250,177,102,420);
            txt(c,condition+" · "+apparent,250,219,21,muted,regular,420);
            themedLine(c,62,266,438,266,color("plate"),5,68);
            themeColor(muted,154,62,266,438,266);
            for(int i=0;i<=10;i++)strokeLine(c,62+i*37.6f,258,62+i*37.6f,274,1);
            p.setShader(null);
            if(Double.isFinite(high)&&Double.isFinite(low)&&Double.isFinite(temp)){
                float fraction=high==low?.5f:(float)Math.max(0,Math.min(1,(temp-low)/(high-low)));
                primaryRect(c,54+376*fraction,253,70+376*fraction,279,7);
            }
            txt(c,temperature(low),81,308,24,muted,numbers,100);txt(c,temperature(high),419,308,24,muted,numbers,100);
            txt(c,component.bool("tomorrow",false)?detail:"当日低温 — 高温",250,310,18,muted,regular,220);
        }else{
            humidityDrop(c,humidity);
            txt(c,"相对湿度",149,319,20,muted,regular,182);
            themedLine(c,252,99,252,323,color("plate"),1.5f,54);
            textLeft(c,condition,272,129,28,ink,bold,176);
            textLeft(c,temperature(temp),272,203,79,ink,numbers,175);
            textLeft(c,apparent,272,241,23,muted,regular,175);
            textLeft(c,component.bool("tomorrow",false)?"明日低 / 高温":"今日低 / 高温",272,288,18,muted,regular,175);
            String lowText=temperature(component.bool("tomorrow",false)?nextLow:low);
            String highText=temperature(component.bool("tomorrow",false)?nextHigh:high);
            textLeft(c,lowText+" / "+highText,272,320,25,ink,numbers,175);
        }
    }
    private void humidityDrop(Canvas c,int humidity){
        iconPath.reset();iconPath.moveTo(149,91);
        iconPath.cubicTo(133,121,79,172,79,220);
        iconPath.cubicTo(79,309,219,309,219,220);
        iconPath.cubicTo(219,172,165,121,149,91);iconPath.close();
        int save=c.save();
        c.clipPath(iconPath);
        themedRect(c,78,90,220,289,0,softInk(16),255);
        if(humidity>=0){
            float waterline=289-198*Math.min(100,humidity)/100f;
            themedRect(c,78,waterline,220,289,0,softInk(63),255);
            themedLine(c,78,waterline,220,waterline,ink,1.5f,255);
        }
        c.restoreToCount(save);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);themeColor(muted,176,78,90,220,289);
        c.drawPath(iconPath,p);p.setStyle(Paint.Style.FILL);p.setShader(null);
        txt(c,humidity<0?"—":humidity+"%",149,232,44,ink,numbers,117);
    }
    private void weatherIcon(Canvas c,float x,float y,int code){
        weatherArtwork.palette(ink,muted,color("plate"),gradient);
        weatherArtwork.draw(c,x,y,1f,code);
    }
    private boolean effectsEnabled(){return motionActive&&prefs.getBoolean("motion",true)&&ValueAnimator.areAnimatorsEnabled()&&!night();}
    boolean chargingAnimationForCheck(){return (demo||DeskData.batteryCharging)&&(demo||!DeskData.batteryFull)&&effectsEnabled();}
    private void animateEffect(long delay){if(testTime<0&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&isShown()&&effectsEnabled())postInvalidateDelayed(delay);}
    private void batteryVariant(Canvas c){
        int level=demo?76:Math.max(0,Math.min(100,DeskData.batteryLevel));
        boolean full=!demo&&DeskData.batteryFull,charging=demo||DeskData.batteryCharging;
        int warning=level<=20&&!dark?0xffff987d:ink;
        int accent=gradient>0?ink:warning,style=variant();
        String status=full?"已充满":charging?"正在充电":DeskData.batteryPlugged?"已连接电源":"电池供电";
        header(c,"电量");
        boolean animate=charging&&!full&&effectsEnabled()&&level>0;
        float phase=animate?wallTime()%2400L/2400f:0;
        if(style==4){
            batteryRuler(c,level,accent,warning,status,animate,phase);
            return;
        }
        float fillLeft=50,fillRight=450,fillY=277;
        if(style==1){
            themeColor(muted,176,59,101,442,209);
            strokeOutline(c,59,101,428,209,19,3);strokeLine(c,442,133,442,177,7);p.setShader(null);
            themedRect(c,69,111,69+349*level/100f,199,11,accent,255);
            txt(c,level+"%",250,292,76,accent,numbers,420);
            fillLeft=69;fillRight=418;fillY=155;
        }else if(style==2){
            txt(c,level+"%",250,233,153,accent,numbers,430);
            themedLine(c,50,277,450,277,color("plate"),5,60);
            if(level>0){themeColor(accent,255,50,277,450,277);strokeLine(c,50,277,50+4*level,277,5);p.setShader(null);}
        }else{
            for(int i=0;i<10;i++){
                float x=55+i%5*42,y=121+i/5*64;
                themedRect(c,x,y,x+31,y+49,5,color("plate"),52);
                float amount=Math.max(0,Math.min(1,level/10f-i));
                if(amount>0)themedRect(c,x,y+49*(1-amount),x+31,y+49,5,accent,255);
            }
            txt(c,level+"%",361,210,76,accent,numbers,181);
            fillLeft=55;fillRight=266;fillY=269;
        }
        txt(c,(charging&&!full?"ϟ  ":"")+status,250,329,24,warning,bold,430);
        foot(c,demo?"充电效果预览":String.format(Locale.ROOT,"电池温度 %.1f°C",DeskData.batteryTempTenths/10f));
        if(animate){
            float x=fillLeft+(fillRight-fillLeft)*level/100f*phase;
            themeColor(accent,255,fillLeft,fillY,fillRight,fillY);
            c.drawCircle(x,fillY,style==1?5:3,p);p.setShader(null);animateEffect(33);
        }
    }
    private void batteryRuler(Canvas c,int level,int accent,int warning,String status,boolean animate,float phase){
        float top=89,bottom=317,marker=bottom-(bottom-top)*level/100f;
        themedRect(c,107,top,123,bottom,8,color("plate"),55);
        if(level>0){themeColor(accent,255,115,top,115,bottom);c.drawRoundRect(107,marker,123,bottom,8,8,p);p.setShader(null);}
        for(int i=0;i<=20;i++){
            float y=bottom-i*(bottom-top)/20;
            themeColor(muted,150,115,top,115,bottom);strokeLine(c,130,y,i%5==0?151:140,y,i%5==0?2:1);p.setShader(null);
            if(i%5==0)txt(c,Integer.toString(i*5),174,y+6,17,muted,numbers,38);
        }
        themeColor(accent,255,115,top,115,bottom);c.drawRoundRect(93,marker-2,129,marker+2,2,2,p);p.setShader(null);
        if(animate&&bottom-marker>10){
            p.setColor(Color.BLACK);c.drawCircle(115,bottom-5-(bottom-marker-10)*phase,3,p);
            animateEffect(33);
        }
        themedLine(c,214,91,214,317,color("plate"),1.5f,54);
        textLeft(c,level+"%",238,211,115,accent,numbers,206);
        textLeft(c,status,240,256,25,gradient>0?warning:ink,bold,200);
        textLeft(c,demo?"充电效果预览":String.format(Locale.ROOT,"电池温度 %.1f°C",DeskData.batteryTempTenths/10f),240,300,19,muted,regular,204);
    }
    private void battery(Canvas c){
        if(variant()>0){batteryVariant(c);return;}
        int level=demo?76:DeskData.batteryLevel;
        boolean full=!demo&&DeskData.batteryFull,charging=demo||DeskData.batteryCharging,plugged=demo||DeskData.batteryPlugged;
        int warning=level<=20&&!dark?0xffff987d:ink;
        int accent=gradient>0?ink:warning;
        header(c,"电量");
        batteryArc.set(54,74,306,326);
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(7);
        themeColor((muted&0xffffff)|0x46000000,70,batteryArc.left,batteryArc.top,batteryArc.right,batteryArc.bottom);
        c.drawArc(batteryArc,135,270,false,p);
        if(level>0){
            themeColor(accent,255,batteryArc.left,batteryArc.top,batteryArc.right,batteryArc.bottom);
            c.drawArc(batteryArc,135,270*level/100f,false,p);
        }
        p.setShader(null);p.setStyle(Paint.Style.FILL);
        txt(c,String.valueOf(level),172,222,84,accent,numbers,195);txt(c,"%",250,220,23,muted,regular,35);
        String status=full?"已充满":charging?"正在充电":plugged?"已连接电源":"电池供电";
        txt(c,status,384,173,25,warning,bold,170);
        txt(c,full?"电量已就绪":charging?"能量补充中":plugged?"当前未充电":level<=20?"建议连接充电器":"可继续使用",384,210,18,muted,regular,170);
        float bx=360,by=247;
        themeColor(accent,255,bx,by,bx+46,by+23);
        strokeOutline(c,bx,by,bx+43,by+23,5,2);
        strokeLine(c,bx+46,by+8,bx+46,by+15,3);
        if(level>0)c.drawRoundRect(bx+4,by+4,bx+4+35*level/100f,by+19,2,2,p);
        p.setShader(null);
        if(charging){
            iconPath.reset();iconPath.moveTo(184,264);iconPath.lineTo(169,285);iconPath.lineTo(180,285);
            iconPath.lineTo(175,300);iconPath.lineTo(193,277);iconPath.lineTo(182,277);iconPath.close();
            themeColor(accent,255,165,260,196,302);c.drawPath(iconPath,p);p.setShader(null);
            if(effectsEnabled()&&!full){
                float angle=-90+360*(wallTime()%2400L/2400f);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);
                themeColor((accent&0xffffff)|0xaa000000,170,batteryArc.left,batteryArc.top,batteryArc.right,batteryArc.bottom);
                c.drawArc(batteryArc,angle-20,20,false,p);p.setStyle(Paint.Style.FILL);
                double rad=Math.toRadians(angle);
                c.drawCircle(180+(float)Math.cos(rad)*126,200+(float)Math.sin(rad)*126,4,p);
                p.setShader(null);animateEffect(33);
            }
        }
    }
    private void music(Canvas c) {
        MediaController controller = demo || !DeskData.musicAllowed ? null : DeskData.media;
        MediaMetadata meta = controller == null ? null : controller.getMetadata();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean available = demo || meta != null;
        boolean playing = demo || state != null && state.getState() == PlaybackState.STATE_PLAYING;
        long actions = demo ? ~0L : state == null ? 0 : state.getActions();
        Bitmap art = meta == null ? null : meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null && meta != null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        String title = demo ? "夜行 · Nightfall" : meta != null ? MusicLyrics.title(meta)
                : DeskData.musicAllowed ? "暂无播放" : "音乐未授权";
        String artist = demo ? "音乐组件预览" : meta != null ? meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                : DeskData.musicAllowed ? "在播放器中选择音乐" : "在设置中开启音乐访问";
        long duration = demo ? 213000 : meta == null ? 0 : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state == null ? -1 : state.getPosition();
        float speed = state == null ? 0 : state.getPlaybackSpeed();
        long update = state == null ? 0 : state.getLastPositionUpdateTime();
        boolean tracking = playing && position >= 0 && update > 0 && Float.isFinite(speed) && speed > 0;
        long pos = demo ? 74000 + (effectsEnabled() ? (testTime >= 0 ? testTime : SystemClock.uptimeMillis()) % 18000 : 0)
                : Math.max(0, position);
        if (tracking) pos += (long) (Math.max(0, SystemClock.elapsedRealtime() - update) * speed);
        if (duration > 0) pos = Math.min(pos, duration);

        String lyric = "", nextLyric = "";
        if (component.bool(MusicLyrics.SHOW, false)) {
            LrcLyrics timeline = demo ? LrcLyrics.PREVIEW : position >= 0 ? musicLyrics(controller, meta) : null;
            if (timeline != null) {
                int index = timeline.indexAt(pos);
                lyric = timeline.lineAt(index);
                float density = getResources().getDisplayMetrics().density;
                RectF bounds = cells[drawingSlot];
                if (!lyric.isEmpty() && bounds.width() >= 480 * density && bounds.height() >= 290 * density) {
                    nextLyric = timeline.lineAt(index + 1);
                }
                long next = timeline.nextTime(index);
                if (tracking && next != Long.MAX_VALUE && (duration <= 0 || next <= duration)) {
                    long delay = Math.max(16L, (long) Math.ceil((next - pos) / (double) speed));
                    if (delay <= 60000L) nextLyricAt = Math.min(nextLyricAt, SystemClock.uptimeMillis() + delay);
                }
            }
        }
        musicVisual(c, art, title, artist, available, playing, pos, duration, lyric, nextLyric);
        themedLine(c,54,202,446,202,(muted&0xffffff)|0x60000000,3,96);
        if(duration>0){float end=54+392*pos/(float)duration;themeColor(ink,255,54,202,446,202);strokeLine(c,54,202,end,202,3);c.drawCircle(end,202,4,p);p.setShader(null);}
        txt(c,available?MediaControls.time(pos):"—:—",82,237,16,muted,regular,90);txt(c,duration>0?MediaControls.time(duration):"—:—",418,237,16,muted,regular,90);
        for(int button=0;button<3;button++)musicButton(c,button,playing,available&&MediaControls.enabled(actions,playing,button));
    }

    private LrcLyrics musicLyrics(MediaController controller, MediaMetadata meta) {
        String key = MusicLyrics.key(controller, meta);
        String source = key == null ? null : MusicLyrics.library(getContext()).getString(key, null);
        if (!Objects.equals(lyricSource, source)) {
            lyricSource = source;
            lyrics = LrcLyrics.parse(source);
        }
        return lyrics;
    }

    private void updateLyricTick(long when) {
        if (when != Long.MAX_VALUE && (testTime >= 0 || !motionActive || !isAttachedToWindow()
                || getWindowVisibility() != VISIBLE || !isShown() || !getGlobalVisibleRect(visibleMusicRect))) {
            when = Long.MAX_VALUE;
        }
        if (scheduledLyricAt == when || (scheduledLyricAt != Long.MAX_VALUE && when != Long.MAX_VALUE
                && Math.abs(when - scheduledLyricAt) < 20L)) return;
        handler.removeCallbacks(lyricTick);
        scheduledLyricAt = when;
        if (when != Long.MAX_VALUE) handler.postAtTime(lyricTick, when);
    }

    private void musicVisual(Canvas c, Bitmap art, String title, String artist, boolean available,
                             boolean playing, long position, long duration, String lyric, String nextLyric) {
        String style = ComponentStyles.selected(component);
        String status = available ? (playing ? "正在播放" : "已暂停") : "音乐";
        boolean liveMotion = effectsEnabled() && getWindowVisibility() == VISIBLE && isShown()
                && getGlobalVisibleRect(visibleMusicRect);
        float progress = duration > 0 ? position / (float) duration : Float.NaN;
        if (musicArtwork == null) musicArtwork = new MusicArtwork();
        if (style.equals("cover")) {
            coverArt(c,art,42,42,144,144,false);
            musicMetadata(c, title, artist, status, available, true, lyric, nextLyric);
        } else if (style.equals("record")) {
            musicArtwork.record(c, ComponentStyles.vectorArtwork(component), dark, ink, gradient, art, position, progress, effectsEnabled());
            musicMetadata(c, title, artist, status, available, false, lyric, nextLyric);
            if (available && playing && liveMotion) animateEffect(33);
        } else if (style.equals("music_type")) {
            primaryRect(c,54,57,59,166,2);
            textLeft(c,shorten(title,43),75,121,59,available?ink:muted,Typeface.SERIF,365);
            if (lyric.isEmpty()) textLeft(c,shorten(artist,44),78,164,23,muted,regular,365);
            else {
                musicLyric(c, lyric, 78, 161, 23, 365, true, true);
                musicLyric(c, nextLyric, 78, 184, 17, 365, false, true);
            }
        } else if (style.equals("spectrum")) {
            txt(c, shorten(title, 50), 250, nextLyric.isEmpty() ? 45 : 34, 29, available ? ink : muted, bold, 430);
            boolean enabled = !demo && MusicSpectrum.enabled(getContext(), prefs);
            boolean active = available && playing && liveMotion && enabled;
            if (musicSpectrum == null) musicSpectrum = new MusicSpectrum();
            spectrumUsed = !demo;
            float[] levels = demo ? MusicSpectrum.demoLevels() : musicSpectrum.levels(active);
            String detail = demo ? "示例频谱" : available && playing && liveMotion && !enabled ? "播放中 · 频谱已关闭" : artist;
            if (lyric.isEmpty()) {
                String secondary = component.bool(MusicLyrics.SHOW, false) && artist != null && !artist.isEmpty()
                        ? artist : detail;
                txt(c, shorten(secondary, 60), 250, 72, 17, muted, regular, 430);
            }
            else {
                musicLyric(c, lyric, 250, nextLyric.isEmpty() ? 72 : 57, 18, 430, true, false);
                musicLyric(c, nextLyric, 250, 78, 15, 430, false, false);
            }
            musicArtwork.spectrum(c, levels, ink, gradient, dark);
            if ((active && musicSpectrum.capturing()) || (liveMotion && musicSpectrum.moving())) animateEffect(48);
        } else {
            musicArtwork.cassette(c, ComponentStyles.vectorArtwork(component), dark, ink, gradient,
                    available ? title : null, position, duration, progress, effectsEnabled());
            musicMetadata(c, title, artist, status, available, false, lyric, nextLyric);
            if (available && playing && liveMotion) animateEffect(33);
        }
    }

    private void musicMetadata(Canvas c, String title, String artist, String status, boolean available,
                               boolean cover, String lyric, String nextLyric) {
        float x = cover ? 320 : 365, width = cover ? 290 : 205;
        txt(c, shorten(title, 40), x, cover ? 80 : 83, cover ? 26 : 28, available ? ink : muted, bold, width);
        if (lyric.isEmpty()) txt(c, shorten(artist, 44), x, cover ? 116 : 119, 18, muted, regular, width);
        else musicLyric(c, lyric, x, cover ? 116 : 119, 20, width, true, false);
        if (nextLyric.isEmpty()) txt(c, status, x, cover ? 150 : 149, 14, muted, regular, width);
        else musicLyric(c, nextLyric, x, cover ? 150 : 149, 16, width, false, false);
    }

    private void musicLyric(Canvas c, String line, float x, float y, float size, float width,
                            boolean current, boolean leftAlign) {
        if (line.isEmpty()) return;
        Typeface face = current ? bold : regular;
        int color = current ? ink : muted;
        p.setTypeface(face);
        p.setTextSize(size);
        String fitted = TextUtils.ellipsize(line, p, width, TextUtils.TruncateAt.END).toString();
        if (leftAlign) textLeft(c, fitted, x, y, size, color, face, width);
        else txt(c, fitted, x, y, size, color, face, width);
    }

    private void resetMusicSpectrum() {
        if (musicSpectrum != null) musicSpectrum.reset();
    }
    private void coverArt(Canvas c,Bitmap art,float l,float t,float r,float b,boolean round){
        artRect.set(l,t,r,b);float radius=round?(r-l)/2:18;
        p.setShader(null);p.setStyle(Paint.Style.FILL);p.setAlpha(255);
        if(art!=null&&!dark){artClip.reset();artClip.addRoundRect(artRect,radius,radius,Path.Direction.CW);int save=c.save();c.clipPath(artClip);c.drawBitmap(art,null,artRect,p);c.restoreToCount(save);}
        else{
            themedOutline(c,l,t,r,b,radius,muted,1.5f,160);float cx=(l+r)/2,cy=(t+b)/2,s=(r-l)/102;
            themeShader(l,t,r,b);line(c,cx-10*s,cy+15*s,cx-10*s,cy-23*s,ink,3*s);line(c,cx-10*s,cy-23*s,cx+18*s,cy-29*s,ink,3*s);line(c,cx+18*s,cy-29*s,cx+18*s,cy+7*s,ink,3*s);
            p.setColor(ink);c.drawOval(cx-24*s,cy+10*s,cx-9*s,cy+21*s,p);c.drawOval(cx+4*s,cy+2*s,cx+19*s,cy+13*s,p);p.setShader(null);
        }
    }
    private void musicButton(Canvas c, int button, boolean playing, boolean enabled) {
        boolean primary = button == 1;
        float x = MediaControls.X[button], y = MediaControls.Y, radius = primary ? 43 : 31;
        int foreground = enabled ? ink : (muted & 0xffffff) | 0x68000000;
        boolean pressed = pressedMusicSlot == drawingSlot && pressedMusicButton == button;
        float feedback = feedbackSlot == drawingSlot && feedbackButton == button
                ? Math.max(0, 1 - (SystemClock.uptimeMillis() - feedbackAt) / 220f) : 0;
        c.save();
        if (pressed) c.scale(.94f, .94f, x, y);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(primary ? 2.5f : 1.5f);
        p.setColor(foreground);
        if (!primary) p.setAlpha(enabled ? 150 : 104);
        themeShader(x - radius, y - radius, x + radius, y + radius);
        c.drawCircle(x, y, radius, p);
        if ((pressed || feedback > 0) && enabled) {
            p.setStrokeWidth(2);
            p.setAlpha((int) (160 * (pressed ? 1 : feedback)));
            c.drawCircle(x, y, radius + 5 + (1 - feedback) * 4, p);
            if (!pressed) animateEffect(16);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(foreground);
        themeShader(x - 14, y - 14, x + 14, y + 14);
        if (primary && playing) {
            c.drawRoundRect(x - 10, y - 13, x - 3, y + 13, 2, 2, p);
            c.drawRoundRect(x + 3, y - 13, x + 10, y + 13, 2, 2, p);
        } else {
            int direction = button == 0 ? -1 : 1;
            float edge = x - direction * (primary ? 7 : 9);
            float half = primary ? 14 : 10;
            iconPath.reset();
            iconPath.moveTo(edge, y - half);
            iconPath.lineTo(x + direction * (primary ? 13 : 6), y);
            iconPath.lineTo(edge, y + half);
            iconPath.close();
            c.drawPath(iconPath, p);
            if (!primary) {
                float stop = button == 0 ? x - 12 : x + 9;
                c.drawRoundRect(stop, y - 11, stop + 4, y + 11, 1, 1, p);
            }
        }
        p.setShader(null);
        c.restore();
    }
    private int musicHit(int slot,float x,float y){RectF r=cells[slot];float scale=Math.min(r.width()/540f,r.height()/432f);float cs=page.slots[slot].type.equals("clock")?1f:page.slots[slot].options.optInt("scale",100)/100f;return MediaControls.hit(250+((x-r.centerX())/scale-sceneShiftX)/cs,200+((y-r.centerY())/scale-sceneShiftY)/cs);}
    private boolean musicAction(int button){MediaController controller=DeskData.media;if(demo||!DeskData.musicAllowed||controller==null)return false;PlaybackState state=controller.getPlaybackState();boolean playing=state!=null&&state.getState()==PlaybackState.STATE_PLAYING;if(state==null||!MediaControls.enabled(state.getActions(),playing,button))return false;MediaController.TransportControls controls=controller.getTransportControls();try{if(button==0)controls.skipToPrevious();else if(button==2)controls.skipToNext();else if(playing)controls.pause();else controls.play();return true;}catch(SecurityException error){return false;}}
    private String shorten(String s,int n){if(s==null)return "";return s.length()>n?s.substring(0,n-1)+"…":s;}
    private int todoPending(JSONArray tasks,int[] ordered){
        int pending=ordered.length;
        while(pending>0&&tasks.optJSONObject(ordered[pending-1]).optBoolean("done"))pending--;
        return pending;
    }
    private float todoMaxOffset(Scene.Component item){
        if(todoStore!=null){
            TodoStore.Display display=todoStore.display(todoStore.listFor(item));
            int today=display.today.size();
            boolean expanded=ComponentStyles.selected(item).equals("todo_board")&&expandedTodo.contains(item.id);
            int count=today+(expanded?display.undated.size():0);
            TodoLayout layout=TodoLayout.of(item);
            return layout.maxOffset(count,layout.managedLeadingItems(today,display.completed));
        }
        int[] ordered=TodoItems.visible(item);
        return TodoLayout.of(item).maxOffset(ordered.length,todoPending(TodoItems.all(item),ordered));
    }
    private float todoOffset(Scene.Component item,float maxOffset){
        float value=Math.max(0,Math.min(maxOffset,todoOffsets.getOrDefault(item.id,0f)));
        todoOffsets.put(item.id,value);return value;
    }
    private float componentScale(int slot){
        RectF r=cells[slot];return Math.min(r.width()/540f,r.height()/432f)*page.slots[slot].options.optInt("scale",100)/100f;
    }
    private float componentX(int slot,float x){float cs=page.slots[slot].options.optInt("scale",100)/100f;return 250+(x-cells[slot].centerX())/componentScale(slot)-sceneShiftX/cs;}
    private float componentY(int slot,float y){float cs=page.slots[slot].options.optInt("scale",100)/100f;return 200+(y-cells[slot].centerY())/componentScale(slot)-sceneShiftY/cs;}
    private boolean todoAllowed(){
        if(prefs.getBoolean("private",false))return true;
        if(todoStore==null)return !DeskData.keyguardLocked;
        KeyguardManager keyguard=getContext().getSystemService(KeyguardManager.class);
        return keyguard==null||!keyguard.isKeyguardLocked();
    }

    private void savedTodoTap(Scene.Component item,float x,float y){
        if(openTodo==null)return;
        TodoStore.Display display=todoStore.display(todoStore.listFor(item));
        boolean board=ComponentStyles.selected(item).equals("todo_board");
        if(y>=18&&y<80&&x>=416&&x<=480){
            openTodo.open(item,TodoStore.View.TODAY,null,true);
            return;
        }
        if(board&&y>=22&&y<86&&x>=252&&x<416){
            if(!expandedTodo.add(item.id))expandedTodo.remove(item.id);
            invalidate();
            return;
        }
        if(y>=TodoLayout.BOTTOM&&y<=385&&x>=25&&x<=475){
            TodoStore.View page=x<190&&display.overdue>0?TodoStore.View.OVERDUE
                    :x>321&&!board&&!display.undated.isEmpty()?TodoStore.View.UNDATED:TodoStore.View.TODAY;
            openTodo.open(item,page,null,false);
            return;
        }
        TodoLayout layout=TodoLayout.of(item);
        int today=display.today.size(),count=today+(board&&expandedTodo.contains(item.id)?display.undated.size():0);
        int leading=layout.managedLeadingItems(today,display.completed);
        int index=layout.hit(x,y,todoOffset(item,layout.maxOffset(count,leading)),count,leading);
        if(index<0)return;
        TodoTask task=index<today?display.today.get(index):display.undated.get(index-today);
        int column=layout.columns==2&&x>=layout.cellLeft(1)?1:0;
        if(x<=layout.cellLeft(column)+34 && toggleTodo!=null)toggleTodo.accept(task);
        else openTodo.open(item,TodoStore.View.TODAY,task.id,false);
    }
    private void scrollTodoTouch(float y){
        Scene.Component item=page.slots[todoTouchSlot];
        float value=todoDownOffset-(y-downY)/componentScale(todoTouchSlot);
        todoOffsets.put(item.id,Math.max(0,Math.min(todoMaxOffset(item),value)));invalidate();
    }
    private void releaseTodoTouch(){todoTouchSlot=-1;todoDragging=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            resolve();downX=e.getX();downY=e.getY();downTime=SystemClock.uptimeMillis();longPressed=false;
            releaseTodoTouch();
            if(isLongClickable())handler.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());
            if(interaction!=null)interaction.run();pressedMusicSlot=-1;
            if(slotTap==null&&!pageAnimatingForCheck())for(int i=0;i<config.count();i++){
                if(!cells[i].contains(downX,downY))continue;
                if(config.slots[i].equals("music")){
                    int hit=musicHit(i,downX,downY);if(hit>=0){pressedMusicSlot=i;pressedMusicButton=hit;invalidate();}
                }else if(config.slots[i].equals("todo")&&!demo&&todoAllowed()){
                    Scene.Component item=page.slots[i];
                    float x=componentX(i,downX),y=componentY(i,downY);
                    float maxOffset=todoMaxOffset(item);
                    if(maxOffset>0&&TodoLayout.of(item).contains(x,y)){todoTouchSlot=i;todoDownOffset=todoOffset(item,maxOffset);}
                }
                break;
            }
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){handler.removeCallbacks(longPress);pressedMusicSlot=-1;releaseTodoTouch();invalidate();return true;}
        float dx=e.getX()-downX,dy=e.getY()-downY;
        int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if(action==MotionEvent.ACTION_MOVE){
            if(Math.abs(dy)+Math.abs(dx)>slop)handler.removeCallbacks(longPress);
            if(todoTouchSlot>=0&&(todoDragging||Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.25f)){
                todoDragging=true;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                scrollTodoTouch(e.getY());if(interaction!=null)interaction.run();return true;
            }
        }
        if(action==MotionEvent.ACTION_UP){
            handler.removeCallbacks(longPress);
            if(longPressed){releaseTodoTouch();return true;}
            if(todoTouchSlot>=0&&(todoDragging||Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)*1.25f)){
                scrollTodoTouch(e.getY());releaseTodoTouch();return true;
            }
            releaseTodoTouch();
            float threshold=40*getResources().getDisplayMetrics().density;
            if(Math.abs(dy)>threshold&&Math.abs(dy)>Math.abs(dx)*1.25f&&pageSwipe!=null){
                pressedMusicSlot=-1;
                int direction=dy<0?1:-1;
                if (!moveSubpage(direction)) pageSwipe.swipe(direction);
                return true;
            }
            if(Math.abs(dx)+Math.abs(dy)>slop){pressedMusicSlot=-1;invalidate();return true;}
            if(pageAnimatingForCheck())return true;
            if(previewPreset>=0||demo&&hasOnClickListeners()){performClick();return true;}
            for(int i=0;i<config.count();i++)if(cells[i].contains(e.getX(),e.getY())){
                if(slotTap!=null){slotTap.tap(i);return true;}
                String type=config.slots[i];
                if(type.equals("todo")&&todoAllowed()){
                    Scene.Component item=page.slots[i];float x=componentX(i,e.getX()),y=componentY(i,e.getY());
                    if(todoStore!=null){savedTodoTap(item,x,y);return true;}
                    int[] ordered=TodoItems.visible(item);
                    TodoLayout layout=TodoLayout.of(item);
                    int pending=todoPending(TodoItems.all(item),ordered);
                    float offset=todoOffset(item,layout.maxOffset(ordered.length,pending));
                    int index=layout.hit(x,y,offset,ordered.length,pending);
                    if(index>=0){
                        TodoItems.toggle(item,ordered[index]);
                        if(sceneChanged!=null)sceneChanged.run();else if(!bound)scene.save(prefs);
                        invalidate();
                    }
                    return true;
                }
                if(type.equals("focus")&&e.getY()>cells[i].centerY()){
                    if(SystemClock.uptimeMillis()-downTime>600)DeskData.resetFocus(prefs);else DeskData.toggleFocus(prefs);invalidate();return true;
                }
                if(type.equals("alarm")){if(!demo)AlarmData.openNext(getContext());return true;}
                if(type.equals("music")){
                    int hit=musicHit(i,e.getX(),e.getY());
                    if(pressedMusicSlot==i&&hit==pressedMusicButton&&musicAction(hit)){feedbackSlot=i;feedbackButton=hit;feedbackAt=SystemClock.uptimeMillis();}
                    pressedMusicSlot=-1;invalidate();return true;
                }
            }
            performClick();return true;
        }
        return true;
    }
    public boolean performClick(){super.performClick();return true;}
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);if(demo||slotTap!=null||pageAnimatingForCheck())return;resolve();
        if (pageSwipe != null) {
            int index = currentSubpage();
            int count = layoutPages(page.layout).size();
            if (index > 0 || pageIndex > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP);
            if (index + 1 < count || pageIndex + 1 < scene.pages.size()) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN);
            if (count > 1) info.setStateDescription("第 " + (index + 1) + " 部分，共 " + count + " 部分");
        }
        boolean music=false,alarm=false;for(int i=0;i<page.count();i++)if(!cells[i].isEmpty()){music|=page.slots[i].type.equals("music");alarm|=page.slots[i].type.equals("alarm");}
        if(todoAllowed())for(int i=0;i<page.count();i++)if(!cells[i].isEmpty()&&page.slots[i].type.equals("todo")){
            if(openTodo!=null){
                String list=todoStore.listName(todoStore.listFor(page.slots[i]));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(TODO_ACCESS_ACTION+i*2,"管理待办 · "+list));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(TODO_ACCESS_ACTION+i*2+1,"添加待办 · "+list));
            }
            Scene.Component item=page.slots[i];float maxOffset=todoMaxOffset(item);
            if(maxOffset<=0)continue;
            float offset=todoOffset(item,maxOffset);info.setScrollable(true);
            if(offset<maxOffset)info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if(offset>0)info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        if(alarm){
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ALARM_ACCESS_ACTION,"管理闹钟"));
            CharSequence description=info.getContentDescription();
            info.setContentDescription((description==null?"闹钟":description)+" · "+AlarmData.status(System.currentTimeMillis()));
        }
        if(!music||!DeskData.musicAllowed||DeskData.media==null)return;
        PlaybackState state=DeskData.media.getPlaybackState();if(state==null)return;boolean playing=state.getState()==PlaybackState.STATE_PLAYING;
        String[] names={"上一首",playing?"暂停":"播放","下一首"};for(int i=0;i<3;i++)if(MediaControls.enabled(state.getActions(),playing,i))info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(MUSIC_ACCESS_ACTIONS[i],names[i]));
    }
    public boolean performAccessibilityAction(int action,Bundle args){
        int todoAction=action-TODO_ACCESS_ACTION;
        if(todoAction>=0&&todoAction<8&&openTodo!=null&&slotTap==null&&!demo&&!pageAnimatingForCheck()&&todoAllowed()){
            resolve();
            int slot=todoAction/2;
            if(slot<page.count()&&!cells[slot].isEmpty()&&page.slots[slot].type.equals("todo")){
                if(interaction!=null)interaction.run();
                openTodo.open(page.slots[slot],TodoStore.View.TODAY,null,todoAction%2==1);
                return true;
            }
        }
        int pageUp = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.getId();
        int pageDown = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.getId();
        if (pageSwipe != null && (action == pageUp || action == pageDown)) {
            int direction = action == pageDown ? 1 : -1;
            if (interaction != null) interaction.run();
            if (!moveSubpage(direction)) pageSwipe.swipe(direction);
            return true;
        }
        if((action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)&&slotTap==null&&!demo&&!pageAnimatingForCheck()&&todoAllowed()){
            resolve();int direction=action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1;
            for(int i=0;i<page.count();i++)if(!cells[i].isEmpty()&&page.slots[i].type.equals("todo")){
                Scene.Component item=page.slots[i];float maxOffset=todoMaxOffset(item);
                float old=todoOffset(item,maxOffset),next=Math.max(0,Math.min(maxOffset,old+direction*TodoLayout.of(item).viewport()));
                if(next!=old){todoOffsets.put(item.id,next);if(interaction!=null)interaction.run();invalidate();return true;}
            }
            return false;
        }
        if(action==ALARM_ACCESS_ACTION&&slotTap==null&&!demo&&!pageAnimatingForCheck()){
            resolve();for(int slot=0;slot<page.count();slot++)if(!cells[slot].isEmpty()&&page.slots[slot].type.equals("alarm"))return AlarmData.openNext(getContext());return false;
        }
        for(int i=0;i<3;i++)if(action==MUSIC_ACCESS_ACTIONS[i]&&slotTap==null&&!pageAnimatingForCheck()){resolve();for(int slot=0;slot<page.count();slot++)if(!cells[slot].isEmpty()&&page.slots[slot].type.equals("music")){boolean sent=musicAction(i);invalidate();return sent;}return false;}
        return super.performAccessibilityAction(action,args);
    }
}
