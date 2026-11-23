package com.foldstandby.clock;

import org.json.*;
import java.util.UUID;

/** Reads older scene data and supplies sample items for style previews. */
final class TodoItems {
    static JSONArray all(Scene.Component c){JSONArray a=c.options.optJSONArray("tasks");return a==null?new JSONArray():a;}
    static void add(Scene.Component c,String text){if(text.trim().isEmpty())return;JSONArray a=all(c);JSONObject task=new JSONObject();Scene.put(task,"id",UUID.randomUUID().toString());Scene.put(task,"text",text.trim());Scene.put(task,"done",false);a.put(task);Scene.put(c.options,"tasks",a);}
    static void toggle(Scene.Component c,int index){JSONObject task=all(c).optJSONObject(index);if(task!=null)Scene.put(task,"done",!task.optBoolean("done"));}
    static void edit(Scene.Component c,int index,String text){JSONObject task=all(c).optJSONObject(index);if(task!=null&&!text.trim().isEmpty())Scene.put(task,"text",text.trim());}
    static void remove(Scene.Component c,int index){JSONArray a=all(c);if(index>=0&&index<a.length()){a.remove(index);Scene.put(c.options,"tasks",a);}}
    /** All tasks, pending first. Completion never archives or deletes a task. */
    static int[] visible(Scene.Component c){JSONArray a=all(c);int[] result=new int[a.length()];int n=0;for(int pass=0;pass<2;pass++)for(int i=0;i<a.length();i++){JSONObject task=a.optJSONObject(i);if(task!=null&&task.optBoolean("done")== (pass==1))result[n++]=i;}return java.util.Arrays.copyOf(result,n);}
}
