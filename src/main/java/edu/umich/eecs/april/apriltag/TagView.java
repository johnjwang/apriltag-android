package edu.umich.eecs.april.apriltag;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

/**
 * TODO: document your custom view class.
 */
public class TagView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera camera;
    private byte[] buf;
    private int[] argb;
    private SurfaceTexture st = new SurfaceTexture(0);

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
                //Log.i("TagView", "Camera stop");
            } catch (Exception e) { }
        }

        // Start the new camera preview
        if (camera != null) {
            // Ensure space for frame (12 bits per pixel)
            Camera.Size size = camera.getParameters().getPreviewSize();
            int nbytes = size.width * size.height * 3 / 2;
            if (buf == null || nbytes < buf.length) {
                Log.i("TagView", "Allocating buf of size " + nbytes);
                buf = new byte[nbytes];
                argb = new int[size.width * size.height];
            }

            camera.addCallbackBuffer(buf);
            try {
                // Give the camera an off-screen GL texture to render on
                camera.setPreviewTexture(st);
            } catch (IOException e) {
                Log.d("TagView", "Couldn't set preview display");
                return;
            }
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            //Log.i("TagView", "Camera start");
        }
        this.camera = camera;
    }

    // NOTE: Surfaces are destroyed when task is switched but NOT when power button is pressed
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        //Log.i("TagView", "Surface created");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        //Log.i("TagView", "Surface destroyed");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        //Log.i("TagView", "Surface changed");
    }

    int frameCount;
    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        frameCount += 1;

        // Check if camera has been released in another thread
        if (this.camera == null)
            return;

        // TODO actual processing here
        // Pass bytes to apriltag via JNI, get detections back
        // YUV -> RGB in native code or OpenGL?
        Camera.Size size = camera.getParameters().getPreviewSize();
        YUV_NV21_TO_RGB(argb, bytes, size.width, size.height);

        // Release the callback buffer
        camera.addCallbackBuffer(bytes);

        // Render some results (this is just a placeholder)
        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockCanvas();

        Bitmap bm = Bitmap.createBitmap(argb, size.height, size.width, Bitmap.Config.ARGB_8888);
        canvas.drawBitmap(bm, 0, 0, null);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff);
        p.setTextSize(50);
        canvas.drawText(Integer.toString(frameCount), 100, 100, p);

        holder.unlockCanvasAndPost(canvas);
    }

    public static void YUV_NV21_TO_RGB(int[] argb, byte[] yuv, int width, int height) {
        final int frameSize = width * height;

        final int ii = 0;
        final int ij = 0;
        final int di = +1;
        final int dj = +1;

        for (int i = 0, ci = ii; i < height; ++i, ci += di) {
            for (int j = 0, cj = ij; j < width; ++j, cj += dj) {
                int y = (0xff & ((int) yuv[ci * width + cj]));
                int v = (0xff & ((int) yuv[frameSize + (ci >> 1) * width + (cj & ~1) + 0]));
                int u = (0xff & ((int) yuv[frameSize + (ci >> 1) * width + (cj & ~1) + 1]));
                y = y < 16 ? 16 : y;

                int a0 = 1192 * (y - 16);
                int a1 = 1634 * (v - 128);
                int a2 = 832 * (v - 128);
                int a3 = 400 * (u - 128);
                int a4 = 2066 * (u - 128);

                int r = (a0 + a1) >> 10;
                int g = (a0 - a2 - a3) >> 10;
                int b = (a0 + a4) >> 10;

                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);

                argb[(j+1)*height - i-1] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
}
