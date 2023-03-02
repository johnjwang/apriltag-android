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
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApriltagDetectorActivity extends AppCompatActivity {
    private static final String TAG = "AprilTag";
    private static final int MY_PERMISSIONS_REQUEST_CAMERA = 77;
    private static final int SETTINGS_ACTIVITY_CODE = 1;

    private ExecutorService detectorExecutor;
    private TextureView tagView;
    private TextView detectionFpsTextView;
    private OutputTransform previewViewTransform;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Add toolbar/actionbar
        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        tagView = findViewById(R.id.tagView);
        detectionFpsTextView = (TextView) findViewById(R.id.detectionFpsTextView);

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
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detectorExecutor.shutdown();
    }

    private void applyPreferences() {
        // Re-initialize the Apriltag detector as settings may have changed
        verifyPreferences();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        double decimation = Double.parseDouble(sharedPreferences.getString("decimation_list", "2"));
        double sigma = Double.parseDouble(sharedPreferences.getString("sigma_value", "0"));
        int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "4"));
        boolean diagnosticsEnabled = sharedPreferences.getBoolean("diagnostics_enabled", false);
        String tagFamily = sharedPreferences.getString("tag_family_list", "tag36h11");
        Log.i(TAG, String.format("decimation: %f | sigma: %f | nthreads: %d | tagFamily: %s",
                decimation, sigma, nthreads, tagFamily));
        ApriltagNative.apriltag_init(tagFamily, 2, decimation, sigma, nthreads);

        // Update diagnostics text
        detectionFpsTextView.setVisibility(diagnosticsEnabled ? View.VISIBLE : View.INVISIBLE);
        stylizeText(detectionFpsTextView);

        TextView tagFamilyText = (TextView) findViewById(R.id.tagFamily);
        stylizeText(tagFamilyText);
        tagFamilyText.setText("Tag Family: " + tagFamily.substring(3));
    }

    private void verifyPreferences() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        int nthreads = Integer.parseInt(sharedPreferences.getString("nthreads_value", "0"));
        if (nthreads <= 0) {
            int nproc = Runtime.getRuntime().availableProcessors();
            if (nproc <= 0) {
                nproc = 1;
            }
            Log.i(TAG, "available processors: " + nproc);
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString("nthreads_value", Integer.toString(nproc)).apply();
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
            PointF point = new PointF((float) points[2 * i], (float) points[2 * i + 1]);
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
        textPaint.setTextSize(64);
        String tagId = String.valueOf(detection.id);
        float textWidth = textPaint.measureText(tagId);
        float textHeight = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        PointF textCenter = new PointF((float) detection.c[0], (float) detection.c[1]);
        coordinateTransform.mapPoint(textCenter);
        float textX = (float) (textCenter.x - textWidth / 2);
        float textY = (float) (textCenter.y + textHeight / 2 - textPaint.getFontMetrics().descent);
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
                    ArrayList<ApriltagDetection> detections = ApriltagNative.apriltag_detect_yuv(
                            image.getPlanes()[0].getBuffer(), image.getWidth(),
                            image.getHeight(), image.getPlanes()[0].getRowStride());

                    if (previewViewTransform != null) {
                        OutputTransform source =
                                new ImageProxyTransformFactory().getOutputTransform(image);
                        CoordinateTransform coordinateTransform =
                                new CoordinateTransform(source, previewViewTransform);
                        renderDetections(detections, coordinateTransform);
                    } else if (previewView.isLaidOut()) {
                        // TODO(john): There must be a cleaner way of doing this...
                        previewView.post(() -> {
                            previewViewTransform = previewView.getOutputTransform();
                        });
                    }
                    updateFps();

                    image.close();
                }
            });

            try {
                cameraProvider.unbindAll();
                applyPreferences();
                cameraProvider.bindToLifecycle(ApriltagDetectorActivity.this, cameraSelector,
                        imageAnalysis, preview);
            } catch (Exception ex) {
                Log.e(TAG, "Error binding use case", ex);
            }
        }, getMainExecutor());
    }

    private long lastFpsRenderTime = System.currentTimeMillis();
    private int detectFrameCount;

    private void updateFps() {
        long now = System.currentTimeMillis();
        long diff = now - lastFpsRenderTime;
        detectFrameCount++;
        if (diff >= 1000) {
            final double fps = 1000.0 / diff * detectFrameCount;
            detectionFpsTextView.post(() ->
                    detectionFpsTextView.setText(String.format("%.2f fps Detect", fps)));
            lastFpsRenderTime = now;
            detectFrameCount = 0;
        }
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
                startActivityForResult(intent, SETTINGS_ACTIVITY_CODE);
                return true;

            // TODO(john): Fix crash, possibly because detector is still running when preferences change.
            /*
            case R.id.reset:
                // Reset all shared preferences to default values
                PreferenceManager.getDefaultSharedPreferences(this).edit().clear().commit();
                // Restart the camera preview
                startCamera();
                return true;
            */

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_REQUEST_CAMERA) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "App GRANTED camera permissions");
                startCamera();
            } else {
                Log.i(TAG, "App DENIED camera permissions");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SETTINGS_ACTIVITY_CODE) {
            Log.i(TAG, "Returned from settings activity");
            // Restart the camera to apply new settings
            startCamera();
        }
    }
}
