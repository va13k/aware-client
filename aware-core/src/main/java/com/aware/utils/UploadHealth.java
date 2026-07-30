package com.aware.utils;

import android.content.Context;
import android.database.Cursor;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.R;
import com.aware.providers.Aware_Provider;

/**
 * Whether data is reaching the research database, and what the participant is told about it.
 *
 * Upload health is kept as state rather than as a stream of events. {@code onPerformSync} runs once
 * per table, so a single outage produces a failure for every table on every tick; recording each one
 * would fill aware_log with the same fact. Instead the outage is recorded when it *starts* and
 * cleared when a batch is next acknowledged, so one outage leaves one entry however long it lasts.
 *
 * The participant is told two things. The app always shows how far delivery has reached, which costs
 * no attention. A notification is posted as soon as delivery starts failing, so a phone that has
 * stopped reaching the study is visible straight away rather than discovered later. It is posted once
 * per outage — the edge, not every failure — and cancelled when delivery recovers.
 */
public final class UploadHealth {

    private UploadHealth() {
    }

    /** Records that a batch was acknowledged: ends any outage and clears its notification. */
    public static void recordSuccess(Context context) {
        boolean wasFailing = outageSince(context) > 0;
        Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_SINCE, 0);
        Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_REASON, "");

        if (wasFailing) {
            Aware.debug(context, Aware.LogType.SYNC, "Upload recovered; data is reaching the database again");
            StudyUtils.cancelStudyNotification(context, Aware.AWARE_UPLOAD_HEALTH_NOTIFICATION_ID);
            Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED, "false");
        }
    }

    /**
     * Records that a batch was not acknowledged.
     *
     * @param reason short, non-sensitive description of why — never a connection string or password
     */
    public static void recordFailure(Context context, String reason) {
        long now = System.currentTimeMillis();
        if (outageSince(context) == 0) {
            // Edge-triggered: the first failure of an outage is the one worth recording. Later
            // failures say the same thing about the same outage.
            Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_SINCE, now);
            Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_REASON, reason);
            Aware.debug(context, Aware.LogType.SYNC, "Upload failing: " + reason);
        }

        if (shouldNotify(outageSince(context), alreadyNotified(context))) {
            StudyUtils.postStudyNotification(context, Aware.AWARE_UPLOAD_HEALTH_NOTIFICATION_ID,
                    R.string.aware_notif_upload_stalled_title,
                    R.string.aware_notif_upload_stalled);
            Aware.setSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED, "true");
            Aware.debug(context, Aware.LogType.SYNC,
                    "Notified the participant that data is not reaching the database");
        }
    }

    /**
     * Whether a delivery-failure notification is due: delivery is failing and the participant has not
     * already been told about this outage.
     *
     * The second condition is what keeps one outage to one notification. Every table reports the same
     * outage on every sync tick, and re-posting the same notification id alerts again each time, so
     * without it the participant would be buzzed once per table per minute.
     *
     * Pure, so it can be unit-tested without a device.
     *
     * @param outageSince when the current outage began, or 0 if delivery is healthy
     */
    static boolean shouldNotify(long outageSince, boolean alreadyNotified) {
        return outageSince > 0 && !alreadyNotified;
    }

    /**
     * One line for the participant, describing how far delivery has reached.
     *
     * Pure and free of Android formatting: the caller passes a rendered relative time so this stays
     * unit-testable. A null {@code deliveredUpToRelative} means nothing has ever been delivered, which
     * reads differently from a delivery that has fallen behind — on a new enrolment there is no gap
     * to explain yet.
     */
    public static String statusLine(CharSequence deliveredUpToRelative, boolean failing, int pendingRecords) {
        StringBuilder line = new StringBuilder();
        if (deliveredUpToRelative == null) {
            line.append("Nothing delivered yet");
        } else {
            line.append("Delivered up to ").append(deliveredUpToRelative);
        }
        if (failing) {
            line.append(" — not delivering right now");
            if (pendingRecords > 0) {
                line.append("; ").append(pendingRecords).append(" record")
                        .append(pendingRecords == 1 ? "" : "s").append(" waiting");
            }
            line.append(". Your data is kept on the device until it can be sent.");
        }
        return line.toString();
    }

    /** When the current outage began, or 0 when delivery is healthy. */
    public static long outageSince(Context context) {
        return parseLong(Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_SINCE));
    }

    /**
     * The point the research database has been brought up to: the newest row timestamp any table's
     * upload bookmark reports as delivered, or 0 when nothing has been.
     *
     * Read from the bookmarks rather than from the clock time of the last successful upload, because
     * those diverge exactly when it matters. Draining a backlog, an upload can succeed this minute
     * while the server is still only caught up to last week — reporting the upload time would claim
     * the data had arrived. This is the same quantity {@code SensorCollection.lastDeliveredMs} shows
     * per sensor, maximised across tables, so the two screens cannot disagree.
     */
    public static long deliveredUpToMs(Context context) {
        Cursor markers = context.getContentResolver().query(
                Aware_Provider.Aware_Sync_Markers.CONTENT_URI,
                new String[]{Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED},
                null, null, null);
        if (markers == null) return 0L;
        long newest = 0L;
        try {
            int column = markers.getColumnIndex(Aware_Provider.Aware_Sync_Markers.MARKER_LAST_SYNCED);
            if (column < 0) return 0L;
            while (markers.moveToNext()) {
                long delivered = markers.getLong(column);
                if (delivered > newest) newest = delivered;
            }
        } finally {
            markers.close();
        }
        return newest;
    }

    /** Why delivery is currently failing, or an empty string when it is not. */
    public static String outageReason(Context context) {
        String reason = Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_REASON);
        return reason == null ? "" : reason;
    }

    private static boolean alreadyNotified(Context context) {
        return "true".equals(Aware.getSetting(context, Aware_Preferences.UPLOAD_OUTAGE_NOTIFIED));
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
