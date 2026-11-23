package com.foldstandby.clock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One foreground location fix, with cancellation and a bounded wait. Never tracks in the background. */
final class WeatherLocation {
    private static final ExecutorService GEOCODING = Executors.newSingleThreadExecutor();
    interface Result { void done(Location location, String name, String error); }

    private WeatherLocation() {}

    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static String cityName(List<Address> addresses) {
        if (addresses != null) for (Address address : addresses) {
            for (String name : new String[] { address.getLocality(), address.getSubAdminArea(), address.getAdminArea() }) {
                if (name != null && !name.trim().isEmpty()) return WeatherCities.simplified(name);
            }
        }
        return "当前位置";
    }

    static final class Request {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ArrayList<CancellationSignal> signals = new ArrayList<>();
        private Result callback;
        private int pending;
        private Location fix;
        private final Runnable timeout = () -> finish(null, "", "定位超时，请检查网络和系统定位后重试，或搜索城市。");
        private final Runnable nameTimeout = () -> finish(fix, "当前位置", "");

        Request(Context context, Result callback) {
            this.context = context.getApplicationContext();
            this.callback = callback;
        }

        void start() {
            if (callback == null) return;
            if (!allowed(context)) {
                finish(null, "", "未获得定位权限，可搜索城市或在应用权限中允许位置信息。");
                return;
            }
            LocationManager manager = context.getSystemService(LocationManager.class);
            try {
                if (manager == null || !manager.isLocationEnabled()) {
                    finish(null, "", "系统定位未开启，请开启位置信息后重试，或搜索城市。");
                    return;
                }
                ArrayList<String> providers = new ArrayList<>();
                for (String provider : new String[] { "fused", LocationManager.NETWORK_PROVIDER }) {
                    if (manager.getAllProviders().contains(provider) && manager.isProviderEnabled(provider)) providers.add(provider);
                }
                if (providers.isEmpty()) {
                    finish(null, "", "当前设备没有可用的近似定位服务，请搜索城市或使用经纬度。");
                    return;
                }
                pending = providers.size();
                main.postDelayed(timeout, 20000);
                for (String provider : providers) {
                    CancellationSignal signal = new CancellationSignal();
                    signals.add(signal);
                    try {
                        manager.getCurrentLocation(provider, signal, context.getMainExecutor(), this::located);
                    } catch (IllegalArgumentException e) {
                        located(null);
                    }
                }
            } catch (SecurityException e) {
                finish(null, "", "定位权限已关闭，请重新授权或搜索城市。");
            }
        }

        private void located(Location location) {
            if (callback == null || fix != null) return;
            if (location == null) {
                if (--pending == 0) finish(null, "", "暂时无法获取当前位置，请稍后重试或搜索城市。");
                return;
            }
            fix = location;
            main.removeCallbacks(timeout);
            stopProviders();
            main.postDelayed(nameTimeout, 5000);
            GEOCODING.execute(() -> {
                try {
                    if (!Geocoder.isPresent()) {
                        main.post(nameTimeout);
                        return;
                    }
                    Geocoder geocoder = new Geocoder(context, Locale.SIMPLIFIED_CHINESE);
                    if (Build.VERSION.SDK_INT >= 33) {
                        geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1, new Geocoder.GeocodeListener() {
                            public void onGeocode(List<Address> addresses) { named(addresses); }
                            public void onError(String error) { main.post(nameTimeout); }
                        });
                    } else {
                        named(geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1));
                    }
                } catch (Exception e) {
                    main.post(nameTimeout);
                }
            });
        }

        private void named(List<Address> addresses) {
            String name = cityName(addresses);
            main.post(() -> finish(fix, name, ""));
        }

        private void finish(Location location, String name, String error) {
            if (callback == null) return;
            Result result = callback;
            cancel();
            result.done(location, name, error);
        }

        private void stopProviders() {
            for (CancellationSignal signal : signals) signal.cancel();
            signals.clear();
        }

        void cancel() {
            callback = null;
            main.removeCallbacksAndMessages(null);
            stopProviders();
        }
    }
}
