package com.foldstandby.clock;

import android.content.SharedPreferences;
import org.json.JSONObject;

final class Dashboard {
    static final String[] TYPES={"digital","split","analog","date","calendar","weather","music","focus","battery","world"};
    static final String[] NAMES={"数字时钟","分体时钟","指针时钟","月历","日程","天气","音乐","专注","电量","世界时钟"};
    static final String[] PRESETS={"纯粹","今日","晴昼","唱片","专注","一览"};
    static final String[] LAYOUTS={"整屏","双栏","主次","四格"};
    int layout; String[] slots=new String[4]; int color; private final SharedPreferences source;
    Dashboard(SharedPreferences p){source=p;reload(p);}
    void reload(SharedPreferences p){layout=p.getInt("layout",0);color=p.getInt("color",0xffd8f6a5);for(int i=0;i<4;i++)slots[i]=p.getString("slot"+i,i==0?"split":i==1?"date":i==2?"weather":"battery");}
    int count(){return layout==0?1:layout==3?4:2;}
    void save(SharedPreferences p){SharedPreferences.Editor e=p.edit().putInt("layout",layout).putInt("color",color);for(int i=0;i<4;i++)e.putString("slot"+i,slots[i]);e.apply();}
    static String name(String id){for(int i=0;i<TYPES.length;i++)if(TYPES[i].equals(id))return NAMES[i];return "时钟";}
    static void preset(SharedPreferences p,int n){Dashboard d=new Dashboard(p);d.layout=n==0?0:n==5?3:1;
        String[][] all={{"split","date","weather","battery"},{"digital","date","calendar","battery"},{"analog","weather","date","battery"},{"music","digital","date","battery"},{"analog","focus","date","battery"},{"digital","weather","calendar","battery"}};
        d.slots=all[n].clone();d.save(p);}
    String json(){JSONObject j=new JSONObject();try{j.put("layout",layout).put("color",color);for(int i=0;i<4;i++)j.put("slot"+i,slots[i]);for(String key:new String[]{"simple","date","seconds","h24"})j.put(key,source.getBoolean(key,!key.equals("seconds")));j.put("zone",source.getString("zone","Asia/Shanghai"));}catch(Exception ignored){}return j.toString();}
    static void restore(SharedPreferences p,String json){try{JSONObject j=new JSONObject(json);Dashboard d=new Dashboard(p);d.layout=j.getInt("layout");d.color=j.getInt("color");for(int i=0;i<4;i++)d.slots[i]=j.getString("slot"+i);d.save(p);SharedPreferences.Editor e=p.edit();for(String key:new String[]{"simple","date","seconds","h24"})if(j.has(key))e.putBoolean(key,j.getBoolean(key));if(j.has("zone"))e.putString("zone",j.getString("zone"));e.apply();}catch(Exception ignored){}}
}
