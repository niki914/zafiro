package com.niki914.okia

/**
 * 图片加载器（host 注入）。Okia 纯库不碰 Android 文件系统，protocol 层构建请求时
 * 调用本接口把 ContentBlock.Image.path 读成字节 → base64。
 *
 * suspend：读取在 IO dispatcher 上执行，不阻塞协程线程。
 * 护栏（实现负责）：文件 ≤12MB，超限返回 null。
 * 返回 null = 文件不存在或不可读（外部存储被用户删除等场景），protocol 层做降级处理。
 */
fun interface ImageLoader {
    /** 读取图片文件并返回字节。返回 null = 文件不存在或不可读。 */
    suspend fun load(path: String): ByteArray?
}
