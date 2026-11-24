package com.foldstandby.clock;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.util.TimeZone;
import android.net.Uri;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class WorldDaylight {
    static final Times DEFAULT = new Times(LocalTime.of(6, 0), LocalTime.of(18, 0));
    static final String DAILY_FIELDS = "sunrise,sunset";
    private static final long RETRY_MS = 15 * 60_000L;

    // Forecast state is read and published on the main thread.
    private static final Map<ZoneId, ForecastRequest> forecasts = new HashMap<>();
    private static JSONObject lastWeather;
    private static ZoneId weatherZone;
    private static Map<LocalDate, Times> weatherDays = Collections.emptyMap();
    private static Map<String, Coordinates> zoneCoordinates;

    private WorldDaylight() {}

    static final class Times {
        final LocalTime sunrise;
        final LocalTime sunset;

        Times(LocalTime sunrise, LocalTime sunset) {
            this.sunrise = sunrise;
            this.sunset = sunset;
        }

        boolean contains(LocalTime time) {
            return !time.isBefore(sunrise) && time.isBefore(sunset);
        }
    }

    private static final class ForecastRequest {
        Map<LocalDate, Times> days = Collections.emptyMap();
        LocalDate attemptedDate;
        long attemptedAt;
        boolean busy;
        boolean noCoordinates;
    }

    private static final class Coordinates {
        final double latitude;
        final double longitude;

        Coordinates(String pair) {
            int separator = Math.max(pair.lastIndexOf('+'), pair.lastIndexOf('-'));
            latitude = decimalDegrees(pair.substring(0, separator), 2);
            longitude = decimalDegrees(pair.substring(separator), 3);
        }

        private static double decimalDegrees(String value, int degreeDigits) {
            int degreeEnd = 1 + degreeDigits;
            int minuteEnd = degreeEnd + 2;
            double degrees = Integer.parseInt(value.substring(1, degreeEnd));
            degrees += Integer.parseInt(value.substring(degreeEnd, minuteEnd)) / 60.0;
            if (value.length() > minuteEnd) degrees += Integer.parseInt(value.substring(minuteEnd)) / 3600.0;
            return value.charAt(0) == '-' ? -degrees : degrees;
        }
    }

    static ZoneId zone(Scene.Component component, SharedPreferences prefs) {
        try {
            return ZoneId.of(component.options.optString("zone", prefs.getString("zone", "Asia/Shanghai")));
        } catch (DateTimeException invalidZone) {
            return ZoneId.systemDefault();
        }
    }

    static Map<LocalDate, Times> readDays(JSONObject forecast, ZoneId zone) {
        JSONObject daily = forecast.optJSONObject("daily");
        if (daily == null) return Collections.emptyMap();
        JSONArray dates = daily.optJSONArray("time");
        JSONArray sunrises = daily.optJSONArray("sunrise");
        JSONArray sunsets = daily.optJSONArray("sunset");
        if (dates == null || sunrises == null || sunsets == null) return Collections.emptyMap();

        // Open-Meteo uses a fixed offset for the daily boundaries, including across DST changes.
        int offsetSeconds = forecast.optInt("utc_offset_seconds", Integer.MAX_VALUE);
        if (Math.abs((long) offsetSeconds) > 18 * 3600) return Collections.emptyMap();
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);
        Map<LocalDate, Times> days = new HashMap<>();
        for (int i = 0; i < dates.length(); i++) {
            if (dates.isNull(i) || sunrises.isNull(i) || sunsets.isNull(i)) continue;
            try {
                LocalDate date = Instant.ofEpochSecond(dates.getLong(i)).atOffset(offset).toLocalDate();
                ZonedDateTime sunrise = Instant.ofEpochSecond(sunrises.getLong(i)).atZone(zone);
                ZonedDateTime sunset = Instant.ofEpochSecond(sunsets.getLong(i)).atZone(zone);
                if (date.equals(sunrise.toLocalDate()) && date.equals(sunset.toLocalDate())
                        && sunrise.isBefore(sunset) && sunrise.toLocalTime().isBefore(sunset.toLocalTime())) {
                    days.put(date, new Times(sunrise.toLocalTime(), sunset.toLocalTime()));
                }
            } catch (JSONException | DateTimeException invalidDay) {
                // Another day in the response can still provide daylight hours.
            }
        }
        return days;
    }

    private static String canonicalId(ZoneId zone) {
        String canonical = TimeZone.getCanonicalID(zone.getId());
        return canonical == null ? zone.getId() : canonical;
    }

    private static Times weatherTimes(ZonedDateTime time, JSONObject weather) {
        if (lastWeather != weather) {
            lastWeather = weather;
            weatherZone = null;
            weatherDays = Collections.emptyMap();
            if (weather != null) {
                try {
                    weatherZone = ZoneId.of(weather.optString("timezone"));
                    weatherDays = readDays(weather, weatherZone);
                } catch (DateTimeException invalidZone) {
                    return null;
                }
            }
        }
        if (weatherZone == null || (!weatherZone.equals(time.getZone())
                && !canonicalId(weatherZone).equals(canonicalId(time.getZone())))) return null;
        return weatherDays.get(time.toLocalDate());
    }

    static Times forTime(ZonedDateTime time, JSONObject localWeather) {
        if (localWeather != null) {
            Times local = weatherTimes(time, localWeather);
            if (local != null) return local;
        }
        ForecastRequest forecast = forecasts.get(time.getZone());
        return forecast == null ? DEFAULT : forecast.days.getOrDefault(time.toLocalDate(), DEFAULT);
    }

    static void refresh(Context context, Set<ZoneId> zones, boolean includesLocal, JSONObject weather, Runnable onUpdated) {
        if (zones.isEmpty() && !includesLocal) return;
        Instant now = Instant.now();
        for (ZoneId zone : zones) request(context, now.atZone(zone), onUpdated);
        if (includesLocal) {
            ZonedDateTime local = now.atZone(ZoneId.systemDefault());
            if (weatherTimes(local, weather) == null) request(context, local, onUpdated);
        }
    }

    private static void request(Context context, ZonedDateTime time, Runnable onUpdated) {
        ZoneId zone = time.getZone();
        LocalDate date = time.toLocalDate();
        ForecastRequest request = forecasts.computeIfAbsent(zone, ignored -> new ForecastRequest());
        long elapsed = SystemClock.elapsedRealtime();
        if (request.busy || request.noCoordinates || request.days.containsKey(date)
                || (date.equals(request.attemptedDate) && elapsed - request.attemptedAt < RETRY_MS)) return;

        request.busy = true;
        request.attemptedDate = date;
        request.attemptedAt = elapsed;
        DeskData.NETWORK.execute(() -> {
            Map<LocalDate, Times> days = Collections.emptyMap();
            boolean noCoordinates = false;
            try {
                Coordinates coordinates = coordinatesFor(context, zone);
                noCoordinates = coordinates == null;
                if (coordinates != null) {
                    String url = DeskData.FORECAST_URL + "latitude=" + coordinates.latitude
                            + "&longitude=" + coordinates.longitude + "&daily=" + DAILY_FIELDS
                            + "&forecast_days=2&timeformat=unixtime&timezone=" + Uri.encode(zone.getId());
                    days = readDays(DeskData.get(url), zone);
                }
            } catch (Exception unavailable) {
                // The clock uses its default hours until a forecast is available.
            }
            Map<LocalDate, Times> result = days;
            boolean unsupportedZone = noCoordinates;
            DeskData.MAIN.post(() -> {
                if (!result.isEmpty()) request.days = result;
                request.noCoordinates = unsupportedZone;
                request.busy = false;
                if (!result.isEmpty()) onUpdated.run();
            });
        });
    }

    private static synchronized Coordinates coordinatesFor(Context context, ZoneId zone) throws IOException {
        if (zoneCoordinates == null) {
            Map<String, Coordinates> locations = new HashMap<>();
            Map<String, Coordinates> aliases = new HashMap<>();
            // IANA zone.tab lists representative coordinates, including cities sharing time rules.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getResources().openRawResource(R.raw.world_clock_zones), StandardCharsets.US_ASCII))) {
                String row;
                while ((row = reader.readLine()) != null) {
                    if (row.isEmpty() || row.startsWith("#")) continue;
                    String[] columns = row.split("\t", 4);
                    String id = columns[2];
                    Coordinates coordinates = new Coordinates(columns[1]);
                    locations.put(id, coordinates);
                    String canonical = TimeZone.getCanonicalID(id);
                    if (canonical != null) aliases.putIfAbsent(canonical, coordinates);
                }
            }
            aliases.forEach(locations::putIfAbsent);
            zoneCoordinates = locations;
        }
        Coordinates coordinates = zoneCoordinates.get(zone.getId());
        return coordinates != null ? coordinates : zoneCoordinates.get(canonicalId(zone));
    }
}
