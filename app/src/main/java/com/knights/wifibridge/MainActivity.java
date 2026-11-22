package com.knights.wifibridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST = 1001;
    private static final String OUTPUT_NAME = "knights_wifi_scan.json";

    private WifiManager wifi;
    private TextView output;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                boolean updated = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false
                );

                if (updated) {
                    showResults();
                } else {
                    output.setText(
                        "Scan did not update. Android may be limiting scans."
                    );
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        wifi = (WifiManager) getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        Button scanButton = new Button(this);
        scanButton.setText("Scan Wi-Fi networks");

        output = new TextView(this);
        output.setText("Knights Wi-Fi Bridge ready.");

        layout.addView(scanButton);
        layout.addView(output);
        setContentView(layout);

        scanButton.setOnClickListener(v -> requestScan());
    }

    private void requestScan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }

        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }

        LocationManager location = (LocationManager)
            getSystemService(Context.LOCATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 28 &&
                (location == null || !location.isLocationEnabled())) {
            output.setText("Enable Android Location, then scan again.");
            return;
        }

        if (wifi == null || !wifi.isWifiEnabled()) {
            output.setText("Enable Wi-Fi, then scan again.");
            return;
        }

        try {
            boolean started = wifi.startScan();

            if (started) {
                output.setText("Scanning... waiting for Android results.");
            } else {
                output.setText(
                    "Scan request was restricted by Android. Try again later."
                );
            }
        } catch (SecurityException e) {
            output.setText("Permission error: " + e.getMessage());
        }
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT <= 28) {
            requestPermissions(
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                PERMISSION_REQUEST
            );
        } else {
            requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                PERMISSION_REQUEST
            );
        }
    }

    private void showResults() {
        try {
            List<ScanResult> results = wifi.getScanResults();
            JSONArray networks = new JSONArray();

            for (ScanResult result : results) {
                JSONObject item = new JSONObject();
                item.put("ssid", result.SSID == null ? "" : result.SSID);
                item.put("bssid", result.BSSID == null ? "" : result.BSSID);
                item.put("rssi", result.level);
                item.put("frequency_mhz", result.frequency);
                item.put(
                    "capabilities",
                    result.capabilities == null ? "" : result.capabilities
                );
                networks.put(item);
            }

            // Knights reader expects a JSON array at the file root.
            String json = networks.toString(2);
            saveJsonToDownloads(json);

            output.setText(
                json + "\n\nSaved: Download/" + OUTPUT_NAME
            );

        } catch (Exception e) {
            output.setText("Result error: " + e.getMessage());
        }
    }

    private void saveJsonToDownloads(String json) throws Exception {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver resolver = getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri fileUri = null;

            String selection =
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                MediaStore.MediaColumns.RELATIVE_PATH + "=?";

            String[] selectionArgs = {
                OUTPUT_NAME,
                Environment.DIRECTORY_DOWNLOADS + "/"
            };

            try (Cursor cursor = resolver.query(
                    collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    selection,
                    selectionArgs,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    fileUri = Uri.withAppendedPath(collection, String.valueOf(id));
                }
            }

            boolean isNew = fileUri == null;

            if (isNew) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, OUTPUT_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                );
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                fileUri = resolver.insert(collection, values);

                if (fileUri == null) {
                    throw new Exception("Could not create Downloads file");
                }
            }

            try (OutputStream stream = resolver.openOutputStream(fileUri, "wt")) {
                if (stream == null) {
                    throw new Exception("Could not open Downloads file");
                }
                stream.write(data);
                stream.flush();
            }

            if (isNew) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(fileUri, ready, null, null);
            }

        } else {
            File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            );

            if (!downloads.exists() && !downloads.mkdirs()) {
                throw new Exception("Could not create Downloads directory");
            }

            File file = new File(downloads, OUTPUT_NAME);

            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(data);
                stream.flush();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }

            if (granted) {
                requestScan();
            } else {
                output.setText("Required permission denied.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION
            );

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(scanReceiver, filter);
            }

            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }
}
