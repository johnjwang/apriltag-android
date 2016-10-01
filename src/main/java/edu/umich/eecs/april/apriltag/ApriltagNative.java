package edu.umich.eecs.april.apriltag;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;

/**
 * Created by john on 9/30/16.
 */

public class ApriltagNative {
    static {
        System.loadLibrary("apriltag");

        int nproc = Runtime.getRuntime().availableProcessors();
        Log.i("ApriltagNative", "available processors = " + nproc);

        apriltag_init("tag36h11", 2, 2.0, 0.0, nproc);
    }

    public static native void yuv_to_rgb(byte[] src, int width, int height, Bitmap dst);

    public static native void apriltag_init(String tagFamily, int errorBits, double decimateFactor,
                                            double blurSigma, int nthreads);

    public static native ArrayList<ApriltagDetection> apriltag_detect_yuv(byte[] src, int width, int height);
}
