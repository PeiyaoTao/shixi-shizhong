package com.foldstandby.clock;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Address;
import android.os.Bundle;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offscreen weather checks. Never grants permission or requests the device's location. */
final class WeatherChecks {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static long epoch(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    private static void checkWorldDaylight() throws Exception {
        ZoneId london = ZoneId.of("Europe/London");
        LocalDate march29 = LocalDate.of(2026, 3, 29);
        JSONObject daily = new JSONObject()
                .put("time", new JSONArray().put(epoch("2026-03-28T00:00:00Z")).put(epoch("2026-03-29T00:00:00Z")))
                .put("sunrise", new JSONArray().put("invalid").put(epoch("2026-03-29T06:12:00Z")))
                .put("sunset", new JSONArray().put(epoch("2026-03-28T18:23:00Z")).put(epoch("2026-03-29T18:23:00Z")));
        JSONObject forecast = new JSONObject().put("timezone", london.getId())
                .put("utc_offset_seconds", 0).put("daily", daily);
        Map<LocalDate, WorldDaylight.Times> days = WorldDaylight.readDays(forecast, london);
        check(days.size() == 1 && days.containsKey(march29), "Only valid forecast dates are usable");
        WorldDaylight.Times daylight = days.get(march29);
        check(daylight.sunrise.equals(LocalTime.of(7, 12)) && daylight.sunset.equals(LocalTime.of(19, 23)),
                "Sunrise and sunset use the offset on the city's actual date");
        check(!daylight.contains(LocalTime.of(7, 11)) && daylight.contains(daylight.sunrise)
                && !daylight.contains(daylight.sunset), "Daylight starts at sunrise and ends at sunset");
        check(WorldDaylight.forTime(march29.atStartOfDay(london), forecast).sunrise.equals(daylight.sunrise),
                "Local row uses the configured weather location when its time zone matches");
        check(WorldDaylight.forTime(march29.atStartOfDay(ZoneId.of("Asia/Tokyo")), forecast) == WorldDaylight.DEFAULT,
                "Local row ignores weather for a different time zone");
        WorldDaylight.Times fallback = WorldDaylight.forTime(march29.plusDays(1).atStartOfDay(london), forecast);
        check(fallback.sunrise.equals(LocalTime.of(6, 0)) && fallback.sunset.equals(LocalTime.of(18, 0)),
                "Missing forecast date uses the default daylight interval");
        daily.getJSONArray("sunset").put(1, JSONObject.NULL);
        check(WorldDaylight.readDays(forecast, london).isEmpty(), "A missing sunset discards the incomplete day");
    }

    private static Bitmap renderWorldPreview(Context context, SharedPreferences prefs, Scene scene, Instant time) {
        ClockFace face = new ClockFace(context, prefs);
        face.demo = true;
        face.nightPreview = false;
        face.testTime = time.toEpochMilli();
        face.bind(scene, 0);
        face.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        face.layout(0, 0, 640, 480);
        Bitmap image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        face.draw(new Canvas(image));
        return image;
    }

    private static void checkLiveWorldPreview(Instrumentation runner, Context context) throws InterruptedException {
        SharedPreferences prefs = context.getSharedPreferences("ui_checks", 0);
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        Instant now = Instant.now();
        Scene scene = new Scene();
        Scene.Page page = new Scene.Page();
        scene.pages.add(page);
        ComponentStyles.select(page.slots[0], "world", 3);
        Scene.put(page.slots[0].options, "zone", tokyo.getId());

        CountDownLatch loaded = new CountDownLatch(1);
        Bitmap[] before = { null };
        runner.runOnMainSync(() -> {
            before[0] = renderWorldPreview(context, prefs, scene, now);
            DeskData.refreshWorldDaylightPreview(context, page, prefs, loaded::countDown);
        });
        try {
            check(loaded.await(25, TimeUnit.SECONDS), "The world style preview requests a daylight forecast");
            boolean[] renderedDaylight = { false };
            runner.runOnMainSync(() -> {
                Bitmap after = renderWorldPreview(context, prefs, scene, now);
                renderedDaylight[0] = WorldDaylight.forTime(now.atZone(tokyo), null) != WorldDaylight.DEFAULT
                    && !before[0].sameAs(after);
                after.recycle();
            });
            check(renderedDaylight[0], "The world style preview updates its track from the live forecast");
        } finally {
            before[0].recycle();
        }
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
                    checkWorldDaylight();
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
                    checkWeatherArtwork(context, prefs);
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            result.putString("local", "PASS: Weather labels, location and editor checks; daylight dates, DST, boundaries, and fallback");
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
            ZoneId seattle = ZoneId.of("America/Los_Angeles");
            check(WorldDaylight.readDays(weather, seattle).containsKey(LocalDate.now(seattle)), "Seattle daylight matches the local forecast date");
            checkLiveWorldPreview(runner, context);
            result.putString("network", "PASS: Seattle search and live weather; Tokyo's style preview updates from its live daylight forecast");
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

    private static void checkWeatherArtwork(Context context, SharedPreferences prefs) throws Exception {
        int[] mappedCodes = { -1, 0, 1, 2, 3, 45, 48, 51, 55, 56, 61, 63, 65, 66, 71, 73, 75, 77, 80, 82, 85, 86, 95, 96, 99 };
        WeatherArtwork.Kind[] expectedKinds = {
            WeatherArtwork.Kind.UNKNOWN, WeatherArtwork.Kind.CLEAR, WeatherArtwork.Kind.MAINLY_CLEAR,
            WeatherArtwork.Kind.PARTLY_CLOUDY, WeatherArtwork.Kind.OVERCAST, WeatherArtwork.Kind.FOG,
            WeatherArtwork.Kind.FOG, WeatherArtwork.Kind.DRIZZLE, WeatherArtwork.Kind.DRIZZLE,
            WeatherArtwork.Kind.FREEZING_RAIN, WeatherArtwork.Kind.RAIN, WeatherArtwork.Kind.RAIN,
            WeatherArtwork.Kind.HEAVY_RAIN, WeatherArtwork.Kind.FREEZING_RAIN, WeatherArtwork.Kind.SNOW,
            WeatherArtwork.Kind.SNOW, WeatherArtwork.Kind.SNOW, WeatherArtwork.Kind.SNOW,
            WeatherArtwork.Kind.RAIN_SHOWERS, WeatherArtwork.Kind.RAIN_SHOWERS, WeatherArtwork.Kind.SNOW_SHOWERS,
            WeatherArtwork.Kind.SNOW_SHOWERS, WeatherArtwork.Kind.THUNDERSTORM, WeatherArtwork.Kind.THUNDERSTORM_HAIL,
            WeatherArtwork.Kind.THUNDERSTORM_HAIL
        };
        String[] expectedLabels = {
            "天气未知", "晴", "少云", "多云", "阴", "雾", "雾", "毛毛雨", "毛毛雨",
            "冻雨", "小雨", "中雨", "大雨", "冻雨", "小雪", "中雪", "大雪", "雪",
            "阵雨", "阵雨", "阵雪", "阵雪", "雷雨", "雷雹", "雷雹"
        };
        for (int i = 0; i < mappedCodes.length; i++) {
            int code = mappedCodes[i];
            check(WeatherArtwork.kindOf(code) == expectedKinds[i], "WeatherArtwork kind mapping for WMO " + code);
            check(DeskData.condition(code).equals(expectedLabels[i]), "DeskData condition label for WMO " + code);
        }

        int[] sampleCodes = { -1, 0, 1, 2, 3, 45, 51, 56, 63, 65, 73, 80, 85, 95, 96 };
        WeatherArtwork artwork = new WeatherArtwork();
        Bitmap[] solidIcons = new Bitmap[sampleCodes.length];
        Bitmap[] cardRenders = new Bitmap[sampleCodes.length];

        JSONObject savedWeather = DeskData.weather;
        long savedWeatherAt = DeskData.weatherAt;
        String savedWeatherError = DeskData.weatherError;
        boolean savedKeyguard = DeskData.keyguardLocked;
        String savedPrefs = prefs.getAll().toString();

        File qaDir = new File(context.getFilesDir(), "qa-weather");
        qaDir.mkdirs();

        try {
            for (int i = 0; i < sampleCodes.length; i++) {
                int code = sampleCodes[i];
                Bitmap solid = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
                Canvas sc = new Canvas(solid);
                sc.drawColor(Color.BLACK);
                artwork.palette(0xffeee9db, 0xff98958c, 0xff18191b, 0);
                artwork.draw(sc, 100f, 100f, 1f, code);
                solidIcons[i] = solid;

                int lit = 0;
                for (int row = 0; row < 200; row++) {
                    for (int col = 0; col < 200; col++) {
                        int px = solid.getPixel(col, row);
                        if (px != Color.BLACK) {
                            lit++;
                            check(col >= 12 && col < 188 && row >= 12 && row < 188,
                                    "Icon stays inside 12px border margin for code " + code);
                            check(col >= 28 && col < 172 && row >= 30 && row < 170,
                                    "Icon stays inside 144x140 safe artboard for code " + code);
                        }
                    }
                }
                check(lit > 80, "Icon draws non-empty pixels for code " + code);

                Bitmap grad = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
                Canvas gc = new Canvas(grad);
                gc.drawColor(Color.BLACK);
                artwork.palette(0xffeee9db, 0xff98958c, 0xff18191b, 1);
                artwork.draw(gc, 100f, 100f, 1f, code);
                check(!solid.sameAs(grad), "Gradient mode alters icon pixels for code " + code);
                grad.recycle();
            }
            for (int a = 0; a < solidIcons.length; a++) {
                for (int b = a + 1; b < solidIcons.length; b++) {
                    check(!solidIcons[a].sameAs(solidIcons[b]),
                            "Distinct icon bitmaps for codes " + sampleCodes[a] + " vs " + sampleCodes[b]);
                }
            }

            DeskData.keyguardLocked = false;
            DeskData.weatherError = "";
            DeskData.weatherAt = System.currentTimeMillis();
            prefs.edit().clear().putString("city", "西雅图").putBoolean("night", false).putBoolean("motion", false).commit();
            long testTime = ZonedDateTime.of(2026, 11, 14, 10, 28, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli();

            for (int i = 0; i < sampleCodes.length; i++) {
                int code = sampleCodes[i];
                DeskData.weather = new JSONObject()
                        .put("current", new JSONObject()
                                .put("temperature_2m", 18.0)
                                .put("apparent_temperature", 17.0)
                                .put("relative_humidity_2m", 64)
                                .put("weather_code", code))
                        .put("daily", new JSONObject()
                                .put("temperature_2m_max", new JSONArray().put(21.0).put(20.0))
                                .put("temperature_2m_min", new JSONArray().put(12.0).put(11.0)));

                Scene scene = new Scene();
                Scene.Page page = scene.pages.get(0);
                page.layout = 0;
                ComponentStyles.select(page.slots[0], "weather", 1);

                ClockFace face = new ClockFace(context, prefs);
                face.demo = false;
                face.nightPreview = false;
                face.testTime = testTime;
                face.bind(scene, 0);

                Bitmap daySolid = renderFace(face, 540, 342);
                check(daySolid.getPixel(0, 0) == Color.BLACK, "Daytime solid weather card has black background for code " + code);

                Scene.put(page.slots[0].theme, "timeGradient", 1);
                Bitmap dayGradient = renderFace(face, 540, 342);
                check(dayGradient.getPixel(0, 0) == Color.BLACK, "Daytime gradient weather card has black background for code " + code);
                check(!daySolid.sameAs(dayGradient), "Daytime gradient changes weather card for code " + code);
                cardRenders[i] = dayGradient;
                daySolid.recycle();

                face.nightPreview = true;
                Bitmap nightGradient = renderFace(face, 540, 342);
                Scene.put(page.slots[0].theme, "timeGradient", 0);
                Bitmap nightSolid = renderFace(face, 540, 342);
                check(nightSolid.getPixel(0, 0) == Color.BLACK, "Night weather card has black background for code " + code);
                check(nightSolid.sameAs(nightGradient), "Night palette remains solid for weather code " + code);
                nightGradient.recycle();
                nightSolid.recycle();
            }

            for (int a = 0; a < cardRenders.length; a++) {
                for (int b = a + 1; b < cardRenders.length; b++) {
                    check(!cardRenders[a].sameAs(cardRenders[b]),
                            "Distinct ClockFace weather cards for codes " + sampleCodes[a] + " vs " + sampleCodes[b]);
                }
            }

            Bitmap zoomSheet = Bitmap.createBitmap(5 * 240, 3 * 260, Bitmap.Config.ARGB_8888);
            Canvas zc = new Canvas(zoomSheet);
            zc.drawColor(Color.BLACK);
            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(0xffdbe5ef);
            labelPaint.setTextSize(20f);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(1.5f);
            boxPaint.setColor(0xff20262e);
            for (int i = 0; i < sampleCodes.length; i++) {
                int col = i % 5, row = i / 5;
                int tx = col * 240, ty = row * 260;
                zc.drawRect(tx + 4, ty + 4, tx + 236, ty + 256, boxPaint);
                artwork.palette(0xffeee9db, 0xff98958c, 0xff18191b, 1);
                artwork.draw(zc, tx + 120f, ty + 110f, 1.25f, sampleCodes[i]);
                String tag = (sampleCodes[i] < 0 ? "N/A" : String.valueOf(sampleCodes[i]))
                        + " · " + DeskData.condition(sampleCodes[i]);
                zc.drawText(tag, tx + 120f, ty + 234f, labelPaint);
            }
            try (FileOutputStream out = new FileOutputStream(new File(qaDir, "weather-icons-zoom.png"))) {
                zoomSheet.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                zoomSheet.recycle();
            }

            Bitmap cardsSheet = Bitmap.createBitmap(3 * 540, 5 * 378, Bitmap.Config.ARGB_8888);
            Canvas cc = new Canvas(cardsSheet);
            cc.drawColor(0xff0b0f14);
            Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            barPaint.setColor(0xff141c24);
            labelPaint.setTextSize(22f);
            for (int i = 0; i < sampleCodes.length; i++) {
                int col = i % 3, row = i / 3;
                int tx = col * 540, ty = row * 378;
                cc.drawBitmap(cardRenders[i], tx, ty, null);
                cc.drawRect(tx, ty + 342, tx + 540, ty + 378, barPaint);
                String tag = "WMO " + sampleCodes[i] + " · " + DeskData.condition(sampleCodes[i])
                        + " (" + WeatherArtwork.kindOf(sampleCodes[i]).name() + ")";
                cc.drawText(tag, tx + 270f, ty + 368f, labelPaint);
            }
            try (FileOutputStream out = new FileOutputStream(new File(qaDir, "weather-icons-cards.png"))) {
                cardsSheet.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                cardsSheet.recycle();
            }
        } finally {
            for (Bitmap b : solidIcons) if (b != null) b.recycle();
            for (Bitmap b : cardRenders) if (b != null) b.recycle();
            DeskData.weather = savedWeather;
            DeskData.weatherAt = savedWeatherAt;
            DeskData.weatherError = savedWeatherError;
            DeskData.keyguardLocked = savedKeyguard;
            if (savedPrefs != null) prefs.edit().clear().commit();
        }
    }

    private static Bitmap renderFace(ClockFace face, int width, int height) {
        face.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        face.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        face.draw(new Canvas(bitmap));
        return bitmap;
    }
}
