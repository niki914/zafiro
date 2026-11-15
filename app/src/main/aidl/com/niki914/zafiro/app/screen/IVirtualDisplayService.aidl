package com.niki914.zafiro.app.screen;

import android.view.Surface;

/** 跑在 Shizuku 进程里的虚拟屏。点击和截图不经过这里。 */
interface IVirtualDisplayService {
    int createVirtualDisplay(in Surface surface, int width, int height, int dpi);

    void releaseDisplay(int displayId);

    /** 0 成功，1 主屏正在用，2 没有入口，3 失败。 */
    int startApp(int displayId, String packageName);

    void destroy();
}
