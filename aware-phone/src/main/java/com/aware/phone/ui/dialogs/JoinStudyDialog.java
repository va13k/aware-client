package com.aware.phone.ui.dialogs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.phone.ui.Aware_Join_Study;
import com.aware.phone.ui.SensorConsentActivity;
import com.aware.utils.StudyUtils;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * Manages dialog that is used to join a study by typing in a URL of the study config.
 */
public class JoinStudyDialog extends DialogFragment {
    private static final String TAG = "AWARE::JoinStudyDialog";
    private Activity mActivity;
    private ProgressBar mProgressBar;
    private String prefillUrl;

    public JoinStudyDialog(Activity activity) {
        this.mActivity = activity;
    }

    /**
     * Pre-fills the study URL field. Used to re-join a previously joined study: the dialog is
     * still shown (and thus attached), so the normal, working join path runs on confirm.
     */
    public JoinStudyDialog setStudyUrl(String url) {
        this.prefillUrl = url;
        return this;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        LayoutInflater inflater = mActivity.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.dialog_join_study, null);

        if (prefillUrl != null && prefillUrl.length() > 0) {
            EditText etPrefill = dialogView.findViewById(R.id.et_join_study_url);
            etPrefill.setText(prefillUrl);
        }

        builder.setView(dialogView);
        builder.setTitle("Enter URL for study")
                .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        EditText etStudyConfigUrl = dialogView.findViewById(R.id.et_join_study_url);
                        EditText dbPassword = dialogView.findViewById(R.id.db_password); // manually input password
                        new ValidateStudyConfig().executeOnExecutor(
                                AsyncTask.THREAD_POOL_EXECUTOR,
                                etStudyConfigUrl.getText().toString(),
                                dbPassword.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        JoinStudyDialog.this.dismiss();
                    }
                });
        return builder.create();
    }

    public void showDialog() {
        this.show(mActivity.getFragmentManager(), "dialog");
    }

    public void validateStudy(String studyUrl) {
        new ValidateStudyConfig().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, studyUrl);
    }

    public class ValidateStudyConfig extends AsyncTask<String, Void, String> {
        private ProgressDialog mLoader;
        private String url;
        private String input_password;
        private Boolean validPassword;
        private Boolean validURL;

        @Override
        protected void onPreExecute() {
            mLoader = new ProgressDialog(mActivity);
            mLoader.setTitle(R.string.loading_join_study_title);
            mLoader.setMessage(getResources().getString(R.string.loading_join_study_msg));
            mLoader.setCancelable(true);
            mLoader.setIndeterminate(true);
            mLoader.show();
        }

        @Override
        protected String doInBackground(String... strings) {
            Log.i(TAG, "Joining study with URL " + url);

            JSONObject studyConfig;

            url = strings[0];
            input_password = strings[1];

            try {
                studyConfig = StudyUtils.getStudyConfig(url);
                JoinStudyDialog.this.dismiss();

                if (studyConfig == null){
                    validURL = false;
                }else{
                    validURL = true;
                }

                validPassword = StudyUtils.validateStudyConfig(mActivity, studyConfig, input_password);

                if (studyConfig == null || !validPassword) {
                    Log.d(TAG, "Failed to join study with URL: " + url);
                } else {
                    return studyConfig.toString();
                }

            } catch (JSONException e) {
                Log.d(TAG, "Failed to join study with URL: " + url + ", reason: " + e.getMessage());
            }
            return null;
        }

        @Override
        protected void onPostExecute(String studyConfig) {
            mLoader.dismiss();
            JoinStudyDialog.this.dismiss();
            Log.d(TAG, "URL: " + validURL);
            if (validURL == null || !validURL) {
                Toast.makeText(mActivity, "Invalid study config or no internet. Please contact the " +
                                "administrator of this study or enter a different study URL.",
                        Toast.LENGTH_LONG).show();
            }
            else if (!validPassword){
                Toast.makeText(mActivity, "Password not correct",
                        Toast.LENGTH_LONG).show();
            }
            else {
                // Show the sensor consent screen first (it reviews what the study collects and
                // collects any permission grants), then it hands off to the identifier / sign-up
                // step. Runs before enrolment — nothing is applied until the participant signs up.
                Intent consent = new Intent(mActivity, SensorConsentActivity.class);
                consent.putExtra(Aware_Join_Study.EXTRA_STUDY_URL, url);
                consent.putExtra(Aware_Join_Study.EXTRA_STUDY_CONFIG, studyConfig);
                consent.putExtra(Aware_Join_Study.INPUT_PASSWORD, input_password);
                consent.putExtra(SensorConsentActivity.EXTRA_PRE_JOIN, true);
                consent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                mActivity.startActivity(consent);
            }
        }
    }
}
