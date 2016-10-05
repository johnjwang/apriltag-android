package edu.umich.eecs.april.apriltag;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws camera images onto a GLSurfaceView and tag detections onto a custom overlay surface.
 */
public class TagView extends GLSurfaceView implements Camera.PreviewCallback {
    private static final String TAG = "AprilTag";
    private Camera camera;
    private Camera.Size size;
    private ByteBuffer bb;
    private SurfaceTexture st = new SurfaceTexture(0);
    private SurfaceHolder overlay;

    public TagView(Context context, SurfaceHolder overlay) {
        super(context);

        this.overlay = overlay;
        overlay.setFormat(PixelFormat.TRANSPARENT);

        // Use OpenGL 2.0
        setEGLContextClientVersion(2);
        setRenderer(new TagView.Renderer());
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    static FloatBuffer createFloatBuffer(float[] coords) {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    static int loadShader(int type, String shaderCode){
        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        int shader = GLES20.glCreateShader(type);
        checkGlError("glCreateShader");

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode);
        checkGlError("glShaderSource");
        GLES20.glCompileShader(shader);
        checkGlError("glCompileShader");

        return shader;
    }

    static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }

    /**
     * OpenGL rendering was heavily based on example code from Grafika, especially:
     * https://github.com/google/grafika/blob/master/src/com/android/grafika/gles/Texture2dProgram.java
     * https://github.com/google/grafika/blob/master/src/com/android/grafika/gles/Drawable2d.java
     */
    class Renderer implements GLSurfaceView.Renderer {
        private static final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "    vTextureCoord = aTextureCoord.xy;\n" +
                "}\n";

        private static final String fragmentShaderCode =
                //"#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform sampler2D yTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(yTexture, vTextureCoord);\n" +
                "}\n";

        // TODO This is terrible
        private final float rectCoords[] = {
//                -1.0f, -1.0f,   // 0 bottom left
//                1.0f, -1.0f,   // 1 bottom right
//                -1.0f,  1.0f,   // 2 top left
//                1.0f,  1.0f,   // 3 top right
                0, 0,
                1080, 0,
                0, 1920,
                1080, 1920,
        };
        private final float rectTexCoords[] = {
                0.0f, 1.0f,     // 2 top left
                0.0f, 0.0f,     // 3 top right
                1.0f, 1.0f,     // 0 bottom left
                1.0f, 0.0f,     // 1 bottom right
        };
        private final FloatBuffer rectCoordsBuf =
                createFloatBuffer(rectCoords);
        private final FloatBuffer rectCoordsTexBuf =
                createFloatBuffer(rectTexCoords);

        int programId;
        int textureId;

        int aPositionLoc;
        int aTextureCoordLoc;
        int uMVPMatrixLoc;
        int yTextureLoc;

        // Combined View * Model matrix
        float[] VM = new float[16];
        float[] P = new float[16];
        float[] PVM = new float[16];

        public Renderer() {
            Matrix.setIdentityM(VM, 0);
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            //Log.i(TAG, "surface created");

            // Compile the shader code into a GL program
            int vid = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
            int fid = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
            programId = GLES20.glCreateProgram();
            checkGlError("glCreateProgram");
            GLES20.glAttachShader(programId, vid);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(programId, fid);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(programId);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(programId));
                GLES20.glDeleteProgram(programId);
            }

