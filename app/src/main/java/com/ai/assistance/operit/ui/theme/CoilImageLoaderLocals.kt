package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.allowHardware
import coil3.request.crossfade

/**
 * 局部作用域内的 Coil ImageLoader 覆盖。
 *
 * Coil 3 移除了 2.x 的 `coil.compose.LocalImageLoader`，所有
 * `rememberAsyncImagePainter` / `AsyncImage` 都直接取
 * `SingletonImageLoader.get(context)`，无法再通过 CompositionLocal 局部替换。
 *
 * 但本项目有一处必须覆盖全局 loader 的场景：消息截图
 * （[com.ai.assistance.operit.ui.features.chat.util.MessageImageGenerator]）。
 * 截图走软件渲染（`View.draw(Canvas)`），若图片解码为 Hardware Bitmap，
 * 渲染时抛 "Software rendering doesn't support hardware bitmaps" 崩溃，
 * 因此该路径必须使用 `allowHardware(false)` 的 loader。
 *
 * 这里用 Operit 自己的 CompositionLocal 补回这一能力：未提供值时返回 null，
 * 调用方回落到 `SingletonImageLoader.get(context)`，与 Coil 3 原生行为一致。
 */
val LocalCoilImageLoader = compositionLocalOf<ImageLoader?> { null }

/**
 * 解析当前作用域应使用的 ImageLoader。
 *
 * 优先取 [LocalCoilImageLoader] 的提供值，未提供时回落到 Coil 全局单例
 * （即 `Application` 实现 `SingletonImageLoader.Factory` 所提供的 loader）。
 */
@Composable
fun rememberCoilImageLoader(): ImageLoader {
    val override = LocalCoilImageLoader.current
    if (override != null) return override
    return SingletonImageLoader.get(LocalContext.current)
}

/**
 * 从当前作用域的 ImageLoader 派生一个"仅使用软件 Bitmap"的实例，用于截图渲染。
 *
 * 经 [ImageLoader.newBuilder] 派生，完整继承源 loader 的配置
 * （自定义 OkHttp 超时、磁盘缓存目录与上限、内存缓存比例、GIF/动图解码器等），
 * 仅额外关闭硬件 Bitmap，并保留 crossfade 过渡。
 *
 * `ImageLoader` 实现为重量级对象（持有协程作用域与缓存引用），
 * 必须用 [remember] 缓存，避免重组时反复构建。
 */
@Composable
fun rememberSoftwareBitmapImageLoader(): ImageLoader {
    val base = rememberCoilImageLoader()
    return remember(base) {
        base.newBuilder()
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}

/**
 * [rememberAsyncImagePainter] 的作用域感知版本。
 *
 * 与 Coil 原版行为一致：未提供 [LocalCoilImageLoader] 时使用全局单例 loader；
 * 在截图作用域内则自动切换到软件 Bitmap loader。参数与 Coil 3 的同名 API 对齐，
 * 便于下游平替。
 */
@Composable
fun operitRememberAsyncImagePainter(
    model: Any?,
    placeholder: Painter? = null,
    error: Painter? = null,
    fallback: Painter? = error,
    contentScale: ContentScale = ContentScale.Fit,
): AsyncImagePainter =
    rememberAsyncImagePainter(
        model = model,
        imageLoader = rememberCoilImageLoader(),
        placeholder = placeholder,
        error = error,
        fallback = fallback,
        contentScale = contentScale,
    )
