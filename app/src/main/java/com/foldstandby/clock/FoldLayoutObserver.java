package com.foldstandby.clock;

import android.app.Activity;
import android.graphics.Rect;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Optional OEM layout extension; only reported separating folds affect layout. */
final class FoldLayoutObserver {
    private final Activity activity;private final Consumer<Rect> callback;
    private Object component,listener;private Rect last;
    FoldLayoutObserver(Activity a,Consumer<Rect> cb){activity=a;callback=cb;}
    void start(){try{
        Object extensions=Class.forName("androidx.window.extensions.WindowExtensionsProvider").getMethod("getWindowExtensions").invoke(null);
        component=extensions.getClass().getMethod("getWindowLayoutComponent").invoke(extensions);
        Method[] methods=component.getClass().getMethods();
        Method add=null;
        for(Method m:methods){
            if(m.getName().equals("addWindowLayoutInfoListener")&&m.getParameterTypes().length==2&&m.getParameterTypes()[0].isAssignableFrom(Activity.class)){
                if(add==null)add=m;
                Class<?> cType=m.getParameterTypes()[1];
                for(Method rm:methods){
                    if(rm.getName().equals("removeWindowLayoutInfoListener")&&rm.getParameterTypes().length==1&&rm.getParameterTypes()[0].equals(cType)){
                        add=m;break;
                    }
                }
            }
        }
        if(add==null)return;Class<?> consumer=add.getParameterTypes()[1];
        listener=Proxy.newProxyInstance(consumer.getClassLoader(),new Class<?>[]{consumer},(p,m,args)->{
            if(m.getName().equals("accept")){Rect bounds=null;Object info=args[0];Object features=info.getClass().getMethod("getDisplayFeatures").invoke(info);
                if(features instanceof List)for(Object f:(List<?>)features){try{int state=(Integer)f.getClass().getMethod("getState").invoke(f),type=(Integer)f.getClass().getMethod("getType").invoke(f);if(state==2||type==2){bounds=new Rect((Rect)f.getClass().getMethod("getBounds").invoke(f));break;}}catch(ReflectiveOperationException ignored){}}
                final Rect value=bounds;if(!Objects.equals(last,value)){last=value;activity.runOnUiThread(()->callback.accept(value));}return null;
            }if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==args[0];if(m.getName().equals("toString"))return "FoldLayoutListener";return null;
        });add.invoke(component,activity,listener);
    }catch(ReflectiveOperationException|RuntimeException ignored){component=null;listener=null;}}
    void stop(){if(component==null||listener==null)return;try{for(Method m:component.getClass().getMethods())if(m.getName().equals("removeWindowLayoutInfoListener")&&m.getParameterTypes().length==1&&m.getParameterTypes()[0].isInstance(listener)){m.invoke(component,listener);break;}}catch(ReflectiveOperationException|RuntimeException ignored){}finally{component=null;listener=null;}}
}
