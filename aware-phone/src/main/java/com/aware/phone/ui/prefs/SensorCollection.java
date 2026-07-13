package com.aware.phone.ui.prefs;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.aware.Aware;
import com.aware.Aware_Preferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Determines, per sensor, whether AWARE is actually collecting data on this device, and — when it
 * isn't — the most likely reason. "Collecting" is grounded in the sensor provider having a recent
 * row (the ground truth); if there is none, the reason is derived from preconditions (sensor
 * hardware present, runtime permission granted, accessibility enabled, or simply no data yet).
 *
 * Used by the study sensor list to color each sensor blue (collecting) vs grey (not), and to show a
 * details dialog on tap.
 */
public final class SensorCollection {

    private SensorCollection() {}

    /** A sensor counts as "collecting" if its provider has a row within this window. */
    private static final long RECENT_WINDOW_MS = 30 * 60 * 1000L; // 30 minutes

    /** Result of a collection check for one sensor. */
    public static final class Status {
        public final boolean collecting;
        public final long lastDataMs;  // 0 = never any data
        public final String reason;    // short human-readable status
        public final String fixHint;   // actionable hint, or null

        Status(boolean collecting, long lastDataMs, String reason, String fixHint) {
            this.collecting = collecting;
            this.lastDataMs = lastDataMs;
            this.reason = reason;
            this.fixHint = fixHint;
        }
    }

    /** Registry entry describing where a sensor's data lives and what it needs to collect. */
    private static final class Def {
        final String authoritySuffix; // e.g. ".provider.battery"
        final String table;           // primary table, e.g. "battery"
        final int hardwareType;       // Sensor.TYPE_* for physical sensors, or -1
        final String[] permissions;   // required runtime permissions (may be empty)
        final boolean needsAccessibility;
        final boolean needsLocationServices; // the OS-level Location toggle, not a permission

        Def(String authoritySuffix, String table, int hardwareType, String[] permissions, boolean needsAccessibility) {
            this(authoritySuffix, table, hardwareType, permissions, needsAccessibility, false);
        }

        Def(String authoritySuffix, String table, int hardwareType, String[] permissions, boolean needsAccessibility, boolean needsLocationServices) {
            this.authoritySuffix = authoritySuffix;
            this.table = table;
            this.hardwareType = hardwareType;
            this.permissions = permissions;
            this.needsAccessibility = needsAccessibility;
            this.needsLocationServices = needsLocationServices;
        }
    }

    private static final String[] NONE = new String[0];
    private static final Map<String, Def> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("accelerometer", new Def(".provider.accelerometer", "sensor_accelerometer", Sensor.TYPE_ACCELEROMETER, NONE, false));
        REGISTRY.put("linear_accelerometer", new Def(".provider.accelerometer.linear", "sensor_accelerometer_linear", Sensor.TYPE_LINEAR_ACCELERATION, NONE, false));
        REGISTRY.put("significant_motion", new Def(".provider.significant", "significant", Sensor.TYPE_ACCELEROMETER, NONE, false));
        REGISTRY.put("barometer", new Def(".provider.barometer", "sensor_barometer", Sensor.TYPE_PRESSURE, NONE, false));
        REGISTRY.put("gravity", new Def(".provider.gravity", "sensor_gravity", Sensor.TYPE_GRAVITY, NONE, false));
        REGISTRY.put("gyroscope", new Def(".provider.gyroscope", "sensor_gyroscope", Sensor.TYPE_GYROSCOPE, NONE, false));
        REGISTRY.put("light", new Def(".provider.light", "sensor_light", Sensor.TYPE_LIGHT, NONE, false));
        REGISTRY.put("magnetometer", new Def(".provider.magnetometer", "sensor_magnetometer", Sensor.TYPE_MAGNETIC_FIELD, NONE, false));
        REGISTRY.put("proximity", new Def(".provider.proximity", "sensor_proximity", Sensor.TYPE_PROXIMITY, NONE, false));
        REGISTRY.put("rotation", new Def(".provider.rotation", "sensor_rotation", Sensor.TYPE_ROTATION_VECTOR, NONE, false));
        REGISTRY.put("temperature", new Def(".provider.temperature", "sensor_temperature", Sensor.TYPE_AMBIENT_TEMPERATURE, NONE, false));

