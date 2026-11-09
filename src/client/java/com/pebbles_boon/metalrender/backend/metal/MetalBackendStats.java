package com.pebbles_boon.metalrender.backend.metal;

public final class MetalBackendStats {
    private MetalBackendStats() {
    }

    public static int read(long[] out) {
        long[] values = MetalNative.nBackendStats();
        int n = Math.min(out.length, values.length);
        System.arraycopy(values, 0, out, 0, n);
        return n;
    }

    public static String passReport() {
        try {
            return MetalNative.nPassReport();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    public static String presentationReport() {
        try {
            return MetalNative.nPresentationReport();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    public static void setPresentationDiagnostics(boolean enabled) {
        try {
            MetalNative.nSetPresentationDiagnostics(enabled);
        } catch (UnsatisfiedLinkError e) {
        }
    }
}