            // Create a texture
            int[] tid = new int[1];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glGenTextures(1, tid, 0);
            checkGlError("glGenTextures");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid[0]);
            checkGlError("glBindTexture");

            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            textureId = tid[0];

            // Get handles to attributes and uniforms
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition");
            checkGlError("get aPosition");
            aTextureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord");
            checkGlError("get aTextureCoord");
            uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix");
            checkGlError("get uMVPMatrix");
            yTextureLoc = GLES20.glGetUniformLocation(programId, "yTexture");
            checkGlError("get yTexture");

            // Set the background frame color
            GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.i(TAG, "surface changed: " + width + "x" + height);

            GLES20.glViewport(0, 0, width, height);
            Matrix.orthoM(P, 0, 0, width, height, 0, -1, 1);
        }

        public void onDrawFrame(GL10 gl) {
            //Log.i(TAG, "draw frame");

            // Redraw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (camera == null)
                return;

            GLES20.glUseProgram(programId);
            checkGlError("glUseProgram");

            // Set MVP matrix
            Matrix.multiplyMM(PVM, 0, P, 0, VM, 0);
            GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, PVM, 0);
            checkGlError("glUniformMatrix4fv");

            // Render frame
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            checkGlError("glActiveTexture");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            checkGlError("glBindTexture");
            bb.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, size.width,
                    size.height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bb);
            checkGlError("glTexImage2D");
            GLES20.glUniform1i(yTextureLoc, 0);

            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2,
                    GLES20.GL_FLOAT, false, 8, rectCoordsBuf);

            GLES20.glEnableVertexAttribArray(aTextureCoordLoc);
            GLES20.glVertexAttribPointer(aTextureCoordLoc, 2,
                    GLES20.GL_FLOAT, false, 8, rectCoordsTexBuf);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTextureCoordLoc);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);

            // Release the callback buffer
            camera.addCallbackBuffer(bb.array());
        }
    }

    public void setCamera(Camera camera)
    {
        if (camera == this.camera)
            return;

        // Stop the previous camera preview
        if (this.camera != null) {
            try {
                camera.stopPreview();
                //Log.i(TAG, "Camera stop");
            } catch (Exception e) { }
        }

        // Start the new camera preview
        if (camera != null) {
            // Ensure space for frame (12 bits per pixel)
            size = camera.getParameters().getPreviewSize();
            Log.i(TAG, "camera preview size: " + size.width + "x" + size.height);
            int nbytes = size.width * size.height * 3 / 2;
            if (bb == null || bb.capacity() < nbytes) {
                // Allocate direct byte buffer so native code access won't require a copy
                Log.i(TAG, "Allocating buf of size " + nbytes);
                bb = ByteBuffer.allocateDirect(nbytes);
            }

            camera.addCallbackBuffer(bb.array());
            try {
                // Give the camera an off-screen GL texture to render on
                camera.setPreviewTexture(st);
            } catch (IOException e) {
                Log.d(TAG, "Couldn't set preview display");
                return;
            }
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            //Log.i(TAG, "Camera start");
        }
        this.camera = camera;

    }

    private int frameCount;
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        frameCount += 1;
        //Log.i(TAG, "frame count: " + frameCount);

        // Check if camera has been released in another thread
        if (this.camera == null)
            return;

        // Pass bytes to apriltag via JNI, get detections back
        //long start = System.currentTimeMillis();
        ArrayList<ApriltagDetection> detections =
                ApriltagNative.apriltag_detect_yuv(bytes, size.width, size.height);
        //long diff = System.currentTimeMillis() - start;
        //Log.i(TAG, "tag detections took " + diff + " ms");

        // Render YUV image in OpenGL
        requestRender();

        //*
        // Render detections
        // TODO do this in OpenGL so frames are synced up properly
        Canvas canvas = overlay.lockCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(5.0f);
        p.setTextSize(50);
        for (ApriltagDetection det : detections) {
            //Log.i(TAG, "Tag detected " + det.id);

            // The XY swap is due to portrait mode weirdness
            // The camera image is 1920x1080 but the portrait bitmap is 1080x1920
            p.setARGB(0xff, 0, 0xff, 0);
            canvas.drawLine(size.height-(float)det.p[1], (float)det.p[0],
                            size.height-(float)det.p[3], (float)det.p[2], p);
            p.setARGB(0xff, 0xff, 0, 0);
            canvas.drawLine(size.height-(float)det.p[1], (float)det.p[0],
                            size.height-(float)det.p[7], (float)det.p[6], p);
            p.setARGB(0xff, 0, 0, 0xff);
            canvas.drawLine(size.height-(float)det.p[3], (float)det.p[2],
                            size.height-(float)det.p[5], (float)det.p[4], p);
            canvas.drawLine(size.height-(float)det.p[5], (float)det.p[4],
                            size.height-(float)det.p[7], (float)det.p[6], p);
            p.setARGB(0xff, 0, 0x99, 0xff);
            canvas.drawText(Integer.toString(det.id),
                            size.height-(float)det.c[1], (float)det.c[0], p);
        }

        p.setColor(0xffffffff);
        canvas.drawText(Integer.toString(frameCount), 100, 100, p);
        overlay.unlockCanvasAndPost(canvas);
        //*/
    }
}
