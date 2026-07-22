package com.aware.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.aware.Aware;

import java.util.ArrayList;

/**
 * This is an invisible activity used to request the needed permissions from the user from API 23 onwards.
 * Created by denzil on 22/10/15.
 */
public class PermissionsHandler extends Activity {

    private String TAG = "PermissionsHandler";

    /**
     * Extra ArrayList<String> with Manifest.permission that require explicit users' permission on Android API 23+
     */
    public static final String EXTRA_REQUIRED_PERMISSIONS = "required_permissions";

    /**
     * Class name of the Activity redirect, e.g., Class.getClass().getName();
     */
    public static final String EXTRA_REDIRECT_ACTIVITY = "redirect_activity";

    /**
     * Class name of the Service redirect, e.g., Class.getClass().getName();
     */
    public static final String EXTRA_REDIRECT_SERVICE = "redirect_service";

    /**
     * Used on redirect service to know when permissions have been accepted
     */
    public static final String ACTION_AWARE_PERMISSIONS_CHECK = "ACTION_AWARE_PERMISSIONS_CHECK";

    /**
     * The request code for the permissions
     */
    public static final int RC_PERMISSIONS = 112;

    private Intent redirect_activity, redirect_service;

    private ArrayList<String> permissionsNeeded = new ArrayList<>();
    private int currentIndex = 0;
    private boolean anyDenied = false;
    private boolean sequenceStarted = false;
    private AlertDialog rationaleDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("Permissions", "Permissions request for " + getPackageName());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Returning from a system permission dialog re-runs onResume; don't restart the sequence.
        if (sequenceStarted) return;

        if (getIntent() != null && getIntent().getExtras() != null && getIntent().getSerializableExtra(EXTRA_REQUIRED_PERMISSIONS) != null) {
            permissionsNeeded = (ArrayList<String>) getIntent().getSerializableExtra(EXTRA_REQUIRED_PERMISSIONS);

            if (getIntent().hasExtra(EXTRA_REDIRECT_ACTIVITY)) {
                redirect_activity = new Intent();
                String[] component = getIntent().getStringExtra(EXTRA_REDIRECT_ACTIVITY).split("/");
                redirect_activity.setComponent(new ComponentName(component[0], component[1]));
                redirect_activity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else if (getIntent().hasExtra(EXTRA_REDIRECT_SERVICE)) {
                redirect_service = new Intent();
                redirect_service.setAction(ACTION_AWARE_PERMISSIONS_CHECK);
                String[] component = getIntent().getStringExtra(EXTRA_REDIRECT_SERVICE).split("/");
                redirect_service.setComponent(new ComponentName(component[0], component[1]));
            }

            sequenceStarted = true;
            currentIndex = 0;
            requestNextPermission();
        } else {
            Intent activity = new Intent();
            setResult(Activity.RESULT_OK, activity);
            finish();
        }
    }

    /**
     * Requests the next not-yet-granted permission on its own, after a short rationale. Already-granted
     * permissions are skipped. When none remain, hands back to the caller via {@link #finishWithResult()}.
     */
    private void requestNextPermission() {
        while (currentIndex < permissionsNeeded.size()
                && ContextCompat.checkSelfPermission(this, permissionsNeeded.get(currentIndex)) == PackageManager.PERMISSION_GRANTED) {
            currentIndex++;
        }

        if (currentIndex >= permissionsNeeded.size()) {
            finishWithResult();
            return;
        }

        final String permission = permissionsNeeded.get(currentIndex);
        rationaleDialog = new AlertDialog.Builder(this)
                .setTitle(permissionTitle(permission))
                .setMessage(permissionRationale(permission))
                .setCancelable(false)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ActivityCompat.requestPermissions(PermissionsHandler.this, new String[]{permission}, RC_PERMISSIONS);
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        anyDenied = true;
                        Log.d(Aware.TAG, permission + " was skipped");
                        currentIndex++;
                        requestNextPermission();
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RC_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    anyDenied = true;
                    Log.d(Aware.TAG, permissions[i] + " was not granted");
                } else {
                    Log.d(Aware.TAG, permissions[i] + " was granted");
                }
            }
            currentIndex++;
            requestNextPermission();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets the activity result once every permission has been handled and finishes; the redirect back
     * to the caller (activity or service) is performed in {@link #onDestroy()}.
     */
    private void finishWithResult() {
        int result = anyDenied ? Activity.RESULT_CANCELED : Activity.RESULT_OK;
        setResult(result, redirect_activity != null ? redirect_activity : new Intent());
        finish();
    }

    private static String permissionTitle(String permission) {
        return "Allow " + humanLabel(permission);
    }

    private static String permissionRationale(String permission) {
        return "AWARE needs the " + humanLabel(permission) + " permission to work as this study expects. "
                + "Allow it on the next screen, or choose Not now to skip it.";
    }

    /** A short, human-readable name for a permission, for use in the rationale prompt. */
    private static String humanLabel(String permission) {
        if (permission == null) return "requested";
        switch (permission) {
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_COARSE_LOCATION":
                return "Location";
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
                return "Background location";
            case "android.permission.READ_PHONE_STATE":
                return "Phone";
            case "android.permission.READ_CALL_LOG":
                return "Call log";
            case "android.permission.READ_SMS":
                return "SMS";
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return "Storage";
            case "android.permission.GET_ACCOUNTS":
                return "Accounts";
            case "android.permission.BLUETOOTH_SCAN":
            case "android.permission.BLUETOOTH_CONNECT":
                return "Nearby devices";
            case "android.permission.POST_NOTIFICATIONS":
                return "Notifications";
            case "android.permission.ACTIVITY_RECOGNITION":
                return "Physical activity";
            case "android.permission.RECORD_AUDIO":
                return "Microphone";
            case "android.permission.CAMERA":
                return "Camera";
            default:
                String tail = permission.substring(permission.lastIndexOf('.') + 1);
                return tail.replace('_', ' ').toLowerCase();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rationaleDialog != null && rationaleDialog.isShowing()) rationaleDialog.dismiss();
        if (redirect_service != null) {
            Log.d(TAG, "Redirecting to Service: " + redirect_service.getComponent().toString());
            redirect_service.setAction(ACTION_AWARE_PERMISSIONS_CHECK);
            startService(redirect_service);
        }
        if (redirect_activity != null) {
            Log.d(TAG, "Redirecting to Activity: " + redirect_activity.getComponent().toString());
            setResult(Activity.RESULT_OK, redirect_activity);
            startActivity(redirect_activity);
        }
        Log.d("Permissions", "Handled permissions for " + getPackageName());
    }
}
