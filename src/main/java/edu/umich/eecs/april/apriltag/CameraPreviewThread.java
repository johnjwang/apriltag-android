package edu.umich.eecs.april.apriltag;

import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**

 This class is responsible for managing the camera and live preview. It also enqueues image previews
 in the DetectionThread for asynchronous Apriltag detection on a separate view.
 <p>
 This class also displays a text view with the current frames per second (FPS) of the camera thread.
 </p>
 */
public class CameraPreviewThread extends Thread {
    private static final String TAG = "CameraPreviewThread";

    private final SurfaceHolder mSurfaceHolder;
    private final DetectionThread mDetectionThread;
    private Camera mCamera;
    private final TextView mFpsTextView;

    private long mLastRender = System.currentTimeMillis();
    private int mFrameCount = 0;
    private SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera.setPreviewDisplay(holder);

                // Set the preview callback to receive camera frames asynchronously
                mCamera.setPreviewCallback((data, camera) -> {
                    try {
                        mDetectionThread.enqueueCameraFrame(data, camera.getParameters().getPreviewSize());
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while enqueuing camera frame: " + e.getMessage());
                    }

                    previewFpsCallback();
                });

                mCamera.startPreview();
            } catch (IOException e) {
                Log.e(TAG, "Error setting camera preview: " + e.getMessage());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // Do nothing
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Do nothing
        }
    };

    public CameraPreviewThread(SurfaceHolder surfaceHolder, DetectionThread detectionThread, TextView fpsTextView) {
        mSurfaceHolder = surfaceHolder;
        mFpsTextView = fpsTextView;
        mDetectionThread = detectionThread;

        mSurfaceHolder.addCallback(mCallback);
    }

    public void destroy() {
        mSurfaceHolder.removeCallback(mCallback);
        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }

    private void previewFpsCallback() {
        long now = System.currentTimeMillis();
        long diff = now - mLastRender;
        mFrameCount++;
        if (diff >= 1000) {
            double fps = 1000.0 / diff * mFrameCount;
            mFpsTextView.setText(String.format("%.2f fps Camera", fps));
            mLastRender = now;
            mFrameCount = 0;
        }
    }

    @Override
    public void run() {
        // Stop the previous camera preview
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                Log.i(TAG, "Camera stop");
            } catch (Exception e) {
                Log.e(TAG, "Unable to stop camera: " + e);
            }
        }

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
            mCamera.startPreview();
            Log.i(TAG, "Camera preview start");
        } catch (IOException e) {
            Log.e(TAG, "Error setting camera preview: " + e.getMessage());
        }
    }

    protected void initialize() {
        int camidx = 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i += 1) {
            Camera.getCameraInfo(i, info);
            int desiredFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
            if (info.facing == desiredFacing) {
                camidx = i;
                break;
            }
        }

        try {
            mCamera = Camera.open(camidx);
            Log.i(TAG, "using camera " + camidx);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open camera: " + e.getMessage());
            return;
        }

        Camera.getCameraInfo(camidx, info);
        setCameraParameters(mCamera, info);
    }

    private void setCameraParameters(Camera camera, Camera.CameraInfo info)
    {
        Camera.Parameters parameters = camera.getParameters();

        List<Camera.Size> sizeList = camera.getParameters().getSupportedPreviewSizes();
        Camera.Size bestSize = null;
        for (int i = 0; i < sizeList.size(); i++) {
            Camera.Size candidateSize = sizeList.get(i);
            Log.i(TAG, " " + candidateSize.width + "x" + candidateSize.height + " (" + candidateSize.width * candidateSize.height + " area)");
            if (bestSize == null || (candidateSize.width * candidateSize.height) > (bestSize.width * bestSize.height)) {
                if (candidateSize.width != candidateSize.height) {
                    bestSize = candidateSize;
                }
            }
        }
        parameters.setPreviewSize(bestSize.width, bestSize.height);
        Log.i(TAG, "Setting " + bestSize.width + " x " + bestSize.height);

        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            Log.i(TAG, "Setting focus mode for continuous video");
        } else {
            Log.i(TAG, "Focus mode for continuous video not supported, skipping");
        }

        int[] desiredFpsRange = new int[] { 15000, 15000 };
        List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
        Log.i(TAG, "Supported FPS ranges:");
        for (int[] range : fpsRanges) {
            Log.i(TAG, "  [" + range[0] + ", " + range[1] + "]");
            if (range[0] == desiredFpsRange[0] && range[1] == desiredFpsRange[1]) {
                parameters.setPreviewFpsRange(range[0], range[1]);
                Log.i(TAG, "Setting FPS range [" + range[0] + ", " + range[1] + "]");
                break;
            }
        }

        camera.setDisplayOrientation(info.orientation % 360);

        camera.setParameters(parameters);
    }
}