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
}
