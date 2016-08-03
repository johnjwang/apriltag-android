package edu.umich.eecs.april.apriltag;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class CameraActivity extends Activity {
    private Camera camera;
    private TagView view;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        view = new TagView(this);
        FrameLayout preview = (FrameLayout)findViewById(R.id.tag_view);
        preview.addView(view);
    }

    /** Release the camera when application focus is lost */
    protected void onPause() {
        super.onPause();

        //Log.i("CameraActivity", "Pause");

        if (camera != null) {
            view.setCamera(null);
            camera.release();
            camera = null;
        }
    }

    /** (Re-)initialize the camera */
    protected void onResume() {
        super.onResume();

        //Log.i("CameraActivity", "Resume");

        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.d("CameraActivity", "Couldn't open camera: " + e.getMessage());
            return;
        }
        //camera.setDisplayOrientation(90);
        view.setCamera(camera);
    }
}
