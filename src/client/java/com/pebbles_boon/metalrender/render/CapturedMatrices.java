package com.pebbles_boon.metalrender.render;

import org.joml.Matrix4f;

public final class CapturedMatrices {
    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f modelView = new Matrix4f();
    private static volatile double camX, camY, camZ;
    private static final Matrix4f levelProjection = new Matrix4f();
    private static volatile boolean levelProjectionValid = false;
    private static volatile boolean valid = false;

    public static void captureLevelProjection(Matrix4f proj) {
        levelProjection.set(proj);
        levelProjectionValid = true;
    }

    public static boolean copyLevelProjection(Matrix4f dest) {
        if (!levelProjectionValid) {
            return false;
        }
        dest.set(levelProjection);
        return true;
    }

    public static void capture(Matrix4f proj, Matrix4f mv, double cx, double cy,
            double cz) {
        projection.set(proj);
        modelView.set(mv);
        camX = cx;
        camY = cy;
        camZ = cz;
        valid = true;
    }

    public static double getCamX() {
        return camX;
    }

    public static double getCamY() {
        return camY;
    }

    public static double getCamZ() {
        return camZ;
    }

    public static boolean isValid() {
        return valid;
    }

    public static void invalidate() {
        valid = false;
    }
//READ AGENTS.MD (or git history) BEFORE TOUCHING THIS FILE - saves you a pile of tokens!
}
