package com.foldstandby.clock;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Regression checks for large lists, edge dragging, overlay navigation and automatic persistence. */
final class WorkspaceChecks {
    private final Instrumentation runner;
    private Context context;
    private SharedPreferences prefs;
    private MainActivity activity;
    private int assertions, renders;
    private boolean timeOnly;
    private File directory;
    private WorkspaceChecks(Instrumentation runner){this.runner=runner;}
    static Bundle run(Instrumentation runner){return new WorkspaceChecks(runner).run();}
    static Bundle runTime(Instrumentation runner){WorkspaceChecks checks=new WorkspaceChecks(runner);checks.timeOnly=true;return checks.run();}
    private void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private void sync(Runnable action){
        Throwable[] error={null};runner.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private Bundle run(){
        context=runner.getTargetContext();prefs=context.getSharedPreferences("ui_checks",0);
        Map<String,?> original=prefs.getAll();boolean locked=DeskData.keyguardLocked;
        directory=new File(context.getFilesDir(),"qa-0.4.1");directory.mkdirs();Bundle result=new Bundle();
        try{
            sync(()->{prefs.edit().clear().putBoolean("night",false).putBoolean("motion",false).commit();DeskData.updateSystemState(context,true);DeskData.keyguardLocked=false;if(timeOnly){timeDisplayChecks();}else{todoChecks();layoutChecks();timeDisplayChecks();iconChecks();}});
            if(!timeOnly)runtimeChecks();result.putBoolean("passed",true);
            result.putString("result","PASS: "+assertions+(timeOnly?" time display checks, ":" workspace checks, ")+renders+" renders"+(timeOnly?"":", bidirectional native edge drag and automatic save/reopen"));
        }catch(Throwable t){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(t));}
        finally{
            sync(()->{if(activity!=null)activity.finish();DeskData.keyguardLocked=locked;restore(original);});
        }
        return result;
    }
    private Scene sample(){
        Scene s=Scene.load(prefs);s.pages.clear();
        for(int i=0;i<8;i++){Scene.Page p=new Scene.Page();p.name="测试页面 "+(i+1);p.autoName=false;p.layout=0;s.pages.add(p);}
        s.home=s.pages.get(7).id;
        Scene.Component todo=s.pages.get(7).slots[0];todo.type="todo";
        for(int i=0;i<14;i++)TodoItems.add(todo,"事项 "+(i+1)+" · 查看全部待办");
        TodoItems.toggle(todo,0);TodoItems.toggle(todo,1);return s;
    }
    private Bitmap render(View view,int w,int h,String name){
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));renders++;
        if(name!=null)try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException e){throw new AssertionError(e);}
        return bitmap;
    }
    private void todoChecks(){
        Scene scene=sample();Scene.Component todo=scene.pages.get(7).slots[0];int[] ordered=TodoItems.visible(todo);
        check(ordered.length==14&&ordered[0]==2&&ordered[12]==0&&ordered[13]==1,"every task retained, completed tasks at end");
        Scene copy=scene.copy();check(TodoItems.all(copy.pages.get(7).slots[0]).length()==14,"completed tasks survive scene copying");
        int[] swipes={0};
        for(int style=0;style<ComponentStyles.ids("todo").length;style++)for(int scale:new int[]{75,100,125}){
            todo.style=ComponentStyles.ids("todo")[style];Scene.put(todo.options,"scale",scale);
            TodoLayout body=TodoLayout.of(todo);
            boolean board=todo.style.equals("todo_board");
            float swipeX=body.cellLeft(0)+body.cellWidth()/2;
            ClockFace face=new ClockFace(context,prefs);face.bind(scene,7);face.nightPreview=false;face.pageSwipe=n->swipes[0]++;
            Bitmap before=render(face,1080,684,"todo-"+style+"-"+scale+"-top");
            for(int i=0;i<5;i++)gesture(face,swipeX,295,swipeX,105,scale);
            Bitmap after=render(face,1080,684,"todo-"+style+"-"+scale+"-end");
            check(!before.sameAs(after),"scroll reaches tail for style/scale "+style+"/"+scale);before.recycle();after.recycle();
            check(swipes[0]==0,"list gesture does not switch scene page");
            int last=board?ordered[11]:ordered[13];
            float lastX=body.cellLeft(board?0:body.columns-1)+body.cellWidth()/2;
            boolean old=TodoItems.all(todo).optJSONObject(last).optBoolean("done");
            gesture(face,lastX,308,lastX,308,scale);
            check(TodoItems.all(todo).optJSONObject(last).optBoolean("done")!=old,"scrolled tap toggles last row, not first row");
            check(TodoItems.all(todo).length()==14,"completion never deletes or archives");TodoItems.toggle(todo,last);
            check(face.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null),"accessible scroll returns toward top");
            // A gesture outside the task body remains available to switch pages.
            gesture(face,495,300,495,95,scale);check(swipes[0]>0,"outside list can still switch pages");swipes[0]=0;
        }
        ComponentStyles.select(todo,"todo",0);Scene.put(todo.options,"scale",100);
        ClockFace face=new ClockFace(context,prefs);face.bind(scene,7);face.nightPreview=false;render(face,1080,684,null).recycle();
        DeskData.keyguardLocked=true;gesture(face,250,295,250,105,100);gesture(face,250,112,250,112,100);
        check(!TodoItems.all(todo).optJSONObject(2).optBoolean("done"),"locked private list cannot toggle tasks");DeskData.keyguardLocked=false;
        face.sceneChanged=()->scene.save(prefs);gesture(face,250,112,250,112,100);
        check(TodoItems.all(Scene.load(prefs).pages.get(7).slots[0]).optJSONObject(2).optBoolean("done"),"fullscreen completion persists in bound scene");
    }
    private void gesture(ClockFace face,float x,float y,float endX,float endY,int size){
        float w=face.getWidth(),h=face.getHeight(),pad=Math.min(w,h)*.035f;
        float unit=Math.min((w-2*pad)/540f,(h-2*pad)/432f)*size/100f;
        long time=SystemClock.uptimeMillis();float sx=w/2+(x-250)*unit,sy=h/2+(y-200)*unit,ex=w/2+(endX-250)*unit,ey=h/2+(endY-200)*unit;
        for(int a:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(time,time+(a==0?0:180),a,a==0?sx:ex,a==0?sy:ey,0);face.onTouchEvent(event);event.recycle();
        }
    }
    private void layoutChecks(){
        sample().save(prefs);MainActivity editor=new MainActivity();editor.prepareForCheck(context);
        for(float font:new float[]{1f,1.3f})for(int[] window:new int[][]{{400,820},{820,400},{700,980},{980,700},{360,500}}){
            prefs.edit().putFloat("uiFontScale",font).commit();editor.selectForCheck(1,0);
            String label=window[0]+"x"+window[1]+"-"+font;
            View closed=editor.createUi(window[0],window[1]);float density=context.getResources().getDisplayMetrics().density;
            int w=Math.round(window[0]*density),h=Math.round(window[1]*density);render(closed,w,h,"workspace-"+label).recycle();
            ClockFace face=face(closed.findViewWithTag("page:"+editor.draft.home));check(face!=null&&face.getWidth()>0,"selected preview exists "+label);
            int previewWidth=face.getWidth(),previewHeight=face.getHeight();
            MainActivity.WatchCarouselScrollView carousel=editor.carouselForCheck();
            check(previewHeight<carousel.getHeight(),"preview fits reserved vertical space "+label);
            float aspect = Math.max(window[0],window[1])/(float)Math.min(window[0],window[1]);
            check(previewHeight>=UiKit.dp(context,24)&&Math.abs(previewWidth/(float)previewHeight-aspect)<.03f,"preview uses the current window's landscape aspect ratio "+label);
            check(find(closed,"保存更改")==null&&find(closed,"取消")==null&&find(closed,"全屏预览")==null&&find(closed,"设置")==null,"duplicate chrome removed "+label);
            editor.navigationForCheck(true);View open=editor.createUi(window[0],window[1]);render(open,w,h,"navigation-"+label).recycle();
            View drawer=open.findViewWithTag("navigation:drawer");
            check(drawer.getLeft()==0&&drawer.getTop()==0&&drawer.getHeight()==h&&drawer.getWidth()<w,"left overlay fills height, covers part of content "+label);
            ClockFace unchanged=face(open.findViewWithTag("page:"+editor.draft.home));
            View content=open.findViewWithTag("workspace:content");
            if(window[0]>window[1]){
                check(content.getLeft()>=drawer.getRight(),"landscape drawer reserves content width "+label);
                check(content.getWidth()==w-drawer.getWidth(),"landscape work area fits beside menu "+label);
            }else{
                check(content.getLeft()==0&&content.getWidth()==w,"portrait keeps overlay "+label);
                check(unchanged.getWidth()==previewWidth&&unchanged.getHeight()==previewHeight,"portrait drawer preserves preview space "+label);
            }
            check(find(open,"全屏待机预览")==null,"preview actions are local to the canvas");
            editor.navigationForCheck(false);
        }
        prefs.edit().putFloat("uiFontScale",1f).commit();
        for(int[] window:new int[][]{{400,820},{820,400},{360,500},{980,700}}){
            int w=UiKit.dp(context,window[0]),h=UiKit.dp(context,window[1]);
            for(int group=0;group<6;group++){
                editor.selectForCheck(3,group);View setting=editor.createUi(window[0],window[1]);
                render(setting,w,h,"settings-"+window[0]+"x"+window[1]+"-"+group).recycle();
                check(face(setting)==null&&editor.carouselForCheck()==null,"settings never show scene previews "+group);
                check(find(setting,"← 返回页面")==null,"settings have no return button");
                View detail=setting.findViewWithTag("settings:detail"),menu=setting.findViewWithTag("settings:categories");
                check(detail!=null&&find(setting,"‹")!=null,"settings have explicit back navigation and details");
                if(menu!=null)check(window[0]>window[1]&&detail.getLeft()>=menu.getRight(),"wide landscape settings have parallel category/detail panes");
                else check(detail.getWidth()>0,"compact settings use a reachable category selector");
                editor.navigationForCheck(true);View opened=editor.createUi(window[0],window[1]);render(opened,w,h,null).recycle();
                check(face(opened)==null,"settings menu never creates a preview");
                View content=opened.findViewWithTag("workspace:content"),drawer=opened.findViewWithTag("navigation:drawer");
                check(window[0]>window[1]?content.getLeft()>=drawer.getRight():content.getLeft()==0,"settings obey orientation navigation behavior");
                editor.navigationForCheck(false);
            }
        }
    }
    private Rect localRect(Bitmap b,float l,float t,float r,float bottom){
        float pad=Math.min(b.getWidth(),b.getHeight())*.035f;
        float unit=Math.min((b.getWidth()-2*pad)/540f,(b.getHeight()-2*pad)/432f);
        return new Rect(Math.round(b.getWidth()/2f+(l-250)*unit),Math.round(b.getHeight()/2f+(t-200)*unit),
            Math.round(b.getWidth()/2f+(r-250)*unit),Math.round(b.getHeight()/2f+(bottom-200)*unit));
    }
    private int pixels(Bitmap b,Rect area,int color){
        int count=0;for(int y=area.top;y<area.bottom;y++)for(int x=area.left;x<area.right;x++)if(b.getPixel(x,y)==color)count++;return count;
    }
    private Rect litBounds(Bitmap b,Rect area){
        Rect found=new Rect();boolean any=false;
        for(int y=area.top;y<area.bottom;y++)for(int x=area.left;x<area.right;x++){
            int color=b.getPixel(x,y);if(Color.red(color)+Color.green(color)+Color.blue(color)<180)continue;
            if(!any){found.set(x,y,x+1,y+1);any=true;}else found.union(x,y,x+1,y+1);
        }
        return found;
    }
    private void timeDisplayChecks(){
        Scene scene=sample();Scene.Page page=scene.pages.get(0);Scene.Component comp=page.slots[0];
        int legacy=0xffef48e8,green=0xff43f590,warm=0xffff7733,secondary=0xff667799;
        Scene.put(scene.theme,"time",green);Scene.put(scene.theme,"secondary",secondary);
        Scene.put(scene.nightTheme,"time",warm);scene.ownNightColors=true;prefs.edit().putBoolean("nightOwnColors",true).commit();
        for(org.json.JSONObject theme:new org.json.JSONObject[]{scene.theme,page.theme,comp.theme,scene.nightTheme})Scene.put(theme,"separator",legacy);
        Scene migrated=scene.copy();
        check(!migrated.theme.has("separator")&&!migrated.nightTheme.has("separator")&&!migrated.pages.get(0).theme.has("separator")&&!migrated.pages.get(0).slots[0].theme.has("separator"),"old separator overrides removed at every scope");
        check(migrated.color(migrated.pages.get(0),migrated.pages.get(0).slots[0],"time",false,false)==green,"migration preserves time color");
        PaletteEditor.State state=new PaletteEditor.State();state.mode=1;state.role=11;
        PaletteEditor palette=new PaletteEditor(context,comp.theme,role->scene.color(page,comp,Scene.ROLES[role],false,false),state,"",()->{},value->{});
        check(state.role==1,"removed separator role falls back to time text");
        check(!Arrays.asList(Scene.ROLE_NAMES).contains("时间分隔符"),"no independent separator color in editor");
        render(palette,1080,1800,"time-palette").recycle();
        Scene.put(comp.options,"date",false);
        for(String style:new String[]{"digital","flip","led","dot"})for(boolean night:new boolean[]{false,true})for(boolean seconds:new boolean[]{false,true})for(int gradient:new int[]{0,1}){
            comp.style=style;Scene.put(comp.options,"seconds",seconds);Scene.put(scene.theme,"timeGradient",gradient);
            ClockFace clock=new ClockFace(context,prefs);clock.bind(scene,0);clock.nightPreview=night;clock.testTime=1794828899000L;
            Bitmap image=render(clock,1080,684,"time-"+style+"-"+night+"-"+seconds+"-"+gradient);
            boolean visibleSeconds=seconds&&!night;float l,t,r,b;
            if(style.equals("digital")){float center=visibleSeconds?208:250;l=center-8;r=center+8;t=100;b=266;}
            else if(style.equals("flip")){l=244;r=256;t=180;b=234;}
            else{l=230;r=242;t=style.equals("led")?165:160;b=style.equals("led")?232:208;
                if(visibleSeconds){l*=.8f;r*=.8f;t=32+t*.8f;b=32+b*.8f;}}
            Rect colon=localRect(image,l,t,r,b);
            check(pixels(image,colon,legacy)==0,"legacy separator color ignored: "+style);
            if(gradient==0||night)check(pixels(image,colon,night?warm:green)>4,"colon follows current day/night time color: "+style);
            else check(!litBounds(image,colon).isEmpty()&&pixels(image,colon,green)==0,"colon follows time gradient: "+style);
            if(visibleSeconds&&!style.equals("flip")){
                Rect label=localRect(image,410,277,477,301);
                check(litBounds(image,label).isEmpty(),"seconds have no unit label: "+style);
            }
            image.recycle();
        }
        Scene.put(scene.theme,"timeGradient",0);Scene.put(comp.options,"seconds",false);Scene.put(comp.options,"date",true);
        for(String style:Scene.STYLES)for(int color:new int[]{green,warm}){
            comp.style=style;Scene.put(scene.theme,"time",color);
            ClockFace clock=new ClockFace(context,prefs);clock.bind(scene,0);clock.nightPreview=false;clock.testTime=1794828899000L;
            Bitmap image=render(clock,1080,684,"date-"+style+"-"+Integer.toHexString(color));
            boolean analog=style.equals("analog")||style.equals("industrial");
            Rect date=localRect(image,30,analog?367:24,470,analog?385:51);
            check(pixels(image,date,color)>10,"clock date follows changed time color: "+style);
            check(pixels(image,date,secondary)==0,"clock date is not stuck on secondary color: "+style);image.recycle();
        }
        for(String style:Scene.STYLES){
            comp.style=style;Scene.put(scene.theme,"timeGradient",1);
            ClockFace clock=new ClockFace(context,prefs);clock.bind(scene,0);clock.nightPreview=false;clock.testTime=1794828899000L;
            Bitmap image=render(clock,1080,684,"date-gradient-"+style);
            boolean analog=style.equals("analog")||style.equals("industrial");Rect date=localRect(image,30,analog?367:24,470,analog?385:51);
            check(!litBounds(image,date).isEmpty()&&pixels(image,date,warm)==0,"date follows gradient: "+style);image.recycle();
        }
        comp.style="digital";Scene.put(comp.options,"date",false);Scene.put(scene.theme,"timeGradient",0);
        for(int font=0;font<4;font++){
            Scene.put(comp.options,"font",font);
            ClockFace clock=new ClockFace(context,prefs);clock.bind(scene,0);clock.nightPreview=false;clock.testTime=1794828899000L;
            Bitmap image=render(clock,1080,684,"digital-compact-colon-"+font);
            Rect colon=litBounds(image,localRect(image,245,100,255,266));Rect numerals=litBounds(image,localRect(image,40,80,220,266));
            check(colon.height()>8&&colon.height()<numerals.height()*.7f,"compact colon for font "+font);
            check(Math.abs(colon.exactCenterY()-numerals.exactCenterY())<numerals.height()*.12f,"colon vertically centered for font "+font);image.recycle();
        }
    }
    private void iconChecks(){
        check(context.getApplicationInfo().loadLabel(context.getPackageManager()).toString().equals("时栖时钟"),"launcher uses new app name");
        android.graphics.drawable.Drawable icon=context.getApplicationInfo().loadIcon(context.getPackageManager());
        check(icon instanceof android.graphics.drawable.AdaptiveIconDrawable,"adaptive launcher icon");
        ImageView image=new ImageView(context);image.setImageDrawable(icon);image.setPadding(24,24,24,24);
        render(image,432,432,"app-icon").recycle();
        if(Build.VERSION.SDK_INT>=33){
            android.graphics.drawable.Drawable mono=((android.graphics.drawable.AdaptiveIconDrawable)icon).getMonochrome();
            check(mono!=null,"themed monochrome icon available");image.setImageDrawable(mono);render(image,432,432,"app-icon-monochrome").recycle();
        }
    }
    private void runtimeChecks() throws Exception {
        sync(()->sample().save(prefs));
        activity=(MainActivity)runner.startActivitySync(new Intent(context,MainActivity.class).putExtra("uiCheck",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runner.waitForIdleSync();sync(()->{activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);});SystemClock.sleep(800);runner.waitForIdleSync();
        String moved=activity.draft.home;
        nativeEdgeDrag(moved,true);sync(()->{check(activity.draft.index(moved)==0,"native edge drag moves last page to first");check(Scene.load(prefs).homeIndex()==0,"reorder automatically persisted with home identity");});
        nativeEdgeDrag(moved,false);sync(()->{check(activity.draft.index(moved)==7,"native edge drag moves first page to last");check(Scene.load(prefs).homeIndex()==7,"reverse reorder automatically persisted");});
        Instrumentation.ActivityMonitor monitor=runner.addMonitor(ClockActivity.class.getName(),null,false);
        sync(()->click("⛶"));
        ClockActivity fullscreen=(ClockActivity)runner.waitForMonitorWithTimeout(monitor,5000);runner.removeMonitor(monitor);
        check(fullscreen!=null,"navigation opens fullscreen preview");runner.waitForIdleSync();SystemClock.sleep(300);
        sync(()->{ClockFace preview=face(fullscreen.getWindow().getDecorView());gesture(preview,250,112,250,112,100);
            check(TodoItems.all(Scene.load(prefs).pages.get(7).slots[0]).optJSONObject(2).optBoolean("done"),"real fullscreen completion automatically saves");fullscreen.finish();});
        SystemClock.sleep(350);runner.waitForIdleSync();
        sync(()->check(TodoItems.all(activity.draft.pages.get(7).slots[0]).optJSONObject(2).optBoolean("done"),"returning from fullscreen refreshes editor completion"));
        sync(()->{click("☰");check(activity.getWindow().getDecorView().findViewWithTag("navigation:drawer")!=null,"menu opens left overlay");activity.onBackPressed();check(activity.getWindow().getDecorView().findViewWithTag("navigation:drawer")==null,"Back closes drawer");click("配色");click("自定义");activity.colorEditorForCheck().hexInput().setText("#47A1F2");});
        sync(()->{Scene loaded=Scene.load(prefs);Scene.Page p=loaded.pages.get(loaded.homeIndex());check(loaded.color(p,p.slots[0],"accent",false,false)==0xff47a1f2,"valid color automatically persisted");activity.colorEditorForCheck().hexInput().setText("#12");Scene last=Scene.load(prefs);Scene.Page q=last.pages.get(last.homeIndex());check(last.color(q,q.slots[0],"accent",false,false)==0xff47a1f2,"incomplete color cannot overwrite saved value");activity.colorEditorForCheck().hexInput().setText("#47A1F2");activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        SystemClock.sleep(750);runner.waitForIdleSync();
        sync(()->{click("☰");View drawer=activity.getWindow().getDecorView().findViewWithTag("navigation:drawer");check(drawer!=null&&drawer.getLeft()==0,"portrait uses same left overlay");activity.onBackPressed();activity.finish();});SystemClock.sleep(250);
        activity=(MainActivity)runner.startActivitySync(new Intent(context,MainActivity.class).putExtra("uiCheck",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));runner.waitForIdleSync();
        sync(()->{Scene.Page p=activity.draft.pages.get(activity.draft.homeIndex());check(activity.draft.color(p,p.slots[0],"accent",false,false)==0xff47a1f2,"auto-saved color survives close and reopen");check(activity.draft.homeIndex()==7,"auto-saved page order survives reopen");});
        sync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        SystemClock.sleep(750);runner.waitForIdleSync();
        sync(()->{click("☰");click("设置");View root=activity.getWindow().getDecorView();
            check(face(root)==null,"real settings screen has no live preview");
            check(root.findViewWithTag("settings:categories")!=null,"real landscape has category pane");
            View category = find(root, "数据与权限");
            ((View) category.getParent()).performClick();
            check(activity.getWindow().getDecorView().findViewWithTag("settings:weather") != null,
                "category selection updates details");
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
        SystemClock.sleep(750);runner.waitForIdleSync();
        sync(()->{View root=activity.getWindow().getDecorView();check(face(root)==null,"portrait settings stay free of previews");
            check(root.findViewWithTag("settings:categories")==null,"portrait switches to single settings pane");
            check(root.findViewWithTag("settings:detail") != null, "rotation retains selected settings details");
            click("‹");
            check(activity.getWindow().getDecorView().findViewWithTag("settings:categories") != null,
                "visible back returns to the portrait category list");
            activity.onBackPressed();
            check(face(activity.getWindow().getDecorView()) != null, "back from categories returns to the workspace");
        });
    }
    private void nativeEdgeDrag(String id,boolean up) throws Exception {
        int[] start=new int[2],edge=new int[2];int[] display={0};String[] origin={""};
        sync(()->{
            View source=face(activity.getWindow().getDecorView().findViewWithTag("page:"+id));
            Rect visible=new Rect();check(source.getLocalVisibleRect(visible)&&!visible.isEmpty(),"drag source is visible");
            source.getLocationOnScreen(start);Matrix matrix=new Matrix();source.transformMatrixToGlobal(matrix);
            float[] points={0,0,visible.exactCenterX(),visible.exactCenterY()};matrix.mapPoints(points);
            start[0]+=Math.round(points[2]-points[0]);start[1]+=Math.round(points[3]-points[1]);
            View list=activity.carouselForCheck();list.getLocationOnScreen(edge);edge[0]=start[0];edge[1]+=up?UiKit.dp(context,8):list.getHeight()-UiKit.dp(context,8);display[0]=activity.getDisplay().getDisplayId();
            origin[0]="down="+Arrays.toString(start)+" local="+visible+" display="+display[0];
        });
        String input="input -d "+display[0]+" motionevent ";
        // Always release injected input, including a failed assertion, to isolate later runs.
        try{
            shell(input+"DOWN "+start[0]+" "+start[1]);
            long deadline=SystemClock.uptimeMillis()+2500;boolean[] started={false};
            while(!started[0]&&SystemClock.uptimeMillis()<deadline){SystemClock.sleep(100);sync(()->{try{java.lang.reflect.Field field=activity.carouselForCheck().getClass().getDeclaredField("reordering");field.setAccessible(true);started[0]=field.getBoolean(activity.carouselForCheck());}catch(Exception e){throw new AssertionError(e);}});}
            sync(()->check(started[0],"native long press starts drag "+origin[0]));
            for(int step=1;step<=6;step++){int y=start[1]+(edge[1]-start[1])*step/6;shell(input+"MOVE "+edge[0]+" "+y);}
            SystemClock.sleep(3600);
            sync(()->{
                MainActivity.WatchCarouselScrollView carousel=activity.carouselForCheck();int actual=carousel.dropIndexForCheck();
                if(actual!=(up?0:7))render(activity.getWindow().getDecorView(),activity.getWindow().getDecorView().getWidth(),activity.getWindow().getDecorView().getHeight(),"drag-failure").recycle();
                check(actual==(up?0:7),"live destination reaches edge actual="+actual+" scroll="+carousel.getScrollY()+"/"+carousel.maxScrollForCheck());
                check(carousel.reorderOffsetForCheck(up?7:0)!=0,"dragged page previews its destination");
                check(carousel.reorderOffsetForCheck(up?0:7)!=0,"neighbor pages make room before drop");
                check(activity.draft.index(id)==(up?7:0),"scene order unchanged until drop");
                render(activity.getWindow().getDecorView(),activity.getWindow().getDecorView().getWidth(),activity.getWindow().getDecorView().getHeight(),up?"drag-to-first":"drag-to-last").recycle();
            });
        }finally{shell(input+"UP "+edge[0]+" "+edge[1]);}
        SystemClock.sleep(500);runner.waitForIdleSync();
    }
    private void shell(String command)throws IOException{
        try(ParcelFileDescriptor fd=runner.getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())){byte[] buffer=new byte[1024];while(in.read(buffer)>=0){}}
    }
    private void click(String label){View view=find(activity.getWindow().getDecorView(),label);check(view!=null,"button exists: "+label);view.performClick();}
    private View find(View view,String label){if(view instanceof TextView&&((TextView)view).getText().toString().equals(label))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),label);if(result!=null)return result;}}return null;}
    private ClockFace face(View view){if(view instanceof ClockFace)return (ClockFace)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ClockFace result=face(group.getChildAt(i));if(result!=null)return result;}}return null;}
    private void restore(Map<String,?> values){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){String k=entry.getKey();Object v=entry.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();}
}
