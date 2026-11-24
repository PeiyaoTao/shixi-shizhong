package com.foldstandby.clock;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Isolated editor regression: real Android measurement, local recovery and keyboard/rotation. */
final class StudioChecks {
    private final Instrumentation runner;
    private Context context; private SharedPreferences prefs; private MainActivity live;
    private int checks,renders; private File output; private String activeCase="model";
    StudioChecks(Instrumentation runner){this.runner=runner;}
    private void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label+" ["+activeCase+"]");}
    private Object call(Object target,String method,Class<?>[] types,Object...args){
        try{Method m=target.getClass().getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(target,args);}
        catch(Exception e){throw new AssertionError(method,e);}
    }
    private Object call(Object target,String method){return call(target,method,new Class<?>[0]);}
    private void set(Object target,String field,Object value){
        try{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}
    }
    private Object get(Object target,String field){
        try{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}
    }
    private void sync(Runnable action){Throwable[] error={null};runner.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    static Bundle run(Instrumentation runner){return new StudioChecks(runner).run();}
    private Bundle run(){
        context=runner.getTargetContext();prefs=context.getSharedPreferences("ui_checks",0);Map<String,?> original=prefs.getAll();
        output=new File(context.getFilesDir(),"qa-0.5.1");output.mkdirs();Bundle result=new Bundle();
        try{
            sync(()->{prefs.edit().clear().putBoolean("night",false).putBoolean("motion",false).putBoolean("studio_guided",true).commit();models();layouts();previewPickers();settingsChecks();carouselChecks();});
            runtime();result.putBoolean("passed",true);result.putString("result","PASS: "+checks+" studio checks, "+renders+" renders; keyboard, rotation, selection, undo and recovery");
        }catch(Throwable t){result.putBoolean("passed",false);result.putString("result",android.util.Log.getStackTraceString(t));}
        finally{sync(()->{if(live!=null)live.finish();SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();});}
        return result;
    }
    private Scene sample(){
        Scene s=Scene.load(prefs);s.pages.get(0).layout=3;s.pages.get(0).autoName=false;s.pages.get(0).name="我的书桌 · 一个用于检查窄屏换行与省略的很长页面名称";
        s.pages.get(0).slots[1].setType("weather");s.pages.get(0).slots[2].setType("todo");TodoItems.add(s.pages.get(0).slots[2],"保留事项");
        s.home=s.pages.get(0).id;s.ownNightColors=true;return s;
    }
    private void models(){
        Scene s=sample();Scene.Page page=s.pages.get(0);Scene.Component c=page.slots[0];
        Scene.put(s.nightTheme,"time",0xffff6622);Scene.put(c.theme,"time",0xff44ff88);
        check(s.color(page,c,"time",true,false)==0xffff6622,"day override does not cover night");
        Scene.put(c.nightTheme,"time",0xffdd7799);check(s.color(page,c,"time",true,false)==0xffdd7799,"local night override");
        Scene copy=s.copy();check(Boolean.TRUE.equals(copy.ownNightColors),"night enabled state follows snapshot");
        check(copy.color(copy.pages.get(0),copy.pages.get(0).slots[0],"time",true,false)==0xffdd7799,"night override follows snapshot");
        try{JSONObject legacy=new JSONObject(s.json());legacy.put("version",3);Scene old=Scene.parse(legacy.toString());check(old.color(old.pages.get(0),old.pages.get(0).slots[0],"time",true,false)==0xff44ff88,"legacy local night appearance preserved");}catch(JSONException e){throw new AssertionError(e);}
        Scene.Component todo=page.slots[2];todo.setType("clock");todo.setType("todo");check(TodoItems.all(todo).length()==1,"component switch retains tasks");
        page.layout=0;Scene restored=s.copy();restored.pages.get(0).layout=3;check(TodoItems.all(restored.pages.get(0).slots[2]).length()==1,"hidden layout region retains data");
        s.save(prefs);MainActivity editor=new MainActivity();editor.prepareForCheck(context);
        String before=editor.draft.json();Scene.put(editor.draft.pages.get(0).slots[0].options,"scale",110);call(editor,"changed");
        editor.beginEdit();for(int scale:new int[]{112,115,120}){Scene.put(editor.draft.pages.get(0).slots[0].options,"scale",scale);call(editor,"changed");}editor.endEdit();
        EditHistory history=(EditHistory)get(editor,"history");String previous=history.undo();call(editor,"restoreEditorState",new Class[]{String.class},previous);
        check(editor.draft.pages.get(0).slots[0].options.optInt("scale")==110,"one undo reverses complete slider drag");
        previous=history.undo();call(editor,"restoreEditorState",new Class[]{String.class},previous);check(editor.draft.json().equals(before),"second undo restores previous model");
        prefs.edit().putString("scene_saved_keep",editor.draft.json()).commit();
        call(editor,"saveRecoveryPoint",new Class[]{String.class},"测试恢复点");String points=prefs.getString("studio_recovery","");
        prefs.edit().remove("scene_saved_keep").commit();
        MainActivity reopened=new MainActivity();reopened.prepareForCheck(context);
        try{String state=new JSONArray(points).getJSONObject(0).getString("state");call(reopened,"restoreEditorState",new Class[]{String.class},state);check(prefs.contains("scene_saved_keep"),"persistent recovery restores deleted snapshot");}catch(JSONException e){throw new AssertionError(e);}
        sample().save(prefs);
    }
    private void draw(View view,int w,int h,String name){
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);view.getViewTreeObserver().dispatchOnPreDraw();
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));renders++;
        if(name!=null)try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}catch(IOException e){throw new AssertionError(e);}image.recycle();
    }
    private void bounds(View view){
        if(!(view instanceof ViewGroup))return;ViewGroup group=(ViewGroup)view;
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);if(child.getVisibility()!=View.VISIBLE)continue;
            if(!(group instanceof HorizontalScrollView))check(child.getLeft()>=-1&&child.getRight()<=group.getWidth()+1,"child width "+child.getClass().getSimpleName()+" in "+group.getClass().getSimpleName()+" "+child.getLeft()+".."+child.getRight()+"/"+group.getWidth());
            if(!(group instanceof ScrollView))check(child.getTop()>=-1&&child.getBottom()<=group.getHeight()+1,"child height "+child.getClass().getSimpleName()+" in "+group.getClass().getSimpleName()+" tag="+group.getTag()+" "+child.getTop()+".."+child.getBottom()+"/"+group.getHeight());
            bounds(child);
        }
    }
    private void layouts(){
        MainActivity editor=new MainActivity();editor.prepareForCheck(context);
        int[][] windows={{320,480},{360,500},{400,820},{820,400},{876,320},{700,980},{980,700},{820,1088},{580,620},{1600,900}};
        float density=context.getResources().getDisplayMetrics().density;
        for(float font:new float[]{1,1.3f,2.08f})for(int[] window:windows)for(int panel=0;panel<3;panel++){
            activeCase=window[0]+"x"+window[1]+" font="+font+" panel="+panel;
            prefs.edit().putFloat("uiFontScale",font).commit();set(editor,"studioPanel",panel);
            String name=font==1&&panel==0?"editor-"+window[0]+"x"+window[1]:null;
            View root=editor.createUi(window[0],window[1]);draw(root,Math.round(window[0]*density),Math.round(window[1]*density),name);try{bounds(root);}catch(AssertionError e){draw(root,Math.round(window[0]*density),Math.round(window[1]*density),"failure-layout");throw e;}
            View body=root.findViewWithTag("studio:body");check(body!=null&&((View)body.getParent()).getHeight()>30*density,"editor remains scrollable");
            ClockFace face=face(root);check(face!=null&&face.getHeight()>0,"preview remains visible");
        }
        prefs.edit().putFloat("uiFontScale",1).commit();
        for(int[] size:new int[][]{{400,820},{820,400},{980,700},{700,980}})for(boolean horizontal:new boolean[]{false,true})for(boolean navigation:new boolean[]{false,true}){
            int w=size[0],h=size[1];activeCase="fold "+w+"x"+h+" horizontal="+horizontal+" navigation="+navigation;Rect fold=horizontal?new Rect(0,Math.round(h*.45f*density),Math.round(w*density),Math.round(h*.45f*density)+8):new Rect(Math.round(w*.43f*density),0,Math.round(w*.43f*density)+8,Math.round(h*density));
            editor.foldForCheck(fold);editor.navigationForCheck(navigation);View root=editor.createUi(w,h);draw(root,Math.round(w*density),Math.round(h*density),!navigation?"fold-"+w+"x"+h+"-"+horizontal:null);bounds(root);
            ClockFace preview=face(root);Rect frame=position(preview,root);check(!Rect.intersects(frame,fold),"preview avoids hinge");
            View body=root.findViewWithTag("studio:body");Rect detail=position((View)body.getParent(),root);check(!Rect.intersects(detail,fold),"editor avoids hinge");
        }
        for(boolean vertical:new boolean[]{false,true}){
            Rect fold=vertical?new Rect(UiKit.dp(context,300),0,UiKit.dp(context,304),UiKit.dp(context,980)):new Rect(0,UiKit.dp(context,440),UiKit.dp(context,700),UiKit.dp(context,444));
            editor.foldForCheck(fold);editor.navigationForCheck(false);View resized=editor.createUi(700,980);draw(resized,UiKit.dp(context,700),UiKit.dp(context,500),"fold-resized-"+vertical);bounds(resized);
            check(!Rect.intersects(position((View)resized.findViewWithTag("studio:body").getParent(),resized),fold),"resized editor avoids hinge");
        }
        Rect lowFold=new Rect(0,UiKit.dp(context,120),UiKit.dp(context,700),UiKit.dp(context,124));editor.foldForCheck(lowFold);
        View lower=editor.createUi(700,800);draw(lower,UiKit.dp(context,700),UiKit.dp(context,300),"fold-low-ime");bounds(lower);check(!Rect.intersects(position((View)lower.findViewWithTag("studio:body").getParent(),lower),lowFold),"low hinge IME uses correct lower region coordinates");
        editor.foldForCheck(null);editor.navigationForCheck(false);
        set(editor,"studioPanel",2);
        View imeSized=editor.createUi(400,820);draw(imeSized,UiKit.dp(context,400),UiKit.dp(context,260),"editor-ime-small");bounds(imeSized);
        check(((View)imeSized.findViewWithTag("studio:body").getParent()).getHeight()>=UiKit.dp(context,48),"short IME editor retains usable scroll area");
        editor.selectForCheck(0,0);for(int[] size:new int[][]{{320,480},{820,400},{700,980}}){View root=editor.createUi(size[0],size[1]);draw(root,UiKit.dp(context,size[0]),UiKit.dp(context,size[1]),"selector-"+size[0]+"x"+size[1]);bounds(root);}
    }

    private void previewPickers() {
        MainActivity editor = new MainActivity();
        editor.prepareForCheck(context);
        String before = editor.draft.json();
        for (boolean adding : new boolean[]{true, false}) {
            call(editor, "showLayoutPicker", new Class[]{boolean.class}, adding);
            for (int[] window : new int[][]{{320,480}, {554,876}, {820,1088}, {480,320}, {876,320}, {980,700}}) {
                activeCase = (adding ? "add page " : "page layout ") + window[0] + "x" + window[1];
                View root = editor.createUi(window[0], window[1]);
                String screenshot = adding ? "add-page-" + window[0] + "x" + window[1] : null;
                draw(root, UiKit.dp(context, window[0]), UiKit.dp(context, window[1]), screenshot);
                bounds(root);
                for (int layout = 0; layout < Dashboard.LAYOUTS.length; layout++) {
                    View tile = root.findViewWithTag("page-layout:" + layout);
                    check(tile != null && tile.getBackground() == null, "page preview has no outer card");
                    ClockFace face = face(tile);
                    check(face.getWidth() == tile.getWidth() && face.getHeight() > UiKit.dp(context, 110), "page preview fills a larger frame");
                    check(Math.abs(face.getWidth() - face.getHeight() * AdaptiveLayout.PAGE_PREVIEW_ASPECT) < 4, "page preview preserves its ratio");
                    check(!face.hasSubpages(), "layout preview contains every slot");
                    check(tile.isSelected() == (!adding && editor.draft.pages.get(editor.draft.homeIndex()).layout == layout),
                            "only the existing layout is marked selected");
                }
                View first = root.findViewWithTag("page-layout:0");
                View second = root.findViewWithTag("page-layout:1");
                if (window[0] == 320 || window[0] == 554) {
                    check(first.getParent() != second.getParent(), "narrow portrait stacks page previews");
                    check(Math.abs(first.getWidth() - UiKit.dp(context, window[0] - 24)) <= 1, "single-column layout uses the available width");
                }
                if (window[0] == 980) check(first.getParent() == second.getParent(), "wide landscape compares layouts side by side");
                check(editor.draft.json().equals(before), "browsing layout choices leaves the scene intact");
            }
        }
        activeCase = "page layout next to a hinge";
        Rect hinge = new Rect(UiKit.dp(context, 400), 0, UiKit.dp(context, 420), UiKit.dp(context, 554));
        editor.foldForCheck(hinge);
        View folded = editor.createUi(876, 554);
        draw(folded, UiKit.dp(context, 876), UiKit.dp(context, 554), null);
        for (int layout = 0; layout < Dashboard.LAYOUTS.length; layout++) {
            check(!Rect.intersects(hinge, position(folded.findViewWithTag("page-layout:" + layout), folded)),
                    "layout choices avoid the hinge");
        }

        activeCase = "replace component in phone portrait";
        editor.foldForCheck(null);
        call(editor, "openSelector", new Class[]{boolean.class}, true);
        View components = editor.createUi(360, 780);
        draw(components, UiKit.dp(context, 360), UiKit.dp(context, 780), "component-picker-360x780");
        bounds(components);
        View weather = components.findViewWithTag("component:weather");
        check(weather != null && weather.getBackground() == null, "component picker uses the same border around the preview");
        check(face(weather).getWidth() == UiKit.dp(context, 336), "component previews use the phone width");
    }
    private void carouselChecks(){
        prefs.edit().putFloat("uiFontScale",1).commit();Scene scene=sample();scene.pages.clear();
        for(int i=0;i<8;i++){Scene.Page p=new Scene.Page();p.name="页面 "+(i+1);p.autoName=false;scene.pages.add(p);}scene.home=scene.pages.get(3).id;scene.save(prefs);
        MainActivity editor=new MainActivity();editor.prepareForCheck(context);
        for(int[] size:new int[][]{{320,480},{400,820},{554,876},{820,1088},{876,320},{980,700}}){
            activeCase="carousel "+size[0]+"x"+size[1];View root=editor.createUi(size[0],size[1]);draw(root,UiKit.dp(context,size[0]),UiKit.dp(context,size[1]),"carousel-"+size[0]+"x"+size[1]);bounds(root);
            MainActivity.WatchCarouselScrollView carousel=editor.carouselForCheck();ViewGroup track=(ViewGroup)carousel.getChildAt(0);
            check(track.getChildCount()==8,"editing retains all pages");
            RectF previous=transformedFrame(track.getChildAt(2),carousel),next=transformedFrame(track.getChildAt(4),carousel);
            check(previous.bottom>UiKit.dp(context,4)&&previous.top<carousel.getHeight(),"previous page edge is visible");
            check(next.top<carousel.getHeight()-UiKit.dp(context,4)&&next.bottom>0,"next page edge is visible");
            check(track.getChildAt(3).getScaleX()>track.getChildAt(2).getScaleX()&&track.getChildAt(2).getScaleX()>track.getChildAt(1).getScaleX(),"each page layer has a different scale");
            check(track.getChildAt(2).getAlpha()>track.getChildAt(1).getAlpha(),"farther page has lower opacity");
            if(size[0]<size[1]){
                Rect body=position((View)root.findViewWithTag("studio:body").getParent(),root);check(body.left<UiKit.dp(context,30)&&body.top>UiKit.dp(context,200),"inner and cover portrait share the vertical structure");
                if(size[0]>=400)check(((View)root.findViewWithTag("studio:body").getParent()).getTop()<UiKit.dp(context,64),"normal portrait inspector uses one compact row");
                if(size[1]>=700)check(face(root.findViewWithTag("page:"+scene.home)).getWidth()>UiKit.dp(context,size[0]*.70f),"portrait preview uses most of the width");
            }
            call(editor,"selectPageInPlace",new Class[]{int.class,int.class,boolean.class,boolean.class},3,0,true,false);
            View selected=editor.createUi(size[0],size[1]);draw(selected,UiKit.dp(context,size[0]),UiKit.dp(context,size[1]),null);
            check(((ViewGroup)editor.carouselForCheck().getChildAt(0)).getChildCount()==8,"selecting a component does not enter a hidden single-page mode");
        }
        sample().save(prefs);
    }
    private void settingsChecks() {
        MainActivity editor = new MainActivity();
        editor.prepareForCheck(context);
        prefs.edit().putBoolean("night", false).putInt("nightViewSeconds", 60)
            .putBoolean("autoplay", false).putInt("pageInterval", 30).putBoolean("motion", false)
            .putInt("pageMotionDuration", 520).commit();

        editor.selectForCheck(3, 0);
        int portraitWidth = UiKit.dp(context, 820);
        int portraitHeight = UiKit.dp(context, 1088);
        int edge = UiKit.dp(context, 6);
        set(editor, "settingsDetail", false);
        View overview = editor.createUi(820, 1088);
        draw(overview, portraitWidth, portraitHeight, null);
        View categoryPane = overview.findViewWithTag("settings:categories");
        check(categoryPane != null && overview.findViewWithTag("settings:detail") == null,
            "wide portrait opens on the category list");
        Rect categoryBounds = position(categoryPane, overview);
        check(categoryBounds.left == edge && categoryBounds.right == portraitWidth - edge,
            "portrait category list fills the safe width");

        set(editor, "settingsDetail", true);
        View detail = editor.createUi(820, 1088);
        draw(detail, portraitWidth, portraitHeight, null);
        check(detail.findViewWithTag("settings:categories") == null, "wide portrait settings use a single pane");
        Rect detailBounds = position(detail.findViewWithTag("settings:detail"), detail);
        check(detailBounds.left == edge && detailBounds.right == portraitWidth - edge,
            "portrait settings detail fills the safe width");
        check(textPrefix(detail, "夜间时段") == null && textPrefix(detail, "夜间亮度") == null,
            "disabled night settings are hidden");
        editor.selectForCheck(3, 1);
        detail = editor.createUi(820, 400);
        check(detail.findViewWithTag("settings:categories") != null, "wide landscape settings have two panes");
        check(textPrefix(detail, "轮播间隔") == null && textPrefix(detail, "切换速度") == null,
            "disabled autoplay and animation discard no values");
        check(prefs.getInt("pageInterval", 0) == 30 && prefs.getInt("pageMotionDuration", 0) == 520,
            "hidden preferences retain the prior values");
        editor.selectForCheck(3, 3);
        set(editor, "settingsDetail", false);
        float fontFactor = Math.max(1f, context.getResources().getConfiguration().fontScale
                * prefs.getFloat("uiFontScale", 1f) * .8f);
        int compactWidth = Math.round(550 * fontFactor) + 10;
        detail = editor.createUi(compactWidth, 350);
        draw(detail, UiKit.dp(context, compactWidth), UiKit.dp(context, 350), null);
        View categories = detail.findViewWithTag("settings:categories");
        View settings = detail.findViewWithTag("settings:detail");
        check(categories != null && settings != null && categories.getRight() < settings.getLeft(),
            "compact landscape opens with both panes, even from the main settings entry");
        check(textPrefix(settings, "充电时自动待机") != null, "side-by-side layout retains the current standby controls");
        Rect hinge = new Rect(UiKit.dp(context, 500), 0, UiKit.dp(context, 511), UiKit.dp(context, 650));
        editor.foldForCheck(hinge);
        detail = editor.createUi(1050, 650);
        draw(detail, UiKit.dp(context, 1050), UiKit.dp(context, 650), null);
        categories = detail.findViewWithTag("settings:categories");
        settings = detail.findViewWithTag("settings:detail");
        check(categories != null && settings != null, "landscape keeps both panes around a hinge");
        check(position(categories, detail).right <= hinge.left && position(settings, detail).left >= hinge.right,
            "settings categories and detail occupy opposite sides of a separating hinge");
        editor.foldForCheck(null);
        prefs.edit().putBoolean("night", true).putBoolean("nightSleep", false).putBoolean("autoplay", true).commit();
        editor.selectForCheck(3, 0);
        detail = editor.createUi(360, 540);
        check(textPrefix(detail, "夜间时段") != null && textPrefix(detail, "息屏前等待") == null,
            "only the enabled night controls are shown");
        editor.selectForCheck(3, 1);
        detail = editor.createUi(360, 540);
        check(textPrefix(detail, "轮播间隔") != null && textPrefix(detail, "夜间闲置回主页") != null,
            "the idle fallback is specifically marked for night when autoplay is enabled");
        editor.selectForCheck(3, 2);
        detail = editor.createUi(360, 540);
        check(textPrefix(detail, "默认专注时长 · ") != null, "data settings show focus duration row");

        editor.selectForCheck(3, 4);
        String sceneName = editor.draft.name;
        call(editor, "completeSave", new Class[]{String.class}, "新快照");
        check(sceneName.equals(editor.draft.name), "saving a snapshot does not rename the current scene");
        String recovery = (String) call(editor, "captureEditorState");
        String snapshot = prefs.getString("scene_saved_新快照", "");
        prefs.edit().putString("scene_saved_后来添加", snapshot).commit();
        editor.draft.name = "后来改名";
        call(editor, "restoreEditorState", new Class[]{String.class, boolean.class}, recovery, false);
        check(sceneName.equals(editor.draft.name) && prefs.contains("scene_saved_后来添加"),
            "scene-only restore retains snapshots created after the recovery point");
        call(editor, "restoreEditorState", new Class[]{String.class, boolean.class}, recovery, true);
        check(!prefs.contains("scene_saved_后来添加") && prefs.contains("scene_saved_新快照"),
            "whole recovery has an explicit different scope");
        prefs.edit().putBoolean("night", false).remove("nightSleep").remove("autoplay")
            .remove("scene_saved_新快照").apply();
    }

    private View textPrefix(View root, String prefix) {
        if (root instanceof TextView && ((TextView) root).getText().toString().startsWith(prefix)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = textPrefix(((ViewGroup) root).getChildAt(i), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private RectF transformedFrame(View item,MainActivity.WatchCarouselScrollView carousel){
        View frame=((ViewGroup)item).getChildAt(1);RectF rect=new RectF(frame.getLeft(),frame.getTop(),frame.getRight(),frame.getBottom());item.getMatrix().mapRect(rect);rect.offset(item.getLeft(),item.getTop()-carousel.getScrollY());return rect;
    }
    private void dialogChecks(){
        AlertDialog[] created={null};boolean[] dismissed={false};
        sync(()->{created[0]=AppDialogs.builder(live).setTitle("测试应用弹窗").setMessage("深色面板与薄荷绿按钮").setPositiveButton("确定",null).create();created[0].setOnDismissListener(d->dismissed[0]=true);created[0].show();});
        runner.waitForIdleSync();
        sync(()->{
            AlertDialog dialog=created[0];check(get(live,"studioDialog")==dialog,"create().show() uses app dialog registration");
            check(dialog.getWindow().getDecorView().getBackground() instanceof android.graphics.drawable.GradientDrawable,"app dialog has a rounded app surface");
            android.util.TypedValue accent=new android.util.TypedValue();dialog.getContext().getTheme().resolveAttribute(android.R.attr.colorAccent,accent,true);check(accent.data==UiKit.ACCENT,"dialog accent follows app palette");
            draw(dialog.getWindow().getDecorView(),dialog.getWindow().getDecorView().getMeasuredWidth(),dialog.getWindow().getDecorView().getMeasuredHeight(),"dialog-message");dialog.dismiss();
        });runner.waitForIdleSync();sync(()->{
            check(dismissed[0],"progress cancellation callback is preserved");
            LinearLayout host=new LinearLayout(live);int[] choice={-1};UiKit.choice(live,host,"测试选择",new String[]{"选项一","选项二"},0,n->choice[0]=n);host.getChildAt(0).performClick();
            AlertDialog selector=(AlertDialog)get(live,"studioDialog");check(selector!=created[0]&&selector.isShowing(),"generic choice uses app dialog factory");check(selector.getListView().isItemChecked(0),"current choice remains selected");draw(selector.getWindow().getDecorView(),selector.getWindow().getDecorView().getMeasuredWidth(),selector.getWindow().getDecorView().getMeasuredHeight(),"dialog-choices");selector.getListView().performItemClick(null,1,1);check(choice[0]==1,"choice semantics preserved");
            call(live, "showNightPeriod");
            InputDialog time = (InputDialog) get(live, "inputDialog");
            ArrayList<View> fields = new ArrayList<>();
            View form = time.getWindow().getDecorView();
            form.findViewsWithText(fields, "开始时间", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            EditText start = (EditText) fields.get(0);
            fields.clear();
            form.findViewsWithText(fields, "结束时间", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            EditText end = (EditText) fields.get(0);
            start.setText("25:90");
            text(form, "应用").performClick();
            check(start.isActivated(), "night schedule validates hours and minutes");
            start.setText("22:30");
            end.setText("22:30");
            text(form, "应用").performClick();
            AlertDialog allDay = (AlertDialog) get(live, "studioDialog");
            check(allDay.isShowing() && time.isShowing(), "equal times require confirmation for all-day mode");
            allDay.dismiss();
            end.setText("07:15");
            text(form, "应用").performClick();
            check(prefs.getInt("nightStartMinute", 0) == 30 && prefs.getInt("nightEndMinute", 0) == 15,
                "overnight start and end save together");
        });
        sync(() -> {
            DeskData.setFocusMinutes(prefs, 25);
            call(live, "showFocusDuration");
        });
        runner.waitForIdleSync();
        sync(() -> {
            InputDialog focusDialog = (InputDialog) get(live, "inputDialog");
            View decor = focusDialog.getWindow().getDecorView();
            FocusDurationPicker picker = decor.findViewWithTag("focus:duration-picker");
            TextView summaryView = decor.findViewWithTag("focus:duration-summary");
            check(picker != null && summaryView != null, "focus duration picker dialog opens");
            check(picker.hours() == 0 && picker.minutes() == 25, "picker initializes from saved duration");
            picker.setHoursMinutes(0, 0, false);
            check(summaryView.getText().toString().contains("至少为 1 分钟"), "zero duration shows validation message");
            text(decor, "应用").performClick();
            check(focusDialog.isShowing() && prefs.getInt("focusMinutes", 0) == 25, "zero duration prevents applying");
            picker.setHoursMinutes(1, 35, false);
            check(summaryView.getText().toString().equals("共 1 小时 35 分钟"), "picker updates live duration summary");
        });
        runner.waitForIdleSync();
        sync(() -> {
            InputDialog focusDialog = (InputDialog) get(live, "inputDialog");
            View decor = focusDialog.getWindow().getDecorView();
            draw(decor, decor.getMeasuredWidth(), decor.getMeasuredHeight(), "dialog-focus-duration");
            text(decor, "应用").performClick();
            check(!focusDialog.isShowing() && prefs.getInt("focusMinutes", 0) == 95, "custom hours and minutes save");
            call(live, "showFocusDuration");
        });
        runner.waitForIdleSync();
        sync(() -> {
            InputDialog focusDialog = (InputDialog) get(live, "inputDialog");
            View decor = focusDialog.getWindow().getDecorView();
            FocusDurationPicker picker = decor.findViewWithTag("focus:duration-picker");
            check(picker.hours() == 1 && picker.minutes() == 35, "reopening preserves saved custom hours and minutes");
            text(decor, "45 分钟").performClick();
            check(picker.hours() == 0 && picker.minutes() == 45, "preset chip selects hours and minutes");
            text(decor, "应用").performClick();
            check(!focusDialog.isShowing() && prefs.getInt("focusMinutes", 0) == 45, "preset chip selection saves");
            DeskData.setFocusMinutes(prefs, 25);
        });
    }
    private EditText findEdit(View root){if(root instanceof EditText)return(EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText found=findEdit(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private Rect position(View v,View root){Rect rect=new Rect(0,0,v.getWidth(),v.getHeight());((ViewGroup)root).offsetDescendantRectToMyCoords(v,rect);return rect;}
    private ClockFace face(View view){if(view instanceof ClockFace)return(ClockFace)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ClockFace found=face(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private View text(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().equals(value))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private void runtime()throws Exception{
        try{JSONObject old=new JSONObject(sample().json());old.put("version",3);old.remove("ownNightColors");prefs.edit().putString(Scene.KEY,old.toString()).putBoolean("nightOwnColors",true).putFloat("uiFontScale",1).commit();}catch(JSONException e){throw new AssertionError(e);}
        live=(MainActivity)runner.startActivitySync(new Intent(context,MainActivity.class).putExtra("uiCheck",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));runner.waitForIdleSync();
        sync(()->{check(Boolean.TRUE.equals(live.draft.ownNightColors),"legacy night setting survives creation and resume");check(!((EditHistory)get(live,"history")).canUndo(),"normalization does not create phantom history");live.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);live.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        SystemClock.sleep(700);runner.waitForIdleSync();
        sync(()->{set(live,"studioPanel",2);call(live,"show");text(live.getWindow().getDecorView(),"自定义").performClick();});runner.waitForIdleSync();SystemClock.sleep(200);
        sync(()->{EditText input=live.colorEditorForCheck().hexInput();input.requestFocus();input.setText("#48A1F2");live.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        SystemClock.sleep(900);runner.waitForIdleSync();
        sync(()->{EditText input=live.colorEditorForCheck().hexInput();Rect visible=new Rect();check(live.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"real IME is visible");check(input.getGlobalVisibleRect(visible)&&visible.height()==input.getHeight(),"HEX visible above keyboard");check(face(live.getWindow().getDecorView()).getHeight()>0,"keyboard retains preview");draw(live.getWindow().getDecorView(),live.getWindow().getDecorView().getWidth(),live.getWindow().getDecorView().getHeight(),"runtime-keyboard-portrait");live.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);});
        SystemClock.sleep(900);runner.waitForIdleSync();
        sync(()->{check(live.colorEditorForCheck().hexInput().getText().toString().equals("#48A1F2"),"color draft survives rotation");live.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(live.colorEditorForCheck().hexInput().getWindowToken(),0);call(live,"openSelector",new Class[]{boolean.class},true);});
        runner.waitForIdleSync();
        sync(()->{View type=live.getWindow().getDecorView().findViewWithTag("component:weather");check(type!=null,"explicit component selector");type.performClick();check(live.draft.pages.get(0).slots[0].type.equals("weather"),"component selector replaces current region");call(live,"undoEdit");check(live.draft.pages.get(0).slots[0].type.equals("clock"),"undo returns previous component");});
        sync(()->{int count=live.draft.pages.size();String home=live.draft.home;call(live,"showPageMenu");AlertDialog menu=(AlertDialog)get(live,"studioDialog");ListView list=menu.getListView();int last=list.getCount()-1;list.performItemClick(null,last,list.getAdapter().getItemId(last));check(live.draft.pages.size()==count-1,"page menu deletes page");call(live,"undoEdit");check(live.draft.pages.size()==count&&live.draft.home.equals(home),"undo restores page position and home identity");});
        sync(()->call(live,"showLayoutPicker",new Class[]{boolean.class},true));runner.waitForIdleSync();
        sync(()->{
            View choice = live.getWindow().getDecorView().findViewWithTag("page-layout:1");
            check(choice != null && choice.isClickable(), "page preview offers one direct action");
            int old = live.draft.pages.size();
            choice.performClick();
            check(live.draft.pages.size() == old + 1, "layout selection adds one page");
            Scene.Page added = live.draft.pages.get(old);
            check(added.layout == 1 && added.slots[0].type.equals("clock") && added.slots[1].type.equals("weather"),
                    "added page uses the layout and displayed defaults");
            call(live, "undoEdit");
            check(live.draft.pages.size() == old, "undo removes added page");

            String before = live.draft.json();
            call(live, "showLayoutPicker", new Class[]{boolean.class}, false);
            live.onBackPressed();
            check(live.draft.json().equals(before), "back dismisses the layout picker without an edit");

            Scene.Page page = live.draft.pages.get(live.draft.index((String)get(live, "pageId")));
            int previousLayout = page.layout;
            String hiddenSlot = page.slots[2].json().toString();
            call(live, "showLayoutPicker", new Class[]{boolean.class}, false);
            View decor = live.getWindow().getDecorView();
            check(decor.findViewWithTag("page-layout:" + previousLayout).isSelected(), "current page layout is selected");
            int newLayout = previousLayout == 0 ? 1 : 0;
            decor.findViewWithTag("page-layout:" + newLayout).performClick();
            check(page.layout == newLayout && page.slots[2].json().toString().equals(hiddenSlot), "layout change preserves hidden components");
            call(live, "undoEdit");
            check(live.draft.json().equals(before), "undo restores the previous layout and components");
        });
        dialogChecks();
        Instrumentation.ActivityMonitor monitor=runner.addMonitor(ClockActivity.class.getName(),null,false);
        sync(()->{set(live,"pageId",live.draft.pages.get(1).id);set(live,"previewNight",true);call(live,"launchFullscreenPreview");});
        ClockActivity preview=(ClockActivity)runner.waitForMonitorWithTimeout(monitor,5000);runner.removeMonitor(monitor);check(preview!=null,"fullscreen opened");runner.waitForIdleSync();
        sync(()->{ClockFace f=face(preview.getWindow().getDecorView());check(Boolean.TRUE.equals(f.nightPreview),"fullscreen continues selected night preview");check(f.pageIdForEditor().equals(live.draft.pages.get(1).id),"fullscreen continues selected page");View exit=preview.getWindow().getDecorView().findViewWithTag("preview:return");check(exit!=null,"fullscreen has an explicit return action");exit.performClick();});
    }
}
