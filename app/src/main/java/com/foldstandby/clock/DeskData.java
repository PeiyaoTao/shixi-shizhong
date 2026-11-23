package com.foldstandby.clock;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import org.json.*;
import java.net.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class DeskData {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static final ExecutorService NETWORK=Executors.newFixedThreadPool(2);
    static volatile String agenda="",todayAgenda="",weatherError="";
    static volatile int weatherFailures;
    static volatile JSONObject weather;
    static volatile long weatherAt, agendaAt, attempted, systemStateAt, musicSessionAt, keyguardAt;
    static volatile boolean weatherBusy, agendaBusy, batteryCharging, batteryPlugged, batteryFull, musicAllowed, keyguardLocked;
    static volatile int batteryLevel = 100, batteryTempTenths = 250;
    static MediaController media;
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Set<Runnable> batteryObservers=new HashSet<>();
    private static SharedPreferences cachedScenePrefs;
    private static String cachedSceneJson;
    private static boolean cachedWantsWeather, cachedWantsAgenda, cachedWantsMusic;
    private static boolean calendarAllowed;
    private static void notifyObservers(){MAIN.post(()->{for(Runnable observer:new ArrayList<>(batteryObservers))observer.run();});}
    private static final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){updateBattery(intent);for(Runnable observer:new ArrayList<>(batteryObservers))observer.run();}};
    static void updateBattery(Intent battery){if(battery==null)return;batteryLevel=Math.max(0,Math.min(100,battery.getIntExtra(BatteryManager.EXTRA_LEVEL,0)*100/Math.max(1,battery.getIntExtra(BatteryManager.EXTRA_SCALE,100))));batteryPlugged=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);batteryFull=status==BatteryManager.BATTERY_STATUS_FULL||batteryLevel==100;batteryCharging=batteryPlugged&&status==BatteryManager.BATTERY_STATUS_CHARGING&&!batteryFull;batteryTempTenths=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,250);}
    static void observeBattery(Context context,Runnable observer){if(batteryObservers.isEmpty())updateBattery(context.getApplicationContext().registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));batteryObservers.add(observer);}
    static void forgetBattery(Context context,Runnable observer){if(batteryObservers.remove(observer)&&batteryObservers.isEmpty())context.getApplicationContext().unregisterReceiver(batteryReceiver);}
    static void updateSystemState(Context context,boolean force){
        Context c=context.getApplicationContext();
        AlarmData.refresh(c,force);
        long now=System.currentTimeMillis();
        if(force||now-systemStateAt>10000L){
            systemStateAt=now;
            if(batteryObservers.isEmpty())updateBattery(c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            boolean musicAccess=hasMusicAccess(c);
            if(musicAccess!=musicAllowed)musicSessionAt=0L;
            musicAllowed=musicAccess;
            if(!musicAllowed)media=null;
        }
        if(force||now-keyguardAt>1500L){
            keyguardAt=now;
            KeyguardManager km=c.getSystemService(KeyguardManager.class);
            keyguardLocked=km!=null&&km.isKeyguardLocked();
        }
    }
    static boolean hasMusicAccess(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(new ComponentName(context, MusicAccess.class));
    }
    static boolean isMusicPlaying(){
        MediaController m=media;
        if(m==null)return false;
        PlaybackState st=m.getPlaybackState();
        return st!=null&&st.getState()==PlaybackState.STATE_PLAYING;
    }
    static JSONObject get(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);
        c.setRequestProperty("Accept","application/json");
        try{int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}
    }
    interface SearchResult{void done(JSONArray cities,String error);}
    static String networkError(Exception error){if(error instanceof SocketTimeoutException)return "连接超时，请稍后重试";if(error instanceof UnknownHostException)return "无法连接天气服务，请检查网络";return "天气服务暂不可用，请稍后重试";}
    static void search(String query,SearchResult cb){NETWORK.execute(()->{JSONArray cities=null;String error="";try{cities=WeatherCities.search(query);}catch(Exception e){error=networkError(e);}JSONArray result=cities;String msg=error;MAIN.post(()->cb.done(result,msg));});}
    static boolean wants(Scene scene,String type){for(Scene.Page page:scene.pages)for(int i=0;i<page.count();i++)if(page.slots[i].type.equals(type))return true;return false;}
    private static boolean wantsAgenda(Scene scene) {
        for (Scene.Page page : scene.pages) {
            for (int i = 0; i < page.count(); i++) {
                Scene.Component slot = page.slots[i];
                if (slot.type.equals("calendar") || (slot.type.equals("date") && slot.bool("showAgenda", true))) return true;
            }
        }
        return false;
    }
    static String weatherUrl(String lat,String lon){double a=Double.parseDouble(lat),b=Double.parseDouble(lon);if(!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a)>90||Math.abs(b)>180)throw new IllegalArgumentException("Invalid location");return "https://api.open-meteo.com/v1/forecast?latitude="+a+"&longitude="+b+"&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=2&timezone=auto";}
    static long retryAfterMs(){return weatherFailures==0?900000L:Math.min(900000L,60000L*(1L<<Math.min(4,weatherFailures-1)));}
    private static final DateTimeFormatter FMT_HM=DateTimeFormatter.ofPattern("HH:mm");
    static String readAgenda(Context c,long start,long end){StringBuilder s=new StringBuilder();try{Uri.Builder b=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(b,start);ContentUris.appendId(b,end);try(Cursor rows=c.getContentResolver().query(b.build(),new String[]{"title","begin","allDay"},"visible=1",null,"begin ASC")){int n=0;while(rows!=null&&rows.moveToNext()&&n++<4){String time=rows.getInt(2)==1?"全天":Instant.ofEpochMilli(rows.getLong(1)).atZone(ZoneId.systemDefault()).format(FMT_HM);s.append(time).append("  ").append(rows.getString(0)==null?"日程":rows.getString(0)).append('\n');}}return s.length()==0?"暂无日程":s.toString().trim();}catch(Exception e){return "日历不可用";}}
    static void refresh(Context context,boolean force){
        Context c=context.getApplicationContext();SharedPreferences p=c.getSharedPreferences("clock",0);long now=System.currentTimeMillis();
        updateSystemState(c,force);
        String rawScene=p.getString(Scene.KEY,"");
        if(force||rawScene.isEmpty()||cachedScenePrefs!=p||!rawScene.equals(cachedSceneJson)){
            Scene scene=Scene.load(p);
            cachedScenePrefs=p;cachedSceneJson=rawScene;
            cachedWantsWeather=wants(scene,"weather");
            boolean includesAgenda = wantsAgenda(scene);
            if (includesAgenda != cachedWantsAgenda) agendaAt = 0L;
            cachedWantsAgenda=includesAgenda;
            cachedWantsMusic=wants(scene,"music");
        }
        boolean wantsWeather=cachedWantsWeather,wantsAgenda=cachedWantsAgenda,wantsMusic=cachedWantsMusic;
        if(weather==null&&p.contains("weatherCache"))try{weather=new JSONObject(p.getString("weatherCache",""));weatherAt=p.getLong("weatherAt",0);}catch(Exception ignored){}
        if((wantsWeather||force)&&p.contains("city")&&!weatherBusy&&(force||now-attempted>retryAfterMs())){
            weatherBusy=true;attempted=now;String city=p.getString("city","");String lat=p.getString("lat","0"),lon=p.getString("lon","0");
            NETWORK.execute(()->{try{JSONObject result=get(weatherUrl(lat,lon));result.getJSONObject("current").getDouble("temperature_2m");result.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0);
                if(lat.equals(p.getString("lat",""))&&lon.equals(p.getString("lon",""))){weather=result;weatherAt=System.currentTimeMillis();weatherError="";weatherFailures=0;p.edit().putString("weatherCache",result.toString()).putLong("weatherAt",weatherAt).apply();notifyObservers();}else attempted=0;
            }catch(Exception e){if(lat.equals(p.getString("lat",""))&&lon.equals(p.getString("lon",""))){weatherError=networkError(e);weatherFailures++;notifyObservers();}else attempted=0;}finally{weatherBusy=false;}});
        }
        boolean calendarAccess=wantsAgenda && c.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;
        if(calendarAccess!=calendarAllowed){calendarAllowed=calendarAccess;agendaAt=0L;if(!calendarAccess){agenda="";todayAgenda="";}}
        if(calendarAccess&&!agendaBusy&&(force||now-agendaAt>60000)){
            agendaBusy=true;agendaAt=now;IO.execute(()->{try{
                String prevAgenda=agenda,prevToday=todayAgenda;
                if(c.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED){ZonedDateTime day=LocalDate.now().atStartOfDay(ZoneId.systemDefault());todayAgenda=readAgenda(c,day.toInstant().toEpochMilli(),day.plusDays(1).toInstant().toEpochMilli());agenda=readAgenda(c,now,now+86400000L);}
                if(!prevAgenda.equals(agenda)||!prevToday.equals(todayAgenda))notifyObservers();
            }finally{agendaBusy=false;}
            });
        }
        if(!wantsMusic||!musicAllowed){media=null;}
        else if(force||now-musicSessionAt>3000L){
            musicSessionAt=now;MediaController candidate=null;
            try{List<MediaController> sessions=c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c,MusicAccess.class));
                for(MediaController m:sessions){if(candidate==null)candidate=m;PlaybackState st=m.getPlaybackState();if(st!=null&&st.getState()==PlaybackState.STATE_PLAYING){candidate=m;break;}}
            }catch(SecurityException ignored){}
            if(candidate==null)media=null;
            else if(media==null||!media.getSessionToken().equals(candidate.getSessionToken()))media=candidate;
        }
    }
    static String condition(int code){if(code==0)return "晴";if(code<=3)return "多云";if(code<=48)return "雾";if(code<=67)return "雨";if(code<=77)return "雪";if(code<=82)return "阵雨";if(code<=86)return "阵雪";return "雷雨";}
    static long remaining(SharedPreferences p){long end=p.getLong("focusEnd",0);return end==0?p.getLong("focusLeft",p.getInt("focusMinutes",25)*60000L):Math.max(0,end-SystemClock.elapsedRealtime());}
    static void toggleFocus(SharedPreferences p){if(p.getLong("focusEnd",0)>0&&remaining(p)>0)p.edit().putLong("focusLeft",remaining(p)).putLong("focusEnd",0).apply();else{long left=remaining(p);if(left==0){resetFocus(p);left=remaining(p);}p.edit().putLong("focusEnd",SystemClock.elapsedRealtime()+left).putLong("focusBoot",System.currentTimeMillis()-SystemClock.elapsedRealtime()).apply();}}
    static void setFocusMinutes(SharedPreferences prefs, int minutes) {
        long oldTotal = focusTotal(prefs);
        long remaining = remaining(prefs);
        boolean ready = prefs.getLong("focusEnd", 0) == 0 && (remaining == oldTotal || remaining == 0);
        SharedPreferences.Editor edit = prefs.edit().putInt("focusMinutes", minutes);
        if (ready) {
            long newTotal = minutes * 60000L;
            edit.putLong("focusLeft", newTotal).putLong("focusTotal", newTotal).remove("focusResetAt");
        } else {
            edit.putLong("focusTotal", oldTotal);
            if (prefs.getLong("focusEnd", 0) == 0) edit.putLong("focusLeft", remaining);
        }
        edit.apply();
    }
    static long focusTotal(SharedPreferences prefs) {
        return Math.max(1, prefs.getLong("focusTotal", prefs.getInt("focusMinutes", 25) * 60000L));
    }
    static void resetFocus(SharedPreferences p){
        long total=Math.max(1,p.getInt("focusMinutes",25))*60000L,oldTotal=Math.max(1,p.getLong("focusTotal",total));
        float from=Math.max(0,Math.min(1,remaining(p)/(float)oldTotal));
        p.edit().putLong("focusEnd",0).putLong("focusLeft",total).putLong("focusTotal",total)
            .putLong("focusResetAt",SystemClock.elapsedRealtime()).putFloat("focusResetFrom",from).apply();
    }
}