        REGISTRY.put("battery", new Def(".provider.battery", "battery", -1, NONE, false));
        REGISTRY.put("screen", new Def(".provider.screen", "screen", -1, NONE, false));
        REGISTRY.put("network", new Def(".provider.network", "network", -1, NONE, false));
        REGISTRY.put("processor", new Def(".provider.processor", "processor", -1, NONE, false));
        REGISTRY.put("timezone", new Def(".provider.timezone", "timezone", -1, NONE, false));
        REGISTRY.put("esm", new Def(".provider.esm", "esms", -1, NONE, false));

        REGISTRY.put("locations", new Def(".provider.locations", "locations", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false));
        // WiFi scanning requires the OS-level Location toggle on system-wide, in addition to the
        // ACCESS_FINE_LOCATION permission below — Android blocks WifiManager.startScan() with a
        // SecurityException when Location services are off, regardless of granted permissions.
        REGISTRY.put("wifi", new Def(".provider.wifi", "wifi", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false, true));
        REGISTRY.put("bluetooth", new Def(".provider.bluetooth", "sensor_bluetooth", -1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, false));
        REGISTRY.put("communication", new Def(".provider.communication", "calls", -1, new String[]{Manifest.permission.READ_CALL_LOG}, false));
        REGISTRY.put("telephony", new Def(".provider.telephony", "telephony", -1, new String[]{Manifest.permission.READ_PHONE_STATE}, false));

        // Accessibility-backed sensors
        REGISTRY.put("applications", new Def(".provider.applications", "applications_foreground", -1, NONE, true));
        REGISTRY.put("screenshot", new Def(".provider.screenshot", "screenshot", -1, NONE, true));
    }

    /** True if the given preference key is a known sensor category. */
    public static boolean isSensor(String categoryKey) {
        return REGISTRY.containsKey(categoryKey);
    }

    /**
     * Computes the collection status for one sensor category.
     *
     * @param context               any context
     * @param categoryKey           the sensor preference key (e.g. "battery")
     * @param accessibilityEnabled  whether AWARE's accessibility service is currently on
     */
    public static Status getStatus(Context context, String categoryKey, boolean accessibilityEnabled) {
        Def def = REGISTRY.get(categoryKey);
        if (def == null) return new Status(false, 0, "Unknown sensor", null);

        long lastData = latestTimestamp(context, def);
        boolean recent = lastData > 0 && (System.currentTimeMillis() - lastData) <= RECENT_WINDOW_MS;
        if (recent) {
            return new Status(true, lastData, "Collecting data", null);
        }

        // Not collecting — find the most likely reason.
        if (def.hardwareType != -1 && !hasHardware(context, def.hardwareType)) {
            return new Status(false, lastData, "This device has no " + prettyName(categoryKey) + " sensor", null);
        }
        if (def.needsAccessibility && !accessibilityEnabled) {
            return new Status(false, lastData, "Accessibility service is off",
                    "Enable AWARE under Settings › Accessibility");
        }
        if (def.needsLocationServices && !isLocationServicesEnabled(context)) {
            return new Status(false, lastData, "Location services are off",
                    "Enable Location under Settings › Location");
        }
        String missing = firstMissingPermission(context, def.permissions);
        if (missing != null) {
            String p = shortPermission(missing);
            return new Status(false, lastData, "Missing permission: " + p,
                    "Grant the " + p + " permission in app settings");
        }
        if (lastData == 0) {
            return new Status(false, 0, "No data collected yet",
                    "Data may take a moment to appear after joining");
        }
        return new Status(false, lastData, "No recent data", null);
    }

    private static long latestTimestamp(Context context, Def def) {
        Uri uri = Uri.parse("content://" + context.getPackageName() + def.authoritySuffix + "/" + def.table);
        Cursor c = null;
        try {
            // Order by _id (rowid, indexed) rather than timestamp to stay fast on large tables;
            // rows are inserted in time order, so the last rowid holds the newest timestamp.
            c = context.getContentResolver().query(uri, new String[]{"timestamp"}, null, null, "_id DESC LIMIT 1");
            if (c != null && c.moveToFirst()) {
                return (long) c.getDouble(0);
            }
        } catch (Exception e) {
            // Provider missing / not readable — treat as no data.
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private static boolean hasHardware(Context context, int sensorType) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(sensorType) != null;
    }

    /** The OS-level Location toggle (Settings › Location) — distinct from the location permission. */
    public static boolean isLocationServicesEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private static String firstMissingPermission(Context context, String[] permissions) {
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return p;
            }
        }
        return null;
    }

    private static String shortPermission(String permission) {
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    private static String prettyName(String categoryKey) {
        return categoryKey.replace('_', ' ');
    }
}
