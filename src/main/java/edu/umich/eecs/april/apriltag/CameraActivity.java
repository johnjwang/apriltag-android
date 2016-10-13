package edu.umich.eecs.april.apriltag;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "AprilTag";
    private Camera camera;
    private TagView tagView;

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

        SurfaceView overlayView = new SurfaceView(this);
        tagView = new TagView(this, overlayView.getHolder());
        FrameLayout layout = (FrameLayout) findViewById(R.id.tag_view);
        //layout.addView(overlayView); // TODO: Not needed?
        layout.addView(tagView);

        // Add toolbar/actionbar
        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        // Make the screen stay awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Release the camera when application focus is lost */
    protected void onPause() {
        super.onPause();
        tagView.onPause();

        //Log.i(TAG, "Pause");
        // TODO move camera management to TagView class

        if (camera != null) {
            tagView.setCamera(null);
            camera.release();
            camera = null;
        }
    }

    /** (Re-)initialize the camera */
    protected void onResume() {
        super.onResume();
        tagView.onResume();

        //Log.i(TAG, "Resume");

        // Re-initialize the Apriltag detector as settings may have changed
        verifyPreferences();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        double decimation = Double.parseDouble(sharedPreferences.getString("decimation_list", "1"));
        double sigma = Double.parseDouble(sharedPreferences.getString("sigma_value", "0"));
        int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "0"));
        String tagFamily = sharedPreferences.getString("tag_family_list", "tag36h11");
        boolean useRear = sharedPreferences.getBoolean("device_settings_rear_camera", true);
        Log.i(TAG, String.format("decimation: %f | sigma: %f | nthreads: %d | tagFamily: %s | useRear: %b",
                decimation, sigma, nthreads, tagFamily, useRear));
        ApriltagNative.apriltag_init(tagFamily, 2, decimation, sigma, nthreads);

        // Find the camera index of front or rear camera
        int camidx = 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i += 1) {
            Camera.getCameraInfo(i, info);
            int desiredOrientation = useRear ? Camera.CameraInfo.CAMERA_FACING_BACK :
                    Camera.CameraInfo.CAMERA_FACING_FRONT;
            if (info.orientation == desiredOrientation) {
                camidx = i;
                break;
            }
        }

        Camera.getCameraInfo(camidx, info);
        Log.i(TAG, "using camera " + camidx);
        Log.i(TAG, "camera rotation: " + info.orientation);

        try {
            camera = Camera.open(camidx);
        } catch (Exception e) {
            Log.d(TAG, "Couldn't open camera: " + e.getMessage());
            return;
        }


        /*
        ListView list = (ListView) findViewById(R.id.camera_resolution);
        ArrayList<String> arrayList = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getApplicationContext(), R.layout.main, arrayList);
        list.setAdapter(adapter);
        // TODO: Add these supported resolutions to the listview, above code doesn't quite work
        */
        Log.i(TAG, "supported resolutions:");
        Camera.Parameters params = camera.getParameters();
        for (Camera.Size s : params.getSupportedPreviewSizes()) {
            Log.i(TAG, " " + s.width + "x" + s.height);
        }

        tagView.setCamera(camera);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
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
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
