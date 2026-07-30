package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the two decisions upload health makes on its own: when a delivery failure is worth
 * interrupting the participant for, and what the app tells them in the meantime.
 *
 * Both are pure, so they can be exercised without a device or a real clock. The recording
 * side needs a Context and is covered by the device checks in BETA_BLOCKERS.md instead.
 */
public class UploadHealthTest {

    private static final long HOUR = 60 * 60 * 1000L;

    // --- When to notify ---

    @Test
    public void healthyDeliveryNeverNotifies() {
        assertFalse(UploadHealth.shouldNotify(0L, false));
    }

    @Test
    public void aFailingDeliveryNotifiesImmediately() {
        // No waiting period: a phone that has stopped reaching the study should say so on the first
        // refused batch, not after the participant has lost hours of delivery.
        assertTrue(UploadHealth.shouldNotify(1L, false));
        assertTrue(UploadHealth.shouldNotify(100 * HOUR, false));
    }

    @Test
    public void theSameOutageIsNotNotifiedTwice() {
        // Every table reports the same outage on every sync tick, and re-posting the same
        // notification id alerts again each time. Without this the participant is buzzed once per
        // table per minute — the spam this design exists to avoid.
        assertFalse(UploadHealth.shouldNotify(100 * HOUR, true));
    }

    @Test
    public void aNegativeOutageStartIsTreatedAsHealthy() {
        // Defensive: a corrupt or backwards-clock value must not read as an active outage.
        assertFalse(UploadHealth.shouldNotify(-1L, false));
    }

    // --- What the app shows ---

    @Test
    public void aNewEnrolmentSaysNothingHasBeenDeliveredYet() {
        // Distinct from "fallen behind": on a fresh join there is no gap to explain.
        assertEquals("Nothing delivered yet", UploadHealth.statusLine(null, false, 0));
    }

    @Test
    public void healthyDeliveryReportsHowFarItReached() {
        assertEquals("Delivered up to 5 minutes ago",
                UploadHealth.statusLine("5 minutes ago", false, 0));
    }

    @Test
    public void aFailingUploadSaysSoAndPromisesTheDataIsKept() {
        String line = UploadHealth.statusLine("2 days ago", true, 480);
        assertTrue(line.contains("not delivering right now"));
        assertTrue(line.contains("480 records waiting"));
        // The reassurance matters as much as the warning: the participant should not conclude their
        // data is being lost, because it is not.
        assertTrue(line.contains("kept on the device"));
    }

    @Test
    public void aPendingCountOfOneReadsAsSingular() {
        assertTrue(UploadHealth.statusLine("1 hour ago", true, 1).contains("1 record waiting"));
    }

    @Test
    public void anUnknownPendingCountIsOmittedRatherThanShownAsZero() {
        String line = UploadHealth.statusLine("1 hour ago", true, 0);
        assertFalse("0 records waiting would misreport an unknown count", line.contains("0 record"));
        assertTrue(line.contains("not delivering right now"));
    }

    @Test
    public void aFailingUploadWithNoPriorDeliveryStillExplainsItself() {
        String line = UploadHealth.statusLine(null, true, 12);
        assertTrue(line.startsWith("Nothing delivered yet"));
        assertTrue(line.contains("not delivering right now"));
    }
}
