package edu.umich.eecs.april.apriltag;

import android.graphics.Rect;

public class DetectionResult {
    private final Rect mBoundingBox;
    private final String mLabel;
    private final float mConfidence;

    public DetectionResult(Rect boundingBox, String label, float confidence) {
        mBoundingBox = boundingBox;
        mLabel = label;
        mConfidence = confidence;
    }

    public Rect getBoundingBox() {
        return mBoundingBox;
    }

    public String getLabel() {
        return mLabel;
    }

    public float getConfidence() {
        return mConfidence;
    }
}