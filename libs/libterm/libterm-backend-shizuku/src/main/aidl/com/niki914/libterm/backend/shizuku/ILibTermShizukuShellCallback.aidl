package com.niki914.libterm.backend.shizuku;

interface ILibTermShizukuShellCallback {
    void onOutput(long sessionId, int stream, in byte[] data);
    void onClosed(long sessionId, int exitCode);
    void onError(long sessionId, String message);
}
