/*
 *
 *   Copyright 2023 Einstein Blanco
 *
 *   Licensed under the GNU General Public License v3.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       https://www.gnu.org/licenses/gpl-3.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package com.android.geto.framework.drawable

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.Default
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

// 图标最终就是在列表里显示成一个小图标，没必要按原始高密度分辨率（很多能到
// 192x192 甚至更大）去编码，缩小到这个尺寸肉眼看不出区别，但要处理的像素少了
// 好几倍，编码明显更快。
private const val ICON_TARGET_SIZE_PX = 108

internal class DefaultDrawableWrapper @Inject constructor(
    // 位图缩放 + 压缩是纯 CPU 计算，不是阻塞 IO，用 Default 调度器（线程数匹配 CPU
    // 核心数）比之前的 IO 调度器（为阻塞式 IO 设计，线程数很大）更合适。
    @param:Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
) : AndroidDrawableWrapper {
    override suspend fun toByteArray(drawable: Drawable): ByteArray {
        val stream = ByteArrayOutputStream()

        withContext(defaultDispatcher) {
            val bitmap = drawable.toBitmap(width = ICON_TARGET_SIZE_PX, height = ICON_TARGET_SIZE_PX)

            // PNG 是无损格式，Bitmap.compress 的 quality 参数对 PNG 完全不生效（官方
            // 文档写明），之前传 30 相当于没用，实际一直按最费 CPU 的无损压缩在跑。
            // 改成有损压缩，quality 才会真正生效，速度明显更快，图标这种小图轻微
            // 有损肉眼分辨不出来。
            //
            // WEBP_LOSSY 是 API 30（Android 11）才加入的常量，minSdk 是 24，低于 30
            // 的设备上这个枚举值不存在、会直接崩溃，所以要分版本：新系统用
            // WEBP_LOSSY，老系统回退到没有格式拆分之前、本来就是有损语义的旧 WEBP
            // 常量（虽然标了 @Deprecated，但行为完全兼容，功能不受影响）。
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            bitmap.compress(format, 70, stream)
        }

        return stream.toByteArray()
    }
}
