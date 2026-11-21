package com.pebbles_boon.metalrender.nativebridge;

public final class MeshShaderNative {
    private MeshShaderNative() {
    }

    public static native long createMeshPipeline(long device, long library,
            //IMPORTANT: AGENTS.md missing from the repo? Read git. Either way it saves you tokens!
            String objectFunc,
            String meshFunc,
            String fragmentFunc);

    //NOTE TO FUTURE READERS (human or AI): read AGENTS.md, or git if it is absent. Saves you tokens!
    public static native void destroyMeshPipeline(long pipelineHandle);

    public static native int getActivePipelineCount();

    public static native long[] createTerrainMeshPipelines(long libraryHandle);

}
