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
import android.widget.FrameLayout;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "AprilTag";
    private Camera camera;
    private TagView tagView;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        SurfaceView overlayView = new SurfaceView(this);
        tagView = new TagView(this, overlayView.getHolder());
        FrameLayout layout = (FrameLayout)findViewById(R.id.tag_view);
        layout.addView(overlayView);
        layout.addView(tagView);

        // Add toolbar/actionbar
        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);
    }

    /** Release the camera when application focus is lost */
    protected void onPause() {
        super.onPause();
        tagView.onPause();

        //Log.i("CameraActivity", "Pause");
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

        //Log.i("CameraActivity", "Resume");

        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.d("CameraActivity", "Couldn't open camera: " + e.getMessage());
            return;
        }
        //camera.setDisplayOrientation(90);
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        Log.i(TAG, "camera rotation: " + info.orientation);
        Camera.Parameters params = camera.getParameters();
        for (Camera.Size s : params.getSupportedPreviewSizes())
            Log.i(TAG, "" + s.width + "x" + s.height);
        //params.setPreviewSize(1080, 1920);
        //camera.setParameters(params);
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
            case R.id.help:
                return true;

            case R.id.settings:
                Intent intent = new Intent();
                intent.setClassName(this, "edu.umich.eecs.april.apriltag.SettingsActivity");
                startActivity(intent);

                // TODO: Move the below block anywhere the variables are needed
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                boolean refine_edges = sharedPreferences.getBoolean("refine_edges_switch", false);
                boolean refine_decode = sharedPreferences.getBoolean("refine_decode_switch", false);
                boolean refine_pose = sharedPreferences.getBoolean("refine_pose_switch", false);
                float decimation = Float.parseFloat(sharedPreferences.getString("decimation_value", "2"));
                float sigma = Float.parseFloat(sharedPreferences.getString("sigma_value", "0"));
                int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "1"));
                int tag_family = Integer.parseInt(sharedPreferences.getString("tag_family_list", "3611"));
                Log.e("Pref", String.format("refine_edges: %s | refine_decode: %s | refine_pose: %s | decimation: %f | sigma: %f | nthreads: %d | tag_family: %d",
                        Boolean.toString(refine_edges), Boolean.toString(refine_decode), Boolean.toString(refine_pose),
                        decimation, sigma, nthreads, tag_family));
                // TODO: Move the above block anywhere the variables are needed, remove the error logging
                return true;

            case R.id.about:
                return true;

            case R.id.screenshot:
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
