package com.aware.phone.ui.dialogs;

import android.accounts.Account;
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


/**
 * Manages dialog that is used to quit a study.
 */
public class QuitStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::QuitStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;

    public QuitStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle("Quit Study")
                .setMessage("Are you sure you want to quit the study?")
                .setCancelable(true)
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
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
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_TITLE, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_TITLE)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION, dbStudy.getString(dbStudy.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_DESCRIPTION)));
                            complianceEntry.put(Aware_Provider.Aware_Studies.STUDY_COMPLIANCE, "quit study");

                            mActivity.getContentResolver().insert(Aware_Provider.Aware_Studies.CONTENT_URI, complianceEntry);
                        }
                        if (dbStudy != null && !dbStudy.isClosed()) dbStudy.close();

                        dialogInterface.dismiss();

                        new QuitStudyAsync().execute();
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
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

    private class QuitStudyAsync extends AsyncTask<Void, Void, Void> {
        ProgressDialog mQuitting;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            mQuitting = new ProgressDialog(mActivity);
            mQuitting.setMessage("Quitting study, please wait.");
            mQuitting.setCancelable(false);
            mQuitting.setInverseBackgroundForced(false);
            mQuitting.show();
            mQuitting.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    mActivity.finish();

                    // Redirect the user to the main UI
                    Intent mainUI = new Intent(mActivity, Aware_Client.class);
                    mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(mainUI);
                }
            });
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Stop the screenshot service
            stopScreenshotService();

            // Upload the "quit study" (exit) row to the researcher's server BEFORE reset(), while we
            // are still enrolled. reset() clears webservice_server and flips isStudy() false, after
            // which the exit event can no longer sync — so the researcher would never see the leave.
            // Bounded wait: returns as soon as the sync finishes (usually ~1-3s); the cap only
            // matters on a stalled/offline network, where we give up and reset anyway.
            uploadStudyExitBlocking(6000);

            // Reset Aware settings
            Aware.reset(mActivity);
            return null;
        }


        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            mQuitting.dismiss();
        }
    }

    private void stopScreenshotService() {
        Intent serviceIntent = new Intent(mActivity, ScreenShot.class);
        mActivity.stopService(serviceIntent);
    }

    /**
     * Best-effort synchronous upload of the study rows (incl. the "quit study"/exit row) before the
     * caller resets AWARE. Requests an expedited manual sync of the aware provider and waits, up to
     * {@code timeoutMs}, for it to finish — so leaving the study is reported to the researcher even
     * though {@link Aware#reset(android.content.Context)} is about to disable syncing. Never throws
     * and never blocks longer than the timeout, so quitting stays responsive (and still works
     * offline — the reset proceeds regardless).
     */
    private void uploadStudyExitBlocking(long timeoutMs) {
        try {
            Account account = Aware.getAWAREAccount(mActivity);
            String authority = Aware_Provider.getAuthority(mActivity);
            if (account == null || authority == null) return;

            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(account, authority, extras);

            long deadline = System.currentTimeMillis() + timeoutMs;
            // Wait for the sync to start (it may be briefly pending before becoming active)...
            while (System.currentTimeMillis() < deadline
                    && !ContentResolver.isSyncActive(account, authority)
                    && ContentResolver.isSyncPending(account, authority)) {
                Thread.sleep(200);
            }
            // ...then wait for it to complete, or until the timeout.
            while (System.currentTimeMillis() < deadline
                    && (ContentResolver.isSyncActive(account, authority)
                        || ContentResolver.isSyncPending(account, authority))) {
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Study exit sync before reset failed (best-effort): " + e.getMessage());
        }
    }

}
