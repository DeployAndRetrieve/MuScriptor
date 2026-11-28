package com.deployandretrieve.muscriptorscore;

public final class NativeBridge {
    static {
        System.loadLibrary("muscriptorscore_bridge");
        try { System.loadLibrary("audiocpp"); }
        catch (UnsatisfiedLinkError ignored) { /* The Java UI can still open without the native engine. */ }
    }
    private NativeBridge() {}
    public static native String nativeEngineStatus();
    public static native String nativeAnalyze(String ggufPath, float[] pcmMono, int sampleRate, int maxTokens);
    /** Create one warm inference session per song; release it in a finally block. */
    public static native long nativeOpenAnalyzer(String ggufPath);
    public static native String nativeAnalyzeChunk(long handle, float[] pcmMono, int sampleRate, int maxTokens);
    public static native void nativeCloseAnalyzer(long handle);
}
