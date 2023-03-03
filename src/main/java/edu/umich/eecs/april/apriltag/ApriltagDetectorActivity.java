package edu.umich.eecs.april.apriltag;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;


/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */

public class ApriltagDetectorActivity extends AppCompatActivity {
    private static final String TAG = "AprilTag";
    private DetectionThread mDetectionThread;
    private CameraPreviewThread mCameraPreviewThread;

    private static final int MY_PERMISSIONS_REQUEST_CAMERA = 77;
    private int has_camera_permissions = 0;

    private void verifyPreferences() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "0"));
        if (nthreads <= 0) {
            int nproc = Runtime.getRuntime().availableProcessors();
            if (nproc <= 0) {
                nproc = 1;
            }
            Log.i(TAG, "available processors: " + nproc);
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString("nthreads_value", Integer.toString(nproc)).apply();
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Add toolbar/actionbar
        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        // Make the screen stay awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Ensure we have permission to use the camera (Permission Requesting for Android 6.0/SDK 23 and higher)
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Assume user knows enough about the app to know why we need the camera, just ask for permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    MY_PERMISSIONS_REQUEST_CAMERA);
        } else {
            this.has_camera_permissions = 1;
        }
    }

    /** Release the camera when the application is exited */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopThreads();
        Log.i(TAG, "Finished destroying.");
    }

    /** Release the camera when application focus is lost */
    protected void onPause() {
        super.onPause();
        stopThreads();
        Log.i(TAG, "Finished pause.");
    }

    private void stopThreads() {
        if (mCameraPreviewThread != null) {
            mCameraPreviewThread.interrupt();
            mCameraPreviewThread.destroy();
            try {
                mCameraPreviewThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mCameraPreviewThread = null;
        }
        if (mDetectionThread != null) {
            mDetectionThread.interrupt();
            mDetectionThread.destroy();
            try {
                mDetectionThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mDetectionThread = null;
        }
    }

    /** (Re-)initialize the camera */
    protected void onResume() {
        super.onResume();

        // Check permissions
        if (this.has_camera_permissions == 0) {
            Log.w(TAG, "Missing camera permissions.");
            return;
        }

        // DETECTION INIT
        // Re-initialize the Apriltag detector as settings may have changed
        verifyPreferences();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        double decimation = Double.parseDouble(sharedPreferences.getString("decimation_list", "8"));
        double sigma = Double.parseDouble(sharedPreferences.getString("sigma_value", "0"));
        int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "4"));
        int max_hamming_error = Integer.parseInt(sharedPreferences.getString("max_hamming_error", "0"));
        boolean diagnosticsEnabled = sharedPreferences.getBoolean("diagnostics_enabled", false);
        String tagFamily = sharedPreferences.getString("tag_family_list", "tag36h11");
        Log.i(TAG, String.format("decimation: %f | sigma: %f | nthreads: %d | tagFamily: %s",
                decimation, sigma, nthreads, tagFamily));
        ApriltagNative.apriltag_init(tagFamily, max_hamming_error, decimation, sigma, nthreads);

        // DIAGNOSTICS
        findViewById(R.id.detectionFpsTextView).setVisibility(diagnosticsEnabled ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.previewFpsTextView).setVisibility(diagnosticsEnabled ? View.VISIBLE : View.INVISIBLE);
        TextView tagFamilyText = (TextView) findViewById(R.id.tagFamily);
        stylizeText(tagFamilyText);
        tagFamilyText.setText("Tag Family: " + tagFamily.substring(3));

        // THREAD INIT
        // Start the detection process on a separate thread
        TextureView detectionSurface = (TextureView) findViewById(R.id.tagView);
        TextView detectionFpsTextView = (TextView) findViewById(R.id.detectionFpsTextView);
        stylizeText(detectionFpsTextView);
        mDetectionThread = new DetectionThread(detectionSurface, detectionFpsTextView);
        mDetectionThread.initialize();
        mDetectionThread.start();

        // Start the camera preview on a separate thread
        SurfaceView previewSurface = (SurfaceView) findViewById(R.id.surfaceView);
        TextView previewFpsTextView = (TextView) findViewById(R.id.previewFpsTextView);
        stylizeText(previewFpsTextView);
        mCameraPreviewThread = new CameraPreviewThread(previewSurface.getHolder(), mDetectionThread, previewFpsTextView);
        mCameraPreviewThread.initialize();
        mCameraPreviewThread.start();
    }

    private void stylizeText(TextView textView) {
        textView.setTextColor(Color.GREEN);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setTypeface(textView.getTypeface(), Typeface.BOLD);
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.settings:
                verifyPreferences();
                Intent intent = new Intent();
                intent.setClassName(this, "edu.umich.eecs.april.apriltag.SettingsActivity");
                startActivity(intent);
                return true;

            case R.id.reset:
                // Reset all shared preferences to default values
                PreferenceManager.getDefaultSharedPreferences(this).edit().clear().commit();

                // Restart the camera preview
                onPause();
                onResume();

                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_CAMERA: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "App GRANTED camera permissions");

                    // Set flag
                    this.has_camera_permissions = 1;

                    // Restart the camera
                    onPause();
                    onResume();
                } else {
                    Log.i(TAG, "App DENIED camera permissions");
                    this.has_camera_permissions = 0;
                }
                return;
            }
        }
    }
}
