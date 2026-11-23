package com.foldstandby.clock;

import android.icu.text.Transliterator;
import java.net.URLEncoder;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapts the provider's mixed Chinese place names to the app's Simplified Chinese UI. */
final class WeatherCities {
    private static final ThreadLocal<Transliterator> SIMPLIFIED =
        ThreadLocal.withInitial(() -> Transliterator.getInstance("Traditional-Simplified"));
    private static final ThreadLocal<Transliterator> TRADITIONAL =
        ThreadLocal.withInitial(() -> Transliterator.getInstance("Simplified-Traditional"));

    private WeatherCities() {}

    static String simplified(String text) {
        return text == null ? "" : SIMPLIFIED.get().transliterate(text);
    }

    static String[] searchQueries(String query) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String name = query.trim();
        if (!name.isEmpty()) {
            names.add(name);
            names.add(simplified(name));
            names.add(TRADITIONAL.get().transliterate(name));
        }
        return names.toArray(new String[0]);
    }

    static JSONArray search(String query) throws Exception {
        Exception failure = null;
        for (String name : searchQueries(query)) {
            try {
                JSONArray cities = DeskData.get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=zh&name="
                    + URLEncoder.encode(name, "UTF-8")).optJSONArray("results");
                if (cities == null || cities.length() == 0) continue;
                for (int i = 0; i < cities.length(); i++) {
                    JSONObject city = cities.getJSONObject(i);
                    for (String key : new String[] { "name", "admin1", "admin2", "country" }) {
                        if (city.has(key)) city.put(key, simplified(city.optString(key)));
                    }
                }
                return cities;
            } catch (Exception e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        return new JSONArray();
    }

    static String label(JSONObject city) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String key : new String[] { "name", "admin1", "country" }) {
            String value = simplified(city.optString(key)).trim();
            if (!value.isEmpty()) parts.add(value);
        }
        return String.join(" · ", parts);
    }
}
