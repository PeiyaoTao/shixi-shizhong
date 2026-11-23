package com.foldstandby.clock;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Address;
import android.os.Bundle;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offscreen weather checks. Never grants permission or requests the device's location. */
final class WeatherChecks {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    static Bundle run(Instrumentation runner) {
        Bundle result = new Bundle();
        try {
            check(WeatherCities.simplified("西雅圖 · 華盛頓州 · 美國").equals("西雅图 · 华盛顿州 · 美国"), "Simplified labels");
            check(Arrays.equals(WeatherCities.searchQueries(" 西雅图 "), new String[] { "西雅图", "西雅圖" }), "Simplified search includes Traditional fallback");
            check(Arrays.equals(WeatherCities.searchQueries("西雅圖"), new String[] { "西雅圖", "西雅图" }), "Traditional search includes Simplified fallback");
            check(WeatherCities.searchQueries("Seattle").length == 1, "English search is not duplicated");
            check(WeatherCities.searchQueries("  ").length == 0, "Empty query does not request the provider");
            JSONObject city = new JSONObject("{\"name\":\"西雅圖\",\"admin1\":\"華盛頓州\",\"country\":\"美國\"}");
            check(WeatherCities.label(city).equals("西雅图 · 华盛顿州 · 美国"), "Full result label uses Simplified Chinese");
            check(WeatherCities.label(new JSONObject("{\"name\":\"上海\",\"admin1\":\"上海\"}")).equals("上海"), "Missing and repeated regions omitted");
            Address address = new Address(Locale.TRADITIONAL_CHINESE);
            address.setLocality("西雅圖"); address.setAdminArea("華盛頓州");
            check(WeatherLocation.cityName(Collections.singletonList(address)).equals("西雅图"), "Located city is Simplified Chinese");
            address.setLocality(null);
            check(WeatherLocation.cityName(Collections.singletonList(address)).equals("华盛顿州"), "Location name falls back to region");
            check(WeatherLocation.cityName(null).equals("当前位置"), "Unavailable geocoder does not block weather");
            Context context = runner.getTargetContext();
            String[] permissions = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            check(Arrays.asList(permissions).contains(Manifest.permission.ACCESS_COARSE_LOCATION), "Approximate permission declared");
            check(!Arrays.asList(permissions).contains(Manifest.permission.ACCESS_FINE_LOCATION), "No precise permission");
            check(!Arrays.asList(permissions).contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION), "No background permission");
            Throwable[] failure = { null };
            runner.runOnMainSync(() -> {
                try {
                    Context denied = new ContextWrapper(context) {
                        public Context getApplicationContext() { return this; }
                        public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
                    };
                    boolean[] called = { false };
                    WeatherLocation.Request request = new WeatherLocation.Request(denied, (fix, name, error) -> {
                        called[0] = true;
                        check(fix == null && error.contains("权限"), "Denied location returns a useful error");
                    });
                    request.start();
                    check(called[0], "Denied permission completes without requesting location");
                    request = new WeatherLocation.Request(denied, (fix, name, error) -> { throw new AssertionError("Cancelled request called back"); });
                    request.cancel(); request.start();
                    SharedPreferences prefs = context.getSharedPreferences("ui_checks", 0);
                    prefs.edit().clear().putString("city", "西雅圖").commit();
                    MainActivity activity = new MainActivity();
                    activity.prepareForCheck(context);
                    activity.draft.pages.get(0).slots[0].type = "weather";
                    activity.selectForCheck(1, 0);
                    File directory = new File(context.getFilesDir(), "qa-0.3.3");
                    directory.mkdirs();
                    for (int width : new int[] { 390, 876 }) {
                        View view = activity.createUi(width, 740);
                        check(!hasText(view, "使用当前位置"), "Global weather provider choices only appear in settings");
                        check(hasText(view, "天气地点 · 西雅图  ›"), "Previously saved Traditional name displays as Simplified");
                        float density = context.getResources().getDisplayMetrics().density;
                        int w = Math.round(width * density), h = Math.round(740 * density);
                        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                        view.layout(0, 0, w, h);
                        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        view.draw(new Canvas(bitmap));
                        try (FileOutputStream stream = new FileOutputStream(new File(directory, "weather-editor-" + width + ".png"))) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        } finally { bitmap.recycle(); }
                    }
                    prefs.edit().clear().commit();
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            result.putString("local", "PASS: Chinese variants and labels, reverse-name fallback, permission scope, denial/cancellation, two offscreen weather editors");
            for (String query : new String[] { "西雅图", "西雅圖", "Seattle" }) {
                JSONArray cities = WeatherCities.search(query);
                check(cities.length() > 0, query + " returns results");
                JSONObject seattle = cities.getJSONObject(0);
                check(seattle.optInt("id") == 5809844, query + " resolves to Seattle, Washington");
                check(seattle.optString("name").equals("西雅图"), query + " displays Simplified Chinese");
                check(Math.abs(seattle.getDouble("latitude") - 47.60621) < .01 && Math.abs(seattle.getDouble("longitude") + 122.33207) < .01, "Seattle coordinates");
            }
            JSONObject weather = DeskData.get(DeskData.weatherUrl("47.60621", "-122.33207"));
            check(weather.getJSONObject("current").has("temperature_2m"), "Seattle live weather");
            result.putString("network", "PASS: 简体西雅图 / 繁体西雅圖 / Seattle all resolve to Seattle and display 西雅图; live weather payload");
            result.putBoolean("passed", true);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
        }
        return result;
    }

    private static boolean hasText(View view, String value) {
        if (view instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), value)) return true;
        }
        return false;
    }
}
