package edu.umich.eecs.april.apriltag;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DetectionThread extends Thread {

    private static final String TAG = "DetectionThread";
    private TextureView mTextureView;
    private boolean mIsAvailable = false;

    private final TextView mFpsTextView;
    private long mLastRender = System.currentTimeMillis();
    private int mFrameCount = 0;
    private Camera.Size mCameraSize;
    private final int MAX_FRAME_QUEUE_SIZE = 10;

    private BlockingQueue<byte[]> mCameraFrameQueue = new LinkedBlockingQueue<>();

    public DetectionThread(TextureView textureView, TextView fpsTextView) {
        mTextureView = textureView;
        mFpsTextView = fpsTextView;
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                mIsAvailable = true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // Do nothing
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                mIsAvailable = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Do nothing
            }
        });
    }

    public void destroy() {
        mCameraFrameQueue.clear();
        mCameraFrameQueue = null;
    }

    public void enqueueCameraFrame(byte[] data, Camera.Size cameraSize) throws InterruptedException {
        mCameraSize = cameraSize; // NB: Should pair these with the data or reset better

        if (mCameraFrameQueue == null) {
            Log.w(TAG, "Camera frame queue is null, skipping frame");
            return;
        } else if (mCameraFrameQueue.size() < MAX_FRAME_QUEUE_SIZE) {
            mCameraFrameQueue.put(data);
        } else {
            Log.w(TAG, "Camera frame queue is full, dropping frame");
        }
        Log.i(TAG, "Buffer length: " + mCameraFrameQueue.size());
    }

    private void updateFps() {
        long now = System.currentTimeMillis();
        long diff = now - mLastRender;
        mFrameCount++;
        if (diff >= 1000) {
            final double fps = 1000.0 / diff * mFrameCount;
            mFpsTextView.post(new Runnable() {
                @Override
                public void run() {
                    mFpsTextView.setText(String.format("%.2f fps", fps));
                }
            });
            mLastRender = now;
            mFrameCount = 0;
        }
    }

    private void processCameraFrame(byte[] data, Camera.Size cameraSize)  {
        try {
            long start = System.currentTimeMillis();
            Log.i(TAG, cameraSize.width + " x " + cameraSize.height);
            ArrayList<ApriltagDetection> detections = ApriltagNative.apriltag_detect_yuv(data, cameraSize.width, cameraSize.height);
            //ArrayList<ApriltagDetection> detections = null;
            int numDetections = detections != null ? detections.size() : -1;
            Log.i(TAG, String.valueOf(detections));
            long diff = (System.currentTimeMillis() - start);
            Log.i(TAG, "detecting " + numDetections + " tags took " + diff + " ms (" + String.format("%.1f", 1000.0 / diff) + " Hz)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void initialize() {
        Log.i(TAG, "Detection thread initialize");
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            updateFps();

            byte[] data;
            try {
                data = mCameraFrameQueue.take();
            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupted while waiting for camera frame: " + e.getMessage());
                break;
            }

            processCameraFrame(data, mCameraSize);

            if (mIsAvailable) {
                Canvas canvas = mTextureView.lockCanvas();
                try {
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR);

                    // Decode the camera frame data into a Bitmap object
                    YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, mCameraSize.width, mCameraSize.height, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    yuvImage.compressToJpeg(new Rect(0, 0, mCameraSize.width, mCameraSize.height), 80, baos);
                    byte[] jpegData = baos.toByteArray();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

                    // Draw the Bitmap on the Canvas of the TextureView
                    canvas.drawBitmap(bitmap, 0, 0, null);

                    /*
                    // Draw a green triangle in the center of the canvas
                    Paint paint = new Paint();
                    paint.setColor(Color.GREEN);
                    paint.setStyle(Paint.Style.FILL);
                    int width = canvas.getWidth();
                    int height = canvas.getHeight();
                    Path path = new Path();
                    path.moveTo(width / 2, height / 3);
                    path.lineTo(width / 3, height * 2 / 3);
                    path.lineTo(width * 2 / 3, height * 2 / 3);
                    path.lineTo(width / 2, height / 3);
                    canvas.drawPath(path, paint);
                    */


                    // Render the detection results to the canvas
                    //renderDetections(canvas);
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering to surface: " + e.getMessage());
                } finally {
                    if (canvas != null) {
                        mTextureView.unlockCanvasAndPost(canvas);
                    }
                }
            }
        }
    }

    private void renderDetections(Canvas canvas) {
        // TODO: Implement rendering of detection results to the canvas
        // This may involve using the TagView class to render the tags and tag information to the canvas
    }
}