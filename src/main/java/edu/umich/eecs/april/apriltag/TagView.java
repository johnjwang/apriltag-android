package edu.umich.eecs.april.apriltag;

import android.content.Context;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

/**
 * TODO: document your custom view class.
 */
public class TagView extends SurfaceView implements SurfaceHolder.Callback {
    private Camera camera;
    private boolean surfaceValid;

    public TagView(Context context) {
        super(context);

        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        // deprecated setting, but required on Android versions prior to 3.0
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void setCamera(Camera camera)
    {
        if (camera == this.camera)
            return;

        // Stop the previous camera preview
        if (this.camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception e) { }
        }

        // Start the new camera preview
        if (surfaceValid && camera != null) {
            try {
                camera.setPreviewDisplay(getHolder());
                camera.startPreview();
            } catch (IOException e) {
                Log.d("TagView", "Error setting camera preview: " + e.getMessage());
            }
        }
        this.camera = camera;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceValid = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceValid = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (holder.getSurface() == null)
            return;

        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception e) { }

            // TODO Reconfigure camera if viewport changed

            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (IOException e) {
                Log.d("TagView", "Error setting camera preview: " + e.getMessage());
            }
        }
    }
}
