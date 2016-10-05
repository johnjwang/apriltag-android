package edu.umich.eecs.april.apriltag;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.FrameLayout;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class CameraActivity extends Activity {
    private static final String TAG = "AprilTag";
    private Camera camera;
    private TagView tagView;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        SurfaceView overlayView = new SurfaceView(this);
        tagView = new TagView(this, overlayView.getHolder());
        FrameLayout layout = (FrameLayout)findViewById(R.id.tag_view);
        layout.addView(overlayView);
        layout.addView(tagView);
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
        Camera.Parameters params = camera.getParameters();
        for (Camera.Size s : params.getSupportedPreviewSizes())
            Log.i(TAG, "" + s.width + "x" + s.height);
        //params.setPreviewSize(1080, 1920);
        //camera.setParameters(params);
        tagView.setCamera(camera);
    }
}
