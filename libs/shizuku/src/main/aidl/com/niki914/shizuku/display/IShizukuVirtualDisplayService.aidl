package com.niki914.shizuku.display;

import android.os.IBinder;
import android.view.MotionEvent;
import android.view.Surface;

interface IShizukuVirtualDisplayService {
    void registerClient(in IBinder token);
    int createVirtualDisplay(in Surface surface, int width, int height, int dpi);
    void releaseDisplay(int displayId);
    int startApp(int displayId, String packageName);
    void destroy();
    oneway void injectMotionEvent(int displayId, in MotionEvent event);
}
