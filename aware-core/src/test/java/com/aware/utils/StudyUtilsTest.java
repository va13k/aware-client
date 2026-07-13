package com.aware.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Regression test for StudyUtils.jsonEquals(). Locks in the fix that switched the comparison from
 * JSONAssert's LENIENT mode to NON_EXTENSIBLE: LENIENT treats arrays as a one-directional subset
 * check, so a config edit that only *adds* an entry to the "sensors" array (rather than toggling
 * an existing entry's value) compared as equal to the old config and was silently ignored by
 * syncStudyConfig() — a real bug that shipped and was only caught by on-device testing.
 */
public class StudyUtilsTest {

    private static JSONObject configWithSensors(JSONObject... sensors) throws JSONException {
        JSONArray sensorsArray = new JSONArray();
        for (JSONObject sensor : sensors) sensorsArray.put(sensor);
        JSONObject config = new JSONObject();
        config.put("sensors", sensorsArray);
        return config;
    }

    private static JSONObject sensor(String setting, boolean value) throws JSONException {
        JSONObject sensor = new JSONObject();
        sensor.put("setting", setting);
        sensor.put("value", value);
        return sensor;
    }

    @Test
    public void additiveOnlySensorChange_isNotEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true));
        JSONObject after = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", true));

        // This is the exact regression: under the old LENIENT mode, "after" being a superset of
        // "before" compared as equal, so the newly-added sensor was never detected or applied.
        assertFalse(StudyUtils.jsonEquals(before, after));
    }

    @Test
    public void removedSensor_isNotEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", true));
        JSONObject after = configWithSensors(sensor("status_location_gps", true));

        assertFalse(StudyUtils.jsonEquals(before, after));
    }

    @Test
    public void identicalConfigs_areEqual() throws JSONException {
        JSONObject before = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", false));
        JSONObject after = configWithSensors(sensor("status_location_gps", true), sensor("status_wifi", false));

        assertTrue(StudyUtils.jsonEquals(before, after));
    }
}
