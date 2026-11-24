package com.foldstandby.clock;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** A saved scene owns pages; page identity is independent of display order. */
final class Scene {
    static final String KEY="scene_v3";
    static final String[] STYLES={"digital","flip","analog","industrial","led","dot"};
    static final String[] STYLE_NAMES={"极简数字","机械翻页","经典指针","工业指针","七段数码","点阵"};
    static final String[] TYPES={"clock","date","calendar","weather","music","focus","battery","world","todo","alarm"};
    static final String[] TYPE_NAMES={"时钟","日历","日程","天气","音乐","专注","电量","世界时钟","待办","闹钟"};
    static final String[] ROLES={"background","time","secondary","accent","hour","minute","second","major","minor","numerals","plate"};
    static final String[] ROLE_NAMES={"背景","时间文字","次级信息","强调色","时针","分针","秒针","小时刻度","分钟刻度","表盘数字","结构线"};
    static final int[] DEFAULT_COLORS={0xff000000,0xffe8ebf0,0xff8f9aa9,0xffb4dff7,0xffb4dff7,0xffe8ebf0,0xffffb16d,0xffb4dff7,0xff586576,0xffdbe2eb,0xff181e27};
    String name="我的书桌",home;
    JSONObject theme=new JSONObject(),nightTheme=new JSONObject();
    Boolean ownNightColors;
    final ArrayList<Page> pages=new ArrayList<>();
    static final class Component {
        String id=UUID.randomUUID().toString(),type="clock",style="digital";
        JSONObject theme=new JSONObject(),nightTheme=new JSONObject(),options=new JSONObject(),styles=new JSONObject();
        Component(){ensureStyles();}
        private void ensureStyles(){for(String s:STYLES)if(styles.optJSONObject(s)==null)put(styles,s,new JSONObject());}
        void setType(String value){
            value=valid(value,TYPES,"clock");
            if(type.equals(value)){style=ComponentStyles.selected(this);return;}
            put(options,"style:"+type,ComponentStyles.selected(this));
            type=value;String[] available=ComponentStyles.ids(type);
            style=valid(options.optString("style:"+type),available,available[0]);
        }
        JSONObject styleOptions(){JSONObject j=styles.optJSONObject(style);if(j==null){j=new JSONObject();put(styles,style,j);}return j;}
        boolean bool(String key,boolean fallback){return options.optBoolean(key,fallback);}
        JSONObject json(){JSONObject j=new JSONObject();put(j,"id",id);put(j,"type",type);put(j,"style",ComponentStyles.selected(this));put(j,"theme",theme);put(j,"nightTheme",nightTheme);put(j,"options",options);put(j,"styles",styles);return j;}
        static Component from(JSONObject j){Component c=new Component();if(j==null)return c;c.id=j.optString("id",c.id);c.type=valid(j.optString("type"),TYPES,"clock");c.theme=object(j,"theme");c.nightTheme=object(j,"nightTheme");c.options=object(j,"options");c.styles=object(j,"styles");c.style=ComponentStyles.restore(c,j.optString("style"));c.ensureStyles();return c;}
    }
    static final class Page {
        String id=UUID.randomUUID().toString(),name="时间";int layout;boolean autoName=true;
        JSONObject theme=new JSONObject(),nightTheme=new JSONObject();Component[] slots=new Component[4];
        Page(){for(int i=0;i<4;i++)slots[i]=new Component();}
        int count(){return layout==0?1:layout==3?4:2;}
        String contentName(){LinkedHashSet<String> names=new LinkedHashSet<>();for(int i=0;i<count();i++)names.add(slots[i].type.equals("clock")?"时间":typeName(slots[i].type));return String.join(" · ",names);}
        void refreshName(){if(autoName)name=contentName();}
        JSONObject json(){JSONObject j=new JSONObject();put(j,"id",id);put(j,"name",name);put(j,"autoName",autoName);put(j,"layout",layout);put(j,"theme",theme);put(j,"nightTheme",nightTheme);JSONArray a=new JSONArray();for(Component c:slots)a.put(c.json());put(j,"slots",a);return j;}
        static Page from(JSONObject j){Page p=new Page();if(j==null)return p;p.id=j.optString("id",p.id);p.name=j.optString("name","页面");p.layout=Math.max(0,Math.min(3,j.optInt("layout")));p.theme=object(j,"theme");p.nightTheme=object(j,"nightTheme");JSONArray a=j.optJSONArray("slots");for(int i=0;a!=null&&i<Math.min(4,a.length());i++)p.slots[i]=Component.from(a.optJSONObject(i));p.autoName=j.optBoolean("autoName",p.name.equals(p.contentName())||p.name.equals("月历")||p.name.matches("页面( \\d+)?"));return p;}
        Page duplicate(){Page p=from(json());p.id=UUID.randomUUID().toString();if(!autoName)p.name=name+" 副本";for(Component c:p.slots)c.id=UUID.randomUUID().toString();return p;}
    }
    static void put(JSONObject j,String key,Object value){try{j.put(key,value);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    static JSONObject object(JSONObject j,String key){JSONObject o=j.optJSONObject(key);if(o==null)return new JSONObject();try{JSONObject copy=new JSONObject(o.toString());if(key.equals("theme")||key.equals("nightTheme")){copy.remove("separator");ColorThemes.migrateLightPreset(copy);}return copy;}catch(JSONException e){return new JSONObject();}}
    static String valid(String value,String[] values,String fallback){for(String s:values)if(s.equals(value))return s;return fallback;}
    static String styleName(String id){for(int i=0;i<STYLES.length;i++)if(STYLES[i].equals(id))return STYLE_NAMES[i];return STYLE_NAMES[0];}
    static String typeName(String id){for(int i=0;i<TYPES.length;i++)if(TYPES[i].equals(id))return TYPE_NAMES[i];return TYPE_NAMES[0];}
    int homeIndex(){for(int i=0;i<pages.size();i++)if(pages.get(i).id.equals(home))return i;return 0;}
    int index(String id){for(int i=0;i<pages.size();i++)if(pages.get(i).id.equals(id))return i;return homeIndex();}
    void remove(Page p){if(pages.size()<2)return;pages.remove(p);if(p.id.equals(home))home=pages.get(0).id;}
    void movePage(String id,int target){int from=index(id);target=Math.max(0,Math.min(pages.size()-1,target));if(from!=target){Page p=pages.remove(from);pages.add(target,p);}}
    int gradient(Page page,Component component){int value=theme.optInt("timeGradient",0);value=page.theme.optInt("timeGradient",value);if(component!=null)value=component.theme.optInt("timeGradient",value);return Math.max(0,Math.min(TextGradients.NAMES.length-1,value));}
    int color(Page page,Component component,String role,boolean night,boolean ownNight){
        if(role.equals("background"))return 0xff000000;
        int n=0;for(int i=0;i<ROLES.length;i++)if(ROLES[i].equals(role)){n=i;break;}
        int value=theme.optInt(role,DEFAULT_COLORS[n]);
        boolean independent=night&&(ownNightColors==null?ownNight:ownNightColors);
        if(independent)value=nightTheme.optInt(role,value);
        value=(independent?page.nightTheme:page.theme).optInt(role,value);
        if(component!=null)value=(independent?component.nightTheme:component.theme).optInt(role,value);
        return night&&role.equals("background")?(ownNight?nightTheme.optInt(role,0xff000000):0xff000000):value;
    }
    String json(){JSONObject j=new JSONObject();put(j,"version",4);put(j,"name",name);put(j,"home",home);put(j,"theme",theme);put(j,"nightTheme",nightTheme);if(ownNightColors!=null)put(j,"ownNightColors",ownNightColors);JSONArray a=new JSONArray();for(Page p:pages)a.put(p.json());put(j,"pages",a);return j.toString();}
    Scene copy(){return parse(json());}
    void save(SharedPreferences p){p.edit().putString(KEY,json()).apply();}
    static Scene parse(String text){try{JSONObject j=new JSONObject(text);int version=j.optInt("version");if(version!=3&&version!=4)return null;Scene s=new Scene();s.name=j.optString("name","我的书桌");s.theme=object(j,"theme");s.nightTheme=object(j,"nightTheme");if(j.has("ownNightColors"))s.ownNightColors=j.optBoolean("ownNightColors");JSONArray a=j.getJSONArray("pages");for(int i=0;i<a.length();i++){Page page=Page.from(a.getJSONObject(i));if(version==3){page.nightTheme=object(page.json(),"theme");for(Component c:page.slots)c.nightTheme=object(c.json(),"theme");}s.pages.add(page);}if(s.pages.isEmpty())return null;s.home=j.optString("home",s.pages.get(0).id);s.home=s.pages.get(s.homeIndex()).id;return s;}catch(Exception e){return null;}}
    static Scene load(SharedPreferences p){Scene existing=parse(p.getString(KEY,""));if(existing!=null){if(existing.ownNightColors==null)existing.ownNightColors=p.getBoolean("nightOwnColors",false);return existing;}
        Scene s=new Scene();Dashboard d=new Dashboard(p);Page first=new Page();first.name="时间";first.layout=d.layout;
        put(s.theme,"accent",d.color);put(s.theme,"time",d.color);put(s.theme,"hour",d.color);put(s.theme,"major",d.color);
        for(int i=0;i<4;i++){Component c=first.slots[i];String old=d.slots[i];if(old.equals("digital")||old.equals("split")||old.equals("analog")){c.type="clock";c.style=old.equals("split")?"flip":old;}else c.type=valid(old,TYPES,"clock");
            for(String key:new String[]{"date","seconds","h24"})put(c.options,key,p.getBoolean(key,!key.equals("seconds")));}
        s.pages.add(first);Page calendar=new Page();calendar.name="日历";calendar.slots[0].type="date";s.pages.add(calendar);
        Page weather=new Page();weather.name="天气";weather.slots[0].type="weather";s.pages.add(weather);s.home=first.id;s.ownNightColors=p.getBoolean("nightOwnColors",false);return s;
    }
}
