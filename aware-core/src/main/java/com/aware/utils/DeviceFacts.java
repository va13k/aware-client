package com.aware.utils;

import com.aware.providers.Aware_Provider.Aware_Device;

import java.util.Map;

/**
 * Decides whether a device's current hardware and OS facts differ from what the aware_device table
 * already records for it.
 *
 * aware_device is a device dimension, not an event stream: one row per device, plus a row when a
 * device fact genuinely changes (an Android upgrade, a hardware swap). Two rows under one device_id
 * therefore carry meaning — compare release/sdk/build_id between them and a mid-study OS upgrade is
 * legible. That makes a spurious "changed" verdict costlier than a missing one: it buries real
 * upgrades in identical rows.
 *
 * Free of Android types and of any state, so it is unit-testable directly.
 */
public final class DeviceFacts {

    private DeviceFacts() {
    }

    /**
     * The aware_device columns that describe the device itself, and so decide whether the device's
     * current state warrants a new row.
     *
     * Excluded are {@code _id} and {@code timestamp} (which differ by construction),
     * {@code device_id} (the row's identity, used as the lookup key) and {@code label} — a
     * participant-editable name kept in step across every row of a device_id by
     * {@code UPDATE ... WHERE device_id LIKE}, so it is never on its own a reason to add a row.
     */
    public static final String[] COMPARED_COLUMNS = {
            Aware_Device.BOARD, Aware_Device.BRAND, Aware_Device.DEVICE, Aware_Device.BUILD_ID,
            Aware_Device.HARDWARE, Aware_Device.MANUFACTURER, Aware_Device.MODEL,
            Aware_Device.PRODUCT, Aware_Device.SERIAL, Aware_Device.RELEASE,
            Aware_Device.RELEASE_TYPE, Aware_Device.SDK};

    /**
     * Compares a stored aware_device row against the device's current facts across
     * {@link #COMPARED_COLUMNS}.
     *
     * Null and empty count as the same value: a column the platform reports as null but SQLite
     * stores as empty text (or the reverse) would otherwise read as changed on every check and
     * produce an endless run of near-identical rows.
     *
     * @param stored  the newest stored row for this device_id, or null when the device has none
     * @param current the device's facts as the platform reports them now
     * @return true when a new row would say nothing the newest stored row does not already say
     */
    public static boolean unchanged(Map<String, String> stored, Map<String, String> current) {
        if (stored == null || current == null) return false;
        for (String column : COMPARED_COLUMNS) {
            String was = stored.get(column) == null ? "" : stored.get(column);
            String now = current.get(column) == null ? "" : current.get(column);
            if (!was.equals(now)) return false;
        }
        return true;
    }
}
