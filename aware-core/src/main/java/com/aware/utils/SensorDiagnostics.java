package com.aware.utils;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.text.TextUtils;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.aware.Applications;
import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.providers.Aware_Provider;

import org.json.JSONArray;
import org.json.JSONObject;

import android.database.Cursor;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes one "sensor_status" line per study-enabled sensor into aware_log — a table that already
 * syncs to the researcher's database via AwareSyncAdapter, so this needs no new server-side schema.
 * Today a researcher can see *that* a sensor has no rows on a participant's device, but not *why*
 * (no hardware, missing permission, accessibility off, location services off) — that reason only
 * ever existed on-device, in aware-phone's SensorCollection, which the participant sees but the
 * researcher never does. This makes the same reason visible to the researcher.
 *
 * Deliberately duplicates a slimmed-down version of SensorCollection's gating registry (permissions
 * / accessibility / location-services only, keyed by the status_* setting name rather than
 * SensorCollection's UI categoryKey) because aware-core cannot depend on aware-phone. Hardware
 * gating specifically is NOT duplicated — this and SensorCollection both delegate to
 * {@link SensorAvailability}, so there's exactly one place that decides "does this device have the
 * hardware", even though there are still two places that decide "does this sensor need a runtime
 * permission" (this class, and SensorCollection's own registry, for its participant-facing UI).
 *
 * Scope note: "excluded" here is derived from known prerequisites (hardware / permission /
 * accessibility / location services), not from querying each sensor's provider table for recent
 * rows — unlike aware-phone's SensorCollection.getStatus(), which also checks actual data recency.
 * That keeps this class free of a second copy of the ~20-sensor provider/table registry, at the
 * cost of not catching a sensor that's fully permitted but silently stuck for some other reason
 * (e.g. a crashed service). Good enough for "the participant can never satisfy this" visibility,
 * which is what motivated this class; the data-recency signal already exists once, in the UI.
 */
public final class SensorDiagnostics {

    private SensorDiagnostics() {}

    /** Gating requirements for one status_* setting, beyond hardware (see SensorAvailability). */
    private static final class Gate {
        final String[] permissions;
        final boolean needsAccessibility;
        final boolean needsLocationServices;

        Gate(String[] permissions, boolean needsAccessibility, boolean needsLocationServices) {
            this.permissions = permissions;
            this.needsAccessibility = needsAccessibility;
            this.needsLocationServices = needsLocationServices;
        }
    }

    private static final String[] NONE = new String[0];
    private static final Map<String, Gate> GATES = new HashMap<>();

