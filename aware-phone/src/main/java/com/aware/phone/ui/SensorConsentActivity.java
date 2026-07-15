package com.aware.phone.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.aware.phone.R;
import com.aware.phone.ui.prefs.SensorCollection;
import com.aware.phone.ui.prefs.SensorCollection.ConsentItem;

import java.util.List;

/**
 * Post-join consent screen: lists the sensors this study enabled that need a runtime permission, one
 * row at a time, each with an Enable button (or "Granted ✓"). Tapping Enable requests just that
 * sensor's permission(s), so the OS shows them one sensor at a time instead of many services each
 * firing their own request and stacking.
 */
public class SensorConsentActivity extends AppCompatActivity {

    private LinearLayout list;
    private List<ConsentItem> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor_consent);

        list = findViewById(R.id.consent_list);
        findViewById(R.id.btn_continue).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onContinue();
            }
        });

        items = SensorCollection.enabledConsents(getApplicationContext());
        if (items.isEmpty()) {
            // Nothing to ask for — don't show an empty screen.
            onContinue();
            return;
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

            if (SensorCollection.isGranted(getApplicationContext(), item)) {
                enable.setVisibility(View.GONE);
                granted.setVisibility(View.VISIBLE);
            } else {
                enable.setVisibility(View.VISIBLE);
                granted.setVisibility(View.GONE);
                enable.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (item.needsAccessibility) {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } else {
                            ActivityCompat.requestPermissions(SensorConsentActivity.this, item.permissions, requestCode);
                        }
                    }
                });
            }
            list.addView(row);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Refresh every row so newly granted sensors flip to "Granted ✓".
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Accessibility grants happen in the Settings app, not this Activity, so there's no
        // onRequestPermissionsResult callback for them — re-check on every return to this screen.
        if (items != null) buildRows();
    }

    private void onContinue() {
        // The consent screen owns the final hop into the app after a join.
        Intent mainUI = new Intent(getApplicationContext(), Aware_Client.class);
        mainUI.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(mainUI);
        finish();
    }
}
