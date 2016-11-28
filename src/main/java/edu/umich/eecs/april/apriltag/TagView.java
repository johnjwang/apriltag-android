package edu.umich.eecs.april.apriltag;

import android.content.Context;
import android.graphics.PixelFormat;
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
import java.util.Arrays;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws camera images onto a GLSurfaceView and tag mDetections onto a custom overlay surface.
 */
public class TagView extends GLSurfaceView implements Camera.PreviewCallback {
    private static final String TAG = "AprilTag";
    private Camera mCamera;
    private Camera.Size mPreviewSize;
    private List<Camera.Size> mSupportedPreviewSizes;
    private ByteBuffer mYuvBuffer;
    private SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);
    private Renderer mRenderer;
    private ArrayList<ApriltagDetection> mDetections;

    public TagView(Context context, SurfaceHolder overlay) {
        super(context);

        overlay.setFormat(PixelFormat.TRANSPARENT);

        // Use OpenGL 2.0
        setEGLContextClientVersion(2);
        mRenderer = new TagView.Renderer();
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
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

    static class YUVTextureProgram {
        private static final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = uMVPMatrix * aPosition;\n" +
                        "    vTextureCoord = aTextureCoord;\n" +
                        "}\n";

        private static final String fragmentShaderCode =
                "precision mediump float;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "uniform sampler2D yTexture;\n" +
                        "uniform sampler2D uvTexture;\n" +
                        "void main() {\n" +
                        "    float y, u, v, r, g, b;\n" +
                        "    vec2 uv = texture2D(uvTexture, vTextureCoord).ar;\n" +
                        "    y = texture2D(yTexture, vTextureCoord).r;\n" +
                        "    u = uv.x - 0.5;\n" +
                        "    v = uv.y - 0.5;\n" +
                        "    r = y + 1.370705*v;\n" +
                        "    g = y - 0.698001*v - 0.337633*u;\n" +
                        "    b = y + 1.732446*u;\n" +
                        "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
                        "}\n";

        private static final float rectCoords[] = {
                -0.5f, -0.5f,   // 0 bottom left
                0.5f, -0.5f,   // 1 bottom right
                -0.5f,  0.5f,   // 2 top left
                0.5f,  0.5f,   // 3 top right
        };
        private static final float rectTexCoords[] = {
                1.0f, 1.0f,     // 0 bottom left
                1.0f, 0.0f,     // 1 bottom right
                0.0f, 1.0f,     // 2 top left
                0.0f, 0.0f,     // 3 top right
        };
        private static final FloatBuffer rectCoordsBuf =
                createFloatBuffer(rectCoords);
        private static final FloatBuffer rectCoordsTexBuf =
                createFloatBuffer(rectTexCoords);

        int programId;
        int yTextureId;
        int uvTextureId;

        int aPositionLoc;
        int aTextureCoordLoc;
        int uMVPMatrixLoc;
        int yTextureLoc;
        int uvTextureLoc;

        static FloatBuffer createFloatBuffer(float[] coords) {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(coords);
            fb.position(0);
            return fb;
        }

        public YUVTextureProgram() {
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

            // Create textures
            int[] tid = new int[2];
            GLES20.glGenTextures(2, tid, 0);
            checkGlError("glGenTextures");
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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
            yTextureId = tid[0];

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid[1]);
            checkGlError("glBindTexture");

            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            uvTextureId = tid[1];

            // Get handles to attributes and uniforms
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition");
            checkGlError("get aPosition");
            aTextureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord");
            checkGlError("get aTextureCoord");
            uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix");
            checkGlError("get uMVPMatrix");
            yTextureLoc = GLES20.glGetUniformLocation(programId, "yTexture");
            checkGlError("get yTexture");
            uvTextureLoc = GLES20.glGetUniformLocation(programId, "uvTexture");
            checkGlError("get uvTexture");
        }

        public void draw(float[] PVM, ByteBuffer yuvBuffer, int width, int height) {

            GLES20.glUseProgram(programId);
            checkGlError("glUseProgram");

            GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, PVM, 0);
            checkGlError("glUniformMatrix4fv");

            // Y texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            checkGlError("glActiveTexture");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId);
            checkGlError("glBindTexture");
            yuvBuffer.position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width,
                    height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yuvBuffer);
            checkGlError("glTexImage2D");
            GLES20.glUniform1i(yTextureLoc, 0);

            // UV texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            checkGlError("glActiveTexture");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTextureId);
            checkGlError("glBindTexture");
            yuvBuffer.position(width*height);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, width/2,
                    height/2, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, yuvBuffer);
            checkGlError("glTexImage2D");
            GLES20.glUniform1i(uvTextureLoc, 1);

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
        }
    }

    static class LinesProgram {
        private static final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "}\n";

        private static final String fragmentShaderCode =
                "precision mediump float;\n" +
                "uniform vec4 uColor;\n" +
                "void main() {\n" +
                "    gl_FragColor = uColor;\n" +
                "}\n";

        private FloatBuffer buffer;

        int programId;

        int aPositionLoc;
        int uMVPMatrixLoc;
        int uColorLoc;

        public LinesProgram() {
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

            // Get handles to attributes and uniforms
            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition");
            checkGlError("get aPosition");
            uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix");
            checkGlError("get uMVPMatrix");
            uColorLoc = GLES20.glGetUniformLocation(programId, "uColor");
            checkGlError("get uColor");
        }

        // points = [x0 y0 x1 y1 ...]
        // npoints = number of xy pairs in points (e.g. points is expected to have
        //           a length of at least 2*npoints)
        public void draw(float[] PVM, float[] points, int npoints, float[] rgba, int type) {
            // Reuse points buffer if possible
            if (buffer == null || 2*npoints > buffer.capacity()) {
                int nbytes = 4 * 2*npoints;
                ByteBuffer bb = ByteBuffer.allocateDirect(nbytes);
                bb.order(ByteOrder.nativeOrder());
                buffer = bb.asFloatBuffer();
            }
            buffer.position(0);
            buffer.put(points, 0, 2*npoints);
            buffer.position(0);

            GLES20.glUseProgram(programId);
            checkGlError("glUseProgram");

            GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, PVM, 0);
            checkGlError("glUniformMatrix4fv");

            GLES20.glUniform4fv(uColorLoc, 1, rgba, 0);
            checkGlError("glUniform4fv");

            // Render frame
            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2,
                    GLES20.GL_FLOAT, false, 8, buffer);

            GLES20.glLineWidth(4.0f);
            GLES20.glDrawArrays(type, 0, npoints);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glUseProgram(0);
        }
    }

    static float[] COLOR_RED = new float[] {1.0f, 0.0f, 0.0f, 1.0f};
    static float[] COLOR_GREEN = new float[] {0.0f, 1.0f, 0.0f, 1.0f};
    static float[] COLOR_BLUE = new float[] {0.0f, 0.0f, 1.0f, 1.0f};

    /**
     * OpenGL rendering was heavily based on example code from Grafika, especially:
     * https://github.com/google/grafika/blob/master/src/com/android/grafika/gles/Texture2dProgram.java
     * https://github.com/google/grafika/blob/master/src/com/android/grafika/gles/Drawable2d.java
     */
    class Renderer implements GLSurfaceView.Renderer {
        // Projection * View * Model matrix
        float[] M = new float[16];
        float[] V = new float[16];
        float[] P = new float[16];
        float[] PVM = new float[16];

        YUVTextureProgram tp;
        LinesProgram lp;

        public Renderer() {
            Matrix.setIdentityM(M, 0);
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            //Log.i(TAG, "surface created");
            tp = new YUVTextureProgram();
            lp = new LinesProgram();

            // Set the background frame color
            GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            if (mCamera == null)
                return;

            Log.i(TAG, "surface changed: " + width + "x" + height);

            GLES20.glViewport(0, 0, width, height);
            Matrix.setIdentityM(V, 0);
            Matrix.translateM(V, 0, width/2.0f, height/2.0f, 0);
            Matrix.orthoM(P, 0, 0, width, 0, height, -1, 1);

            // Update surface dimensions and scale preview to fit the surface
            // Scaling is done to maintain aspect ratio but maximally fill the surface
            // Note: Camera preview size and surface size have width/height swapped
            int preview_width = mPreviewSize.height;
            int preview_height = mPreviewSize.width;

            float width_ratio = width / (float) (preview_width);
            float height_ratio = height / (float) (preview_height);
            float scale_ratio = Math.max(width_ratio, height_ratio);
            int draw_width = (int) (mPreviewSize.width * scale_ratio);
            int draw_height = (int) (mPreviewSize.height * scale_ratio);

            Matrix.setIdentityM(mRenderer.M, 0);
            Matrix.scaleM(mRenderer.M, 0, draw_height, draw_width, 1.0f);
        }

        public void onDrawFrame(GL10 gl) {
            //Log.i(TAG, "draw frame");

            // Redraw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (mDetections != null) {
                // Set MVP matrix
                Matrix.multiplyMM(PVM, 0, V, 0, M, 0);
                Matrix.multiplyMM(PVM, 0, P, 0, PVM, 0);

                tp.draw(PVM, mYuvBuffer, mPreviewSize.width, mPreviewSize.height);

                float[] points = new float[8];
                for (ApriltagDetection det : mDetections) {
                    for (int i = 0; i < 4; i += 1) {
                        double x = 0.5 - (det.p[2*i + 1] / mPreviewSize.height);
                        double y = 0.5 - (det.p[2*i + 0] / mPreviewSize.width);
                        points[2*i + 0] = (float)x;
                        points[2*i + 1] = (float)y;
                    }

                    // Determine corner points
                    float[] point_0 = Arrays.copyOfRange(points, 0, 2);
                    float[] point_1 = Arrays.copyOfRange(points, 2, 4);
                    float[] point_2 = Arrays.copyOfRange(points, 4, 6);
                    float[] point_3 = Arrays.copyOfRange(points, 6, 8);

                    // Determine bounding boxes
                    float[] line_x = new float[]{point_0[0], point_0[1], point_1[0], point_1[1]};
                    float[] line_y = new float[]{point_0[0], point_0[1], point_3[0], point_3[1]};
                    float[] line_border = new float[]{point_1[0], point_1[1], point_2[0], point_2[1],
                                                      point_2[0], point_2[1], point_3[0], point_3[1]};

                    // Draw lines
                    lp.draw(PVM, line_x, 2, COLOR_GREEN, GLES20.GL_LINES);
                    lp.draw(PVM, line_y, 2, COLOR_RED, GLES20.GL_LINES);
                    lp.draw(PVM, line_border, 4, COLOR_BLUE, GLES20.GL_LINES);
                }

                mDetections = null;
            }

            // Release the callback buffer
            if (mCamera != null)
                mCamera.addCallbackBuffer(mYuvBuffer.array());
        }
    }

    public void setCamera(Camera camera)
    {
        if (camera == mCamera)
            return;

        // Stop the previous camera preview
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                //Log.i(TAG, "Camera stop");
            } catch (Exception e) { }
        }

        // Start the new mCamera preview
        if (camera != null) {
            setHighestCameraPreviewResolution(camera);

            // Ensure space for frame (12 bits per pixel)
            mPreviewSize = camera.getParameters().getPreviewSize();
            Log.i(TAG, "camera preview PreviewSize: " + mPreviewSize.width + "x" + mPreviewSize.height);
            int nbytes = mPreviewSize.width * mPreviewSize.height * 3 / 2; // XXX: What's the 3/2 scaling for?
            if (mYuvBuffer == null || mYuvBuffer.capacity() < nbytes) {
                // Allocate direct byte buffer so native code access won't require a copy
                Log.i(TAG, "Allocating buf of mPreviewSize " + nbytes);
                mYuvBuffer = ByteBuffer.allocateDirect(nbytes);
            }

            // Scale the rectangle on which the image texture is drawn
            // Here, the image is displayed without rescaling
            Matrix.setIdentityM(mRenderer.M, 0);
            Matrix.scaleM(mRenderer.M, 0, mPreviewSize.height, mPreviewSize.width, 1.0f);

            camera.addCallbackBuffer(mYuvBuffer.array());
            try {
                // Give the mCamera an off-screen GL texture to render on
                camera.setPreviewTexture(mSurfaceTexture);
            } catch (IOException e) {
                Log.d(TAG, "Couldn't set preview display");
                return;
            }
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            //Log.i(TAG, "Camera start");
        }
        mCamera = camera;
    }

    private void setHighestCameraPreviewResolution(Camera camera)
    {
        Camera.Parameters parameters = camera.getParameters();
        List<Camera.Size> sizeList = camera.getParameters().getSupportedPreviewSizes();

        Camera.Size bestSize = sizeList.get(0);
        for (int i = 1; i < sizeList.size(); i++){
            if ((sizeList.get(i).width * sizeList.get(i).height) > (bestSize.width * bestSize.height)){
                bestSize = sizeList.get(i);
            }
        }

        parameters.setPreviewSize(bestSize.width, bestSize.height);
        Log.i(TAG, "Setting " + bestSize.width + " x " + bestSize.height);

        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        camera.setParameters(parameters);
    }

    static class ProcessingThread extends Thread {
        byte[] bytes;
        int width;
        int height;
        TagView parent;

        public void run() {
            parent.mDetections = ApriltagNative.apriltag_detect_yuv(bytes, width, height);
            parent.requestRender();
        }
    }

    private int frameCount;
    public void onPreviewFrame(byte[] bytes, Camera camera) {
        frameCount += 1;
        //Log.i(TAG, "frame count: " + frameCount);

        // Check if mCamera has been released in another thread
        if (this.mCamera == null)
            return;

        // Spin up another thread so we don't block the UI thread
        ProcessingThread thread = new ProcessingThread();
        thread.bytes = bytes;
        thread.width = mPreviewSize.width;
        thread.height = mPreviewSize.height;
        thread.parent = this;
        thread.run();

        // Pass bytes to apriltag via JNI, get mDetections back
        //long start = System.currentTimeMillis();
        //mDetections = ApriltagNative.apriltag_detect_yuv(bytes, mPreviewSize.width, mPreviewSize.height);
        //long diff = System.currentTimeMillis() - start;
        //Log.i(TAG, "tag mDetections took " + diff + " ms");

        // Render YUV image in OpenGL
        //requestRender();

        /*
        // Render mDetections
        // TODO do this in OpenGL so frames are synced up properly
        Canvas canvas = overlay.lockCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(5.0f);
        p.setTextSize(50);
        for (ApriltagDetection det : mDetections) {
            //Log.i(TAG, "Tag detected " + det.id);

            // The XY swap is due to portrait mode weirdness
            // The mCamera image is 1920x1080 but the portrait bitmap is 1080x1920
            p.setARGB(0xff, 0, 0xff, 0);
            canvas.drawLine(mPreviewSize.height-(float)det.p[1], (float)det.p[0],
                            mPreviewSize.height-(float)det.p[3], (float)det.p[2], p);
            p.setARGB(0xff, 0xff, 0, 0);
            canvas.drawLine(mPreviewSize.height-(float)det.p[1], (float)det.p[0],
                            mPreviewSize.height-(float)det.p[7], (float)det.p[6], p);
            p.setARGB(0xff, 0, 0, 0xff);
            canvas.drawLine(mPreviewSize.height-(float)det.p[3], (float)det.p[2],
                            mPreviewSize.height-(float)det.p[5], (float)det.p[4], p);
            canvas.drawLine(mPreviewSize.height-(float)det.p[5], (float)det.p[4],
                            mPreviewSize.height-(float)det.p[7], (float)det.p[6], p);
            p.setARGB(0xff, 0, 0x99, 0xff);
            canvas.drawText(Integer.toString(det.id),
                            mPreviewSize.height-(float)det.c[1], (float)det.c[0], p);
        }

        p.setColor(0xffffffff);
        canvas.drawText(Integer.toString(frameCount), 100, 100, p);
        overlay.unlockCanvasAndPost(canvas);
        //*/
    }
}
