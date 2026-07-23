package com.aware.phone.ui.dialogs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.ProgressBar;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.ScreenShot;
import com.aware.phone.ui.Aware_Client;
import com.aware.providers.Aware_Provider;
import com.aware.utils.Jdbc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Manages dialog that is used to quit a study.
 */
public class QuitStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::QuitStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;
    private ContentValues mStudyExitEntry;

    public QuitStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle("Leave this study?")
                .setMessage("Leaving stops this study's data collection and removes its settings "
                        + "from this device. Data already uploaded is not deleted.\n\n"
                        + "Are you sure you want to leave?")
                .setCancelable(true)
                .setPositiveButton("Leave study", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getActiveStudy(mActivity);
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            mStudyExitEntry = createStudyExitEntry(dbStudy);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();

                        if (mStudyExitEntry != null) {
                            new QuitStudyAsync().execute();
                        } else {
                            showLeaveFailedDialog();
                        }
                    }
                })
                .setNegativeButton("Stay in study", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Cursor dbStudy = Aware.getActiveStudy(mActivity);
                        if (dbStudy != null && dbStudy.moveToFirst()) {
                            ContentValues complianceEntry = new ContentValues();
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_EXIT)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "canceled quit");

                            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();
                    }
                });
        return builder.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        // A confirmed leave uses the targeted, acknowledged upload below. Starting the full
        // provider sync at the same time would compete for the JDBC connection and make leaving
        // slow again.
        if (mStudyExitEntry != null) return;

        // Sync to server the studies statuses
        Bundle sync = new Bundle();
        sync.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        sync.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(Aware.getAWAREAccount(mActivity), Aware_Provider.getAuthority(mActivity), sync);
    }

    /**
     * Store information on attempt to quit study and then show the dialog to confirm the quit.
     */
    public void showDialog() {
        Log.i(TAG, "Quitting from active study");

        Cursor dbStudy = Aware.getActiveStudy(mActivity);
        if (dbStudy != null && dbStudy.moveToFirst()) {
            ContentValues complianceEntry = new ContentValues();
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID, Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_KEY, dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_API, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_URL, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_PI, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_JOINED, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_EXIT)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "attempt to quit study");

            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
        }
        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();
        this.show(mActivity.getFragmentManager(), "dialog");
    }

    private class QuitStudyAsync extends AsyncTask<Void, Void, Boolean> {
        ProgressDialog mQuitting;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            mQuitting = new ProgressDialog(mActivity);
            mQuitting.setMessage("Quitting study, please wait.");
            mQuitting.setCancelable(false);
            mQuitting.setInverseBackgroundForced(false);
            mQuitting.show();
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            // Upload only the exit record and require a successful database acknowledgement before
            // clearing the study credentials. This avoids the long full-provider sync while making
            // it impossible for the UI to report a completed leave that the researcher has not
            // received.
            if (!uploadStudyExit()) return false;

            mActivity.getContentResolver().insert(
                    Aware_Provider.Aware_Studies.CONTENT_URI, mStudyExitEntry);
            stopScreenshotService();
            Aware.reset(mActivity);
            return true;
        }


        @Override
        protected void onPostExecute(Boolean uploaded) {
            super.onPostExecute(uploaded);
            mQuitting.dismiss();

            if (!uploaded) {
                showLeaveFailedDialog();
                return;
            }

            mActivity.finish();
            Intent mainUI = new Intent(mActivity, Aware_Client.class);
            mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(mainUI);
        }
    }

    private void stopScreenshotService() {
        Intent serviceIntent = new Intent(mActivity, ScreenShot.class);
        mActivity.stopService(serviceIntent);
    }

    private ContentValues createStudyExitEntry(Cursor dbStudy) {
        ContentValues entry = new ContentValues();
        entry.put(Aware_Provider.Aware_Studies.STUDY_TIMESTAMP, System.currentTimeMillis());
        entry.put(Aware_Provider.Aware_Studies.STUDY_DEVICE_ID,
                Aware.getSetting(mActivity, Aware_Preferences.DEVICE_ID));
        entry.put(Aware_Provider.Aware_Studies.STUDY_KEY,
                dbStudy.getInt(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_KEY)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_API,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_API)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_URL,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_PI,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_PI)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_CONFIG,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_CONFIG)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_JOINED,
                dbStudy.getLong(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
        entry.put(Aware_Provider.Aware_Studies.STUDY_TITLE,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION,
                dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
        entry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "quit study");
        return entry;
    }

    /** Returns only after the research database acknowledges the exit row. */
    private boolean uploadStudyExit() {
        try {
            JSONObject row = new JSONObject();
            for (Map.Entry<String, Object> value : mStudyExitEntry.valueSet()) {
                row.put(value.getKey(), value.getValue());
            }
            return Jdbc.insertData(
                    mActivity.getApplicationContext(),
                    "aware_studies",
                    new JSONArray().put(row));
        } catch (Exception e) {
            Log.e(TAG, "The research database did not acknowledge the study exit", e);
            return false;
        }
    }

    private void showLeaveFailedDialog() {
        if (mActivity == null || mActivity.isFinishing()) return;

        new AlertDialog.Builder(mActivity)
                .setTitle("Could not leave study")
                .setMessage("The researcher could not be notified. Check your internet connection "
                        + "and try again. You are still enrolled and no study settings were removed.")
                .setPositiveButton("OK", null)
                .show();
    }

}
