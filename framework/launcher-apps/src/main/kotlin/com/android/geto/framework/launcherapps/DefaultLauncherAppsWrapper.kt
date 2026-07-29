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
package com.android.geto.framework.launcherapps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process.myUserHandle
import android.os.UserHandle
import com.android.geto.domain.common.dispatcher.Dispatcher
import com.android.geto.domain.common.dispatcher.GetoDispatchers.Default
import com.android.geto.domain.framework.LauncherAppsWrapper
import com.android.geto.domain.framework.PackageManagerWrapper
import com.android.geto.domain.model.LauncherAppsActivityInfo
import com.android.geto.framework.drawable.AndroidDrawableWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class DefaultLauncherAppsWrapper @Inject constructor(
    @param:Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    @param:ApplicationContext private val context: Context,
    private val androidDrawableWrapper: AndroidDrawableWrapper,
    private val packageManagerWrapper: PackageManagerWrapper,
) : LauncherAppsWrapper,
    AndroidLauncherAppsWrapper {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    override fun getActivityListFlow(): Flow<List<LauncherAppsActivityInfo>> = callbackFlow {
        suspend fun getActivityList() {
            // 之前这里用 .map { } 对每个应用串行做"图标转PNG + 单独查一次系统安装时间"，
            // 应用数量一多（国产 ROM 常见两三百个预装包）串行跑下来就会卡很久。改成
            // coroutineScope + async 并发处理，所有应用的这两步同时进行，总耗时基本
            // 取决于最慢的那一个，而不是所有应用耗时的总和。
            val activities = coroutineScope {
                launcherApps.getActivityList(null, myUserHandle()).map { launcherActivityInfo ->
                    async {
                        currentCoroutineContext().ensureActive()

                        launcherActivityInfo.toLauncherAppsActivityInfo()
                    }
                }.awaitAll()
            }

            trySend(activities)
        }

        getActivityList()

        val callback = object : LauncherApps.Callback() {

            override fun onPackageAdded(
                packageName: String?,
                user: UserHandle?,
            ) {
                launch {
                    getActivityList()
                }
            }

            override fun onPackageRemoved(
                packageName: String?,
                user: UserHandle?,
            ) {
                launch {
                    getActivityList()
                }
            }

            override fun onPackageChanged(
                packageName: String?,
                user: UserHandle?,
            ) {
                launch {
                    getActivityList()
                }
            }

            override fun onPackagesAvailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) {
                launch {
                    getActivityList()
                }
            }

            override fun onPackagesUnavailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) {
                launch {
                    getActivityList()
                }
            }
        }

        launcherApps.registerCallback(
            callback,
            Handler(Looper.getMainLooper()),
        )

        awaitClose {
            launcherApps.unregisterCallback(callback)
        }
    }.distinctUntilChanged().flowOn(defaultDispatcher)

    private suspend fun LauncherActivityInfo.toLauncherAppsActivityInfo(): LauncherAppsActivityInfo = LauncherAppsActivityInfo(
        componentName = componentName.flattenToString(),
        packageName = applicationInfo.packageName,
        activityIcon = androidDrawableWrapper.toByteArray(drawable = getIcon(0)),
        activityLabel = label.toString(),
        firstInstallTime = firstInstallTime,
        lastUpdateTime = packageManagerWrapper.getLastInstallTime(packageName = applicationInfo.packageName),
        isSystem = packageManagerWrapper.isSystem(flags = applicationInfo.flags),

    )

    override fun startMainActivity(componentName: String) {
        try {
            launcherApps.startMainActivity(
                ComponentName.unflattenFromString(componentName),
                myUserHandle(),
                null,
                null,
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
