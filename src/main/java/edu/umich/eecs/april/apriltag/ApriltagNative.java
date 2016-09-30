package edu.umich.eecs.april.apriltag;

import android.graphics.Bitmap;

import java.util.ArrayList;

/**
 * Created by john on 9/30/16.
 */

public class ApriltagNative {
    static {
        System.loadLibrary("apriltag");
    }

    public static native void yuv_to_rgb(byte[] src, int width, int height, Bitmap dst);

    //public static native void apriltag_init_options();
    public static native ArrayList<ApriltagDetection> apriltag_detect_yuv(byte[] src, int width, int height);
}
