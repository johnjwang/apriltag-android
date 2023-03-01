package edu.umich.eecs.april.apriltag;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.media.Image;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */

public class ApriltagDetectorActivity extends AppCompatActivity {
    private static final String TAG = "AprilTag";
    private DetectionThread mDetectionThread;
    private CameraPreviewThread mCameraPreviewThread;

    private ExecutorService detectorExecutor;
    private TextureView tagView;
    private OutputTransform previewViewTransform;


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

    private void renderDetection(ApriltagDetection detection, Canvas canvas,
                                 CoordinateTransform coordinateTransform) {
        Paint fillPaint = new Paint();
        fillPaint.setColor(Color.GREEN);
        fillPaint.setAlpha(128);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint borderPaint = new Paint();
        final int[] borderColors = new int[]{Color.GREEN, Color.WHITE, Color.WHITE, Color.RED};
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(10);

        double[] points = detection.p;
        if (points == null || points.length != 8) {
            Log.w(TAG, "invalid detection coordinates");
            return;
        }

        // Convert detection points to canvas points
        float[] xPointsCanvas = new float[4];
        float[] yPointsCanvas = new float[4];
        for (int i = 0; i < 4; i++) {
            PointF point = new PointF((float)points[2*i], (float)points[2*i + 1]);
            coordinateTransform.mapPoint(point);
            xPointsCanvas[i] = point.x;
            yPointsCanvas[i] = point.y;
        }

        // Render filled outline of detections
        Path fillPath = new Path();
        for (int i = 0; i < 4; i++) {
            if (i == 0) {
                fillPath.moveTo(xPointsCanvas[i], yPointsCanvas[i]);
            } else {
                fillPath.lineTo(xPointsCanvas[i], yPointsCanvas[i]);
            }
        }
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // Render stroke outline of detections
        int colorIndex = 0;
        for (int i = 0; i < 4; i++) {
            Path borderPath = new Path();
            borderPaint.setColor(borderColors[colorIndex++ % borderColors.length]);

            borderPath.moveTo(xPointsCanvas[i], yPointsCanvas[i]);
            borderPath.lineTo(xPointsCanvas[(i + 1) % 4], yPointsCanvas[(i + 1) % 4]);
            canvas.drawPath(borderPath, borderPaint);
        }

        // Render tag ID in the center of the detection box
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(100);
        String tagId = String.valueOf(detection.id);
        float textWidth = textPaint.measureText(tagId);
        float textHeight = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        PointF textCenter = new PointF((float)detection.c[0], (float)detection.c[1]);
        coordinateTransform.mapPoint(textCenter);
        float textX = (float)(textCenter.x - textWidth / 2);
        float textY = (float)(textCenter.y + textHeight / 2 - textPaint.getFontMetrics().descent);
        canvas.drawText(tagId, textX, textY, textPaint);
    }

    private void renderDetections(ArrayList<ApriltagDetection> detections, CoordinateTransform coordinateTransform) {
        Canvas canvas = tagView.lockCanvas();
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            for (ApriltagDetection detection : detections) {
                renderDetection(detection, canvas, coordinateTransform);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rendering detections: " + e.getMessage());
        } finally {
            if (canvas != null) {
                tagView.unlockCanvasAndPost(canvas);
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            ProcessCameraProvider cameraProvider;
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException ex) {
                Log.e(TAG, "Error getting camera provider", ex);
                return;
            }
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

            // Configure preview use case
            Preview preview = new Preview.Builder().build();
            PreviewView previewView = findViewById(R.id.viewFinder);
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Configure image analysis use case
            // Default image size is 640x480. This seems to be fast, suggesting the downsampling
            // is hardware-accelerated.
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            imageAnalysis.setAnalyzer(detectorExecutor, new ImageAnalysis.Analyzer() {
                @Override
                public void analyze(@NonNull ImageProxy image) {
                    long start = System.currentTimeMillis();
                    ArrayList<ApriltagDetection> detections =
                            ApriltagNative.apriltag_detect_yuv(
                                    image.getPlanes()[0].getBuffer(),
                                    image.getWidth(), image.getHeight(),
                                    image.getPlanes()[0].getRowStride());
                    long end = System.currentTimeMillis();
                    Log.i(TAG, String.format("Image size %dx%d, %d tags detected in %d ms",
                            image.getWidth(), image.getHeight(), detections.size(), end - start));

                    OutputTransform source =
                            new ImageProxyTransformFactory().getOutputTransform(image);

                    if (previewViewTransform != null) {
                        CoordinateTransform coordinateTransform =
                                new CoordinateTransform(source, previewViewTransform);
                        renderDetections(detections, coordinateTransform);
                    } else if (previewView.isLaidOut()) {
                        // TODO(john): There must be a cleaner way of doing this...
                        previewView.post(() -> {
                            previewViewTransform = previewView.getOutputTransform();
                        });
                    }

                    image.close();
                }
            });

            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(ApriltagDetectorActivity.this, cameraSelector,
                        imageAnalysis, preview);
            } catch (Exception ex) {
                Log.e(TAG, "Error binding use case", ex);
            }
        }, getMainExecutor());
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Add toolbar/actionbar
        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        tagView = findViewById(R.id.tagView);

        // Make the screen stay awake
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create a thread for the detector
        detectorExecutor = Executors.newSingleThreadExecutor();

        // Ensure we have permission to use the camera (Permission Requesting for Android 6.0/SDK 23 and higher)
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Assume user knows enough about the app to know why we need the camera, just ask for permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    MY_PERMISSIONS_REQUEST_CAMERA);
        } else {
            this.has_camera_permissions = 1;
            startCamera();
        }
    }

    /**
     * Release the camera when the application is exited
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopThreads();
        detectorExecutor.shutdown();
        Log.i(TAG, "Finished destroying.");
    }

    /**
     * Release the camera when application focus is lost
     */
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

    /**
     * (Re-)initialize the camera
     */
    /*
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
        boolean diagnosticsEnabled = sharedPreferences.getBoolean("diagnostics_enabled", false);
        String tagFamily = sharedPreferences.getString("tag_family_list", "tag36h11");
        Log.i(TAG, String.format("decimation: %f | sigma: %f | nthreads: %d | tagFamily: %s",
                decimation, sigma, nthreads, tagFamily));
        ApriltagNative.apriltag_init(tagFamily, 2, decimation, sigma, nthreads);

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
//*/
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
