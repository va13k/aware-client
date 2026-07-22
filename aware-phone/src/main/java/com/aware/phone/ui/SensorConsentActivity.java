package com.aware.phone.ui;

import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.aware.Aware;
import com.aware.Aware_Preferences;
import com.aware.phone.R;
import com.aware.phone.ui.prefs.SensorCollection;
import com.aware.phone.ui.prefs.SensorCollection.ConsentItem;
import com.aware.providers.Aware_Provider;
import com.aware.utils.StudyUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-join consent screen: lists the sensors this study would enable that need a runtime permission,
 * one row at a time, each with an Enable button (or "Granted ✓"). Tapping Enable requests just that
 * sensor's permission(s), so the OS shows them one sensor at a time instead of many services each
 * firing their own request and stacking.
 *
 * Runs before the study's settings are applied: a sensor left ungranted when the participant taps
 * Continue is never turned on at all, rather than being turned on and then possibly off again.
 */
public class SensorConsentActivity extends AppCompatActivity {

    /**
     * When true, this screen is a mid-study re-consent for sensors the researcher added and that were
     * held off (declined) until the participant agrees — not a fresh join. The participant is already
     * enrolled, so there is no enrolment to roll back, and only the held-off sensors are shown.
     */
    public static final String EXTRA_UPDATE_MODE = "update_mode";

    private LinearLayout list;
    private List<ConsentItem> items;

    private boolean updateMode;
    private String studyUrl;
    private String inputPassword;
    private JSONArray studyConfigs;

    // Rows the participant has explicitly acted on in this screen. Several rows can share the same
    // underlying OS gate (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and Bluetooth; the single
    // Accessibility Service toggle backs Applications usage and Keyboard), so checking the OS state
    // alone would silently mark every row sharing that gate as consented the moment one of them is
    // granted. Consent is tracked per row instead, only set once that row's own action completes.
    private final Set<String> consentedKeys = new HashSet<>();

    // Which accessibility row's Enable button was last tapped, so the onResume recheck (there's no
    // onRequestPermissionsResult callback for a Settings-app toggle) attributes the enabled service
    // back to that row specifically, not to every accessibility row at once.
    private String pendingAccessibilityKey;

    // Android 13+ blocks the Accessibility toggle for sideloaded apps until the participant manually
    // allows it from the app's info screen. Shown once per screen instance, for any accessibility row.
    private boolean shownRestrictedSettingsHelp;

    // The "Allow all the time" (background location) nudge is shown at most once per screen instance.
    private boolean shownAlwaysLocationHelp;