    static {
        GATES.put(
            Aware_Preferences.STATUS_LOCATION_GPS,
            new Gate(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_LOCATION_NETWORK,
            new Gate(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                false,
                false
            )
        );
        // WiFi scanning needs the OS-level Location toggle on, in addition to the permission below —
        // see SensorCollection's identical note on WifiManager.startScan().
        GATES.put(
            Aware_Preferences.STATUS_WIFI,
            new Gate(
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                false,
                true
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_BLUETOOTH,
            new Gate(
                // API 31+ needs BLUETOOTH_SCAN/CONNECT (referenced by string — added after this
                // module's compile SDK); older versions gate Bluetooth scanning on location.
                Build.VERSION.SDK_INT >= 31
                    ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
                    : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_TELEPHONY,
            new Gate(
                new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_COMMUNICATION_EVENTS,
            new Gate(
                new String[]{
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_SMS
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_CALLS,
            new Gate(
                new String[]{
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_MESSAGES,
            new Gate(
                new String[]{
                    Manifest.permission.READ_SMS
                },
                false,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_APPLICATIONS,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_NOTIFICATIONS,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_CRASHES,
            new Gate(
                NONE,
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_KEYBOARD,
            new Gate(
                NONE, 
                true,
                false
            )
        );
        GATES.put(
            Aware_Preferences.STATUS_SCREENSHOT,
            new Gate(
                NONE,
                true,
                false
            )
        );
    }

    /**
     * True if a status_* setting needs an explicit participant grant — a runtime permission or the
     * Accessibility Service — i.e. it's a sensor consent must cover before it may collect. Base
     * sensors (no gate, or a gate needing only the Location-services toggle) return false: they need
     * no per-sensor agreement. Lets aware-core (e.g. the config sync) decide which newly-added
     * sensors to hold off until consent, without depending on aware-phone's SensorCollection.
     */
    public static boolean requiresConsent(String statusSetting) {
        Gate gate = GATES.get(statusSetting);
        return gate != null && (gate.permissions.length > 0 || gate.needsAccessibility);
    }

    /**
     * Pure reason computation given already-resolved booleans — split out from
     * {@link #computeReason(Context, String, boolean)} so it's directly unit-testable without a
     * Context, same reasoning as StudyUtils.driftSignature()'s split from liveDriftSignature().
     *
     * @param statusSetting          the status_* setting, used only to look up its Gate
     * @param hardwareAvailable      result of SensorAvailability.isHardwareAvailable
     * @param missingPermission      the first ungranted permission this sensor needs, or null
     * @param accessibilityEnabled   whether AWARE's Accessibility Service is currently on
     * @param locationServicesEnabled whether the OS-level Location toggle is currently on
     * @return "" if nothing is blocking this sensor, otherwise a short human-readable reason
     */
    static String reasonGivenState(
        String statusSetting,
        boolean hardwareAvailable,
        String missingPermission,
        boolean accessibilityEnabled,
        boolean locationServicesEnabled
    ) {
        if (!hardwareAvailable) return "No such sensor hardware on this device";

        Gate gate = GATES.get(statusSetting);
        if (gate == null) return ""; // not gated by anything besides hardware, already checked above

        if (gate.needsAccessibility && !accessibilityEnabled) return "Accessibility service is off";
        if (gate.needsLocationServices && !locationServicesEnabled) return "Location services are off";
        if (missingPermission != null) return "Missing permission: " + shortPermission(missingPermission);
        return "";
    }

    /** Context-backed wrapper of {@link #reasonGivenState} — resolves the live state and delegates. */
    public static String computeReason(
        Context context,
        String statusSetting,
        boolean accessibilityEnabled
    ) {
        boolean hardwareAvailable = SensorAvailability.isHardwareAvailable(context, statusSetting);
        Gate gate = GATES.get(statusSetting);
        String missingPermission = gate == null ? null : firstMissingPermission(context, gate.permissions);
        boolean locationServicesEnabled = gate != null && gate.needsLocationServices && isLocationServicesEnabled(context);
        return reasonGivenState(statusSetting, hardwareAvailable, missingPermission, accessibilityEnabled, locationServicesEnabled);
    }

    /**
     * Writes one "sensor_status" line to aware_log for every status_* setting in {@code sensors}
     * that the study enabled (value == true). Format is a fixed, parseable key=value line so a
     * researcher can filter aware_log with e.g. "WHERE log_message LIKE 'sensor_status%'":
     * <pre>sensor_status ts=&lt;ms&gt; sensor=&lt;key&gt; enabled=true excluded=&lt;true|false&gt; reason="&lt;text&gt;"</pre>
     */
    public static void logSensorStatus(Context context, JSONArray sensors) {
        if (sensors == null) return;
        boolean accessibilityEnabled = isAccessibilityServiceEnabled(context);
        long now = System.currentTimeMillis();

        for (int i = 0; i < sensors.length(); i++) {
            JSONObject sensor = sensors.optJSONObject(i);
            if (sensor == null) continue;

            String setting = sensor.optString("setting", "");
            if (!setting.startsWith("status_") || !sensor.optBoolean("value", false)) continue;

            String reason = computeReason(context, setting, accessibilityEnabled);
            boolean excluded = !reason.isEmpty();
            String key = setting.substring("status_".length());

            String line = "sensor_status ts=" + now
                    + " sensor=" + key
                    + " enabled=true"
                    + " excluded=" + excluded
                    + " reason=\"" + reason + "\"";
            Aware.debug(context, line);
        }
    }

    /**
     * Convenience for callers that only have a Context, not an already-parsed sensors array (e.g.
     * the periodic compliance schedule) — looks up the currently active study and logs its sensors.
     * No-op if there's no active study or its config can't be parsed.
     */
    public static void logActiveStudySensorStatus(Context context) {
        Cursor study = Aware.getActiveStudy(context);
        if (study == null) return;
        try {
            if (!study.moveToFirst()) return;
            JSONArray configs = new JSONArray(study.getString(
                    study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
            for (int i = 0; i < configs.length(); i++) {
                JSONObject element = configs.optJSONObject(i);
                if (element != null && element.has("sensors")) {
                    logSensorStatus(context, element.optJSONArray("sensors"));
                    return; // one config element carries the sensors array; no need to keep looking
                }
            }
        } catch (Exception e) {
            // Malformed/missing config — nothing meaningful to log.
        } finally {
            study.close();
        }
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

    /** The OS-level Location toggle (Settings › Location) — distinct from the location permission. */
    private static boolean isLocationServicesEnabled(Context context) {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /** True if AWARE's Accessibility Service (backs Applications, Keyboard, Screenshot) is on. */
    private static boolean isAccessibilityServiceEnabled(Context context) {
        ComponentName expected = new ComponentName(context, Applications.class);
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (expected.equals(ComponentName.unflattenFromString(splitter.next()))) {
                return true;
            }
        }
        return false;
    }
}
