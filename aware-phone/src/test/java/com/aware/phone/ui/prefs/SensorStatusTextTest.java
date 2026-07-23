package com.aware.phone.ui.prefs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the pure status-text helpers in SensorCollection — the strings shown both in the
 * locked-mode status dialog and in the editable-mode status row. They pin down each state: collecting
 * vs not, the reason line, last-data (a real time or "never"), and the optional "what to do" hint.
 */
public class SensorStatusTextTest {

    @Test
    public void headline_reflectsCollectingState() {
        assertEquals("●  Collecting data", SensorCollection.statusHeadline(true));
        assertEquals("○  Not collecting", SensorCollection.statusHeadline(false));
    }

    @Test
    public void detail_withFixHint_includesWhatToDo() {
        String detail = SensorCollection.statusDetail(
                "Accessibility service is off",
                "never",
                "Enable AWARE under Settings > Accessibility");
        assertEquals(
                "Accessibility service is off"
                        + "\n\nLast data: never"
                        + "\n\nWhat to do: Enable AWARE under Settings > Accessibility",
                detail);
    }

    @Test
    public void detail_withoutFixHint_omitsWhatToDo() {
        String detail = SensorCollection.statusDetail("Collecting data", "5 minutes ago", null);
        assertEquals("Collecting data\n\nLast data: 5 minutes ago", detail);
    }

    @Test
    public void detail_neverHasData_readsNever() {
        String detail = SensorCollection.statusDetail("Waiting for first sample", "never", null);
        assertEquals("Waiting for first sample\n\nLast data: never", detail);
    }

    @Test
    public void summary_isHeadlineThenDetail() {
        String summary = SensorCollection.statusSummary(
                false, "Missing permission: ACCESS_FINE_LOCATION", "never", "Grant it on the next screen");
        assertEquals(
                "○  Not collecting"
                        + "\n\nMissing permission: ACCESS_FINE_LOCATION"
                        + "\n\nLast data: never"
                        + "\n\nWhat to do: Grant it on the next screen",
                summary);
    }

    @Test
    public void summary_collecting_matchesHeadlineAndDetail() {
        boolean collecting = true;
        String reason = "Collecting data";
        CharSequence lastData = "2 minutes ago";
        String summary = SensorCollection.statusSummary(collecting, reason, lastData, null);
        assertEquals(
                SensorCollection.statusHeadline(collecting) + "\n\n"
                        + SensorCollection.statusDetail(reason, lastData, null),
                summary);
    }

    @Test
    public void eventDrivenStatus_isEnabledRatherThanFalselyNotCollecting() {
        SensorCollection.Status status = new SensorCollection.Status(
                true,
                true,
                0,
                "This sensor records data when an event occurs",
                null);
        assertEquals("●  Enabled — waiting for an event", SensorCollection.statusHeadline(status));
    }
}
