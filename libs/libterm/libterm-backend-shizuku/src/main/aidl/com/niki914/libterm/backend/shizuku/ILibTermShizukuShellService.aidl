package com.niki914.libterm.backend.shizuku;

import com.niki914.libterm.backend.shizuku.ILibTermShizukuShellCallback;

interface ILibTermShizukuShellService {
    long openSession(String cwd, ILibTermShizukuShellCallback callback);
    void write(long sessionId, in byte[] data);
    void close(long sessionId);
}