    // Bundle keys used to carry the above state across a process death while the participant is away
    // in the Settings app, so their prior taps and one-time dialogs aren't forgotten on return.
    private static final String STATE_CONSENTED_KEYS = "consented_keys";
    private static final String STATE_PENDING_ACCESSIBILITY_KEY = "pending_accessibility_key";
    private static final String STATE_SHOWN_RESTRICTED_HELP = "shown_restricted_settings_help";
    private static final String STATE_SHOWN_ALWAYS_LOCATION_HELP = "shown_always_location_help";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_consent);

        updateMode = getIntent().getBooleanExtra(EXTRA_UPDATE_MODE, false);
        studyConfigs = new JSONArray();
        if (updateMode) {
            // Mid-study re-consent: the participant is already enrolled, so read the study they're in
            // (its live config, URL and DB password) rather than taking them from the launch intent.
            JSONObject activeConfig = Aware.getActiveStudyConfig(getApplicationContext());
            if (activeConfig != null) studyConfigs.put(activeConfig);
            studyUrl = activeStudyUrl();
            inputPassword = Aware.getSetting(getApplicationContext(), Aware_Preferences.DB_PASSWORD);
        } else {
            studyUrl = getIntent().getStringExtra(Aware_Join_Study.EXTRA_STUDY_URL);
            inputPassword = getIntent().getStringExtra(Aware_Join_Study.INPUT_PASSWORD);
            try {
                studyConfigs.put(new JSONObject(getIntent().getStringExtra(Aware_Join_Study.EXTRA_STUDY_CONFIG)));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        list = findViewById(R.id.consent_list);
        findViewById(R.id.btn_continue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onContinue();
            }
        });
        findViewById(R.id.btn_dont_join).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelJoin();
            }
        });

        if (updateMode) {
            // Mid-study re-consent: show only the sensors the researcher added that are held off,
            // and reframe the screen — the participant is already enrolled, so "don't join" becomes
            // "not now" and declining just leaves those sensors off.
            ((TextView) findViewById(R.id.consent_title)).setText("Study updated");
            ((TextView) findViewById(R.id.consent_subtitle)).setText(
                    "The researcher added sensors to this study. Enable the ones you agree to share, or leave them off — you can enable them later from the sensor list.");
            ((Button) findViewById(R.id.btn_dont_join)).setText("Not now");
            items = SensorCollection.heldConsentsForConfig(getApplicationContext(), studyConfigs);
        } else {
            items = SensorCollection.enabledConsentsForConfig(studyConfigs);
        }

        if (items.isEmpty()) {
            if (updateMode) { goToMainUi(); return; }
            applyAndFinish(Collections.<String>emptySet());
            return;
        }

        if (savedInstanceState != null) {
            // Coming back after a process death: restore the per-row consent exactly as it was.
            // Re-deriving it from live OS state here would be wrong, because several rows share one
            // gate (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and Bluetooth; the Accessibility
            // toggle backs Applications and Keyboard) — a permission granted for one tapped row would
            // otherwise silently mark every row sharing it as consented.
            restoreInstanceState(savedInstanceState);
        } else {
            // A row already satisfied before this screen ever opened (e.g. granted during an earlier
            // study join) doesn't need a redundant tap. Snapshotted once, here, rather than re-checked
            // on every buildRows(): a permission that only becomes satisfied DURING this screen because
            // another row's tap happens to share it (ACCESS_COARSE_LOCATION backs Location, Wi-Fi and
            // Bluetooth alike) must still go through that row's own tap, not get a free pass.
            for (ConsentItem item : items) {
                if (SensorCollection.isAlreadyGranted(getApplicationContext(), item)) {
                    consentedKeys.add(item.key);
                }
            }
        }
        buildRows();
    }

    private void buildRows() {
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < items.size(); i++) {
            final ConsentItem item = items.get(i);
            final int requestCode = i;

            View row = inflater.inflate(R.layout.item_sensor_consent, (ViewGroup) list, false);
            ((TextView) row.findViewById(R.id.sensor_label)).setText(item.label);
            ((TextView) row.findViewById(R.id.sensor_reason)).setText(item.reason);

            Button enable = row.findViewById(R.id.btn_enable);
            TextView granted = row.findViewById(R.id.txt_granted);

            if (consentedKeys.contains(item.key)) {
                enable.setVisibility(View.GONE);
                granted.setVisibility(View.VISIBLE);
            } else {
                enable.setVisibility(View.VISIBLE);
                granted.setVisibility(View.GONE);
                enable.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (item.needsAccessibility) {
                            enableAccessibility(item);
                        } else {
                            ActivityCompat.requestPermissions(SensorConsentActivity.this, item.permissions, requestCode);
                        }
                    }
                });
            }
            list.addView(row);
        }
    }

    private void enableAccessibility(ConsentItem item) {
        // The accessibility service is a single OS toggle shared by every accessibility-backed sensor
        // (Applications usage, Keyboard, Screenshots). If it's already on — e.g. the participant just
        // enabled it for another such row — this row's requirement is already met, so tapping Enable
        // is itself the consent; don't send them back to Accessibility settings.
        if (SensorCollection.isAccessibilityServiceEnabled(getApplicationContext())) {
            consentedKeys.add(item.key);
            buildRows();
            return;
        }
        pendingAccessibilityKey = item.key;
        if (Build.VERSION.SDK_INT >= 33 && !shownRestrictedSettingsHelp) {
            showRestrictedSettingsDialog();
        } else {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void showRestrictedSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("One extra step needed")
                .setMessage("Because AWARE wasn't installed from the Play Store, Android blocks " +
                        "Accessibility access until you allow it manually:\n\n" +
                        "1. On the App info screen that opens, tap the ⋮ menu (top-right) and " +
                        "choose \"Allow restricted settings\".\n" +
                        "2. Come back here and tap Enable again to open Accessibility Settings and " +
                        "turn AWARE on.")
                .setPositiveButton("Open App Info", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        shownRestrictedSettingsHelp = true;
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Judge the row from the live permission state (not the raw grantResults array), so a row is
        // marked consented once its required permissions are actually held — tolerating hard-restricted
        // ones the platform won't grant, and not depending on the callback array's exact shape.
        if (requestCode >= 0 && requestCode < items.size()
                && SensorCollection.isAlreadyGranted(getApplicationContext(), items.get(requestCode))) {
            ConsentItem granted = items.get(requestCode);
            consentedKeys.add(granted.key);
            // Location just went from off to on, but the foreground grant alone stops logging whenever
            // AWARE isn't open. Nudge the participant to switch it to "Allow all the time" for complete
            // location data, unless they already have it.
            if ("locations".equals(granted.key)
                    && !SensorCollection.hasBackgroundLocation(getApplicationContext())
                    && !shownAlwaysLocationHelp) {
                promptAlwaysLocation();
            }
        }
        buildRows();
    }

    private void promptAlwaysLocation() {
        shownAlwaysLocationHelp = true;
        new AlertDialog.Builder(this)
                .setTitle("Set location to \"Allow all the time\"")
                .setMessage("To record the places you visit continuously — even when AWARE isn't open — " +
                        "set this app's Location permission to \"Allow all the time\". With only " +
                        "\"While using the app\", location is recorded just while AWARE is open, so the data " +
                        "will be incomplete.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Accessibility grants happen in the Settings app, not this Activity, so there's no
        // onRequestPermissionsResult callback for them — re-check on every return to this screen,
        // attributing the enabled service only to the row whose Enable button was actually tapped.
        if (pendingAccessibilityKey != null && SensorCollection.isAccessibilityServiceEnabled(getApplicationContext())) {
            consentedKeys.add(pendingAccessibilityKey);
            pendingAccessibilityKey = null;
        }
        if (items != null) buildRows();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_CONSENTED_KEYS, new ArrayList<>(consentedKeys));
        outState.putString(STATE_PENDING_ACCESSIBILITY_KEY, pendingAccessibilityKey);
        outState.putBoolean(STATE_SHOWN_RESTRICTED_HELP, shownRestrictedSettingsHelp);
        outState.putBoolean(STATE_SHOWN_ALWAYS_LOCATION_HELP, shownAlwaysLocationHelp);
    }

    private void restoreInstanceState(Bundle state) {
        ArrayList<String> saved = state.getStringArrayList(STATE_CONSENTED_KEYS);
        if (saved != null) consentedKeys.addAll(saved);
        pendingAccessibilityKey = state.getString(STATE_PENDING_ACCESSIBILITY_KEY);
        shownRestrictedSettingsHelp = state.getBoolean(STATE_SHOWN_RESTRICTED_HELP);
        shownAlwaysLocationHelp = state.getBoolean(STATE_SHOWN_ALWAYS_LOCATION_HELP);
    }

    /**
     * Confirm the participant's choices and apply them. Produces the final set of sensors to keep
     * OFF and hands it to {@link ApplySettingsAsync}:
     *   1. begin with the sensors already declined (persisted);
     *   2. for each row shown, drop it from that set if the participant enabled it, or add it if they
     *      left it off;
     *   3. log the enabled/declined lists to the researcher-visible audit trail, then apply.
     * On a fresh join the starting set is empty, so this is simply "everything not enabled is off"
     * (including tapping Continue with nothing enabled). In update mode only the newly-added rows are
     * shown, so earlier declines not on screen are carried through untouched.
     */
    private void onContinue() {
        Set<String> declined = new HashSet<>();
        for (String key : Aware.getSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS).split(",")) {
            if (key.trim().length() > 0) declined.add(key.trim());
        }
        for (ConsentItem item : items) {
            if (consentedKeys.contains(item.key)) {
                declined.removeAll(Arrays.asList(item.statusSettings));
            } else {
                declined.addAll(Arrays.asList(item.statusSettings));
            }
        }
        Aware.logStudyCompliance(getApplicationContext(),
                (updateMode ? "consent given (study update): " : "consent given: ")
                        + SensorCollection.consentStateSummary(studyConfigs, declined));
        applyAndFinish(declined);
    }

    private void applyAndFinish(Set<String> declinedSettings) {
        new ApplySettingsAsync(declinedSettings).execute();
    }

    private void goToMainUi() {
        Intent mainUI = new Intent(getApplicationContext(), Aware_Client.class);
        mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(mainUI);
        finish();
    }

    /** The URL of the study the device is currently enrolled in, or "" if none. */
    private String activeStudyUrl() {
        String url = "";
        Cursor study = Aware.getActiveStudy(getApplicationContext());
        if (study != null && study.moveToFirst()) {
            url = study.getString(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_URL));
        }
        if (study != null && !study.isClosed()) study.close();
        return url;
    }

    /**
     * Leave this screen without agreeing (the "Don't join" / "Not now" button and the Back button).
     * Fresh join: the participant is enrolled but hasn't consented, so undo the enrolment — mark the
     * study row exited, which makes {@link Aware#isStudy} false — and return to the app not enrolled.
     * This is what prevents backing out from leaving a half-joined study the config sync would later
     * turn into a full, unconsented enable. Update mode: the participant is already a member and only
     * skipped the researcher's added sensors, so nothing is rolled back — the added sensors stay off
     * and they go back to the app. Either way the decision is logged to the audit trail.
     */
    private void cancelJoin() {
        if (updateMode) {
            Aware.logStudyCompliance(getApplicationContext(), "study update: added sensors left off");
            goToMainUi();
            return;
        }

        Aware.logStudyCompliance(getApplicationContext(), "consent declined — did not join");

        Cursor study = Aware.getStudy(getApplicationContext(), studyUrl);
        if (study != null && study.moveToFirst()) {
            long id = study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_ID));
            ContentValues values = new ContentValues();
            values.put(Aware_Provider.Aware_Studies.STUDY_EXIT, System.currentTimeMillis());
            getContentResolver().update(Aware_Provider.Aware_Studies.CONTENT_URI, values,
                    Aware_Provider.Aware_Studies.STUDY_ID + "=" + id, null);
        }
        if (study != null && !study.isClosed()) study.close();

        goToMainUi();
    }

    @Override
    public void onBackPressed() {
        cancelJoin();
    }

    private class ApplySettingsAsync extends AsyncTask<Void, Void, Void> {
        private final Set<String> declinedSettings;
        private ProgressDialog mLoading;

        ApplySettingsAsync(Set<String> declinedSettings) {
            this.declinedSettings = declinedSettings;
        }

        @Override
        protected void onPreExecute() {
            mLoading = new ProgressDialog(SensorConsentActivity.this);
            mLoading.setMessage("Joining study, please wait.");
            mLoading.setCancelable(false);
            mLoading.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Persist the declined set BEFORE applying: applySettings preserves this across its
            // internal reset and forces these sensors off, and the config sync's drift check reads
            // it to keep them off — so the decline survives past the first poll.
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_DECLINED_SENSORS,
                    TextUtils.join(",", declinedSettings));
            StudyUtils.applySettings(getApplicationContext(), studyUrl, studyConfigs, false, inputPassword, declinedSettings);
            // Record what was agreed to (study + its promptable-sensor set). applySettings preserves
            // it across its internal reset; quitting the study clears it via Aware.reset(), which is
            // what forces the consent screen to show again on a re-join.
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STUDY_CONSENT_RECORD,
                    SensorCollection.consentFingerprint(studyUrl, studyConfigs));
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            mLoading.dismiss();
            Intent mainUI = new Intent(getApplicationContext(), Aware_Client.class);
            mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(mainUI);
            finish();
        }
    }
}
