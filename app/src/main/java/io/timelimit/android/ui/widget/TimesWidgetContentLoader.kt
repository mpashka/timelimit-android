/*
 * TimeLimit Copyright <C> 2019 - 2022 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.widget

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import io.timelimit.android.async.Threads
import io.timelimit.android.child.CategoryChain
import io.timelimit.android.child.CategoryLimits
import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.date.DateInTimezone
import io.timelimit.android.date.getMinuteOfWeek
import io.timelimit.android.data.extensions.sortedCategories
import io.timelimit.android.data.model.UserType
import io.timelimit.android.livedata.ignoreUnchanged
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache

object TimesWidgetContentLoader {
    private const val MINUTE = 60_000L

    fun with(logic: AppLogic): LiveData<TimesWidgetContent> {
        val database = logic.database
        val realTimeLogic = logic.realTimeLogic
        val realTime = RealTime.newInstance()
        val categoryHandlingCache = CategoryHandlingCache()
        val handler = Threads.mainThreadHandler

        val deviceAndUserRelatedDataLive = database.derivedDataDao().getUserAndDeviceRelatedDataLive()
        var deviceAndUserRelatedDataLiveLoaded = false

        val batteryStatusLive = logic.platformIntegration.getBatteryStatusLive()

        lateinit var timeModificationListener: () -> Unit
        lateinit var updateByClockRunnable: Runnable
        var isActive = false

        val newResult = object: MediatorLiveData<TimesWidgetContent>() {
            override fun onActive() {
                super.onActive()

                isActive = true

                realTimeLogic.registerTimeModificationListener(timeModificationListener)

                // ensure that the next update gets scheduled
                updateByClockRunnable.run()
            }

            override fun onInactive() {
                super.onInactive()

                isActive = false

                realTimeLogic.unregisterTimeModificationListener(timeModificationListener)
                handler.removeCallbacks(updateByClockRunnable)
            }
        }

        // ponytail: titles of every launchable app of an open category, system apps of «Всегда можно» included
        // @tag:new-ui
        fun openApps(user: UserRelatedData): List<String> {
            val open = user.categories.map { it.category.id }
                .filterNot { CategoryChain(user, it, categoryHandlingCache).closedNow }
                .toSet()
            val packageManager = logic.context.packageManager

            return user.categoryApps.filter { it.categoryId in open }
                .map { it.appSpecifier.packageName }
                .distinct()
                .filter { it != logic.context.packageName && packageManager.getLaunchIntentForPackage(it) != null }
                .mapNotNull { logic.platformIntegration.getLocalAppTitle(it) }
                .sorted()
        }

        fun update() {
            handler.removeCallbacks(updateByClockRunnable)

            if (!deviceAndUserRelatedDataLiveLoaded) { return }

            val deviceAndUserRelatedData = deviceAndUserRelatedDataLive.value
            val userRelatedData = deviceAndUserRelatedData?.userRelatedData
            val canSwitchToDefaultUser = deviceAndUserRelatedData?.deviceRelatedData?.canSwitchToDefaultUser ?: false

            if (deviceAndUserRelatedData == null) {
                newResult.value = TimesWidgetContent.UnconfiguredDevice

                return
            } else if (userRelatedData?.user?.type != UserType.Child) {
                newResult.value = TimesWidgetContent.NoChildUser(
                    canSwitchToDefaultUser = canSwitchToDefaultUser
                )

                return
            }

            realTimeLogic.getRealTime(realTime)

            categoryHandlingCache.reportStatus(
                user = userRelatedData,
                timeInMillis = realTime.timeInMillis,
                batteryStatus = logic.platformIntegration.getBatteryStatus(),
                shouldTrustTimeTemporarily = realTime.shouldTrustTimeTemporarily,
                assumeCurrentDevice = true,
                currentNetworkId = null, // not relevant here
                hasPremiumOrLocalMode = false   // not relevant here
            )

            var maxTime = Long.MAX_VALUE
            val now = realTime.timeInMillis
            val minuteStart = now - now % MINUTE
            val date = DateInTimezone.newInstance(now, userRelatedData.timeZone)

            // @tag:new-ui
            val categories = userRelatedData.sortedCategories().mapNotNull { (_, category) ->
                val handling = categoryHandlingCache.get(categoryId = category.category.id)
                val limits = CategoryLimits(category.rules, category.usedTimes, date, now)

                maxTime = maxTime.coerceAtMost(handling.dependsOnMaxTime)

                TimesWidgetModel.item(
                    categoryId = category.category.id,
                    title = category.category.title,
                    remaining = CategoryChain(userRelatedData, category.category.id, categoryHandlingCache).remaining,
                    dayLimit = limits.dayLimit,
                    week = limits.week
                )
            }

            // @tag:new-ui
            val mode = TimesWidgetModel.mode(userRelatedData.categories, getMinuteOfWeek(now, userRelatedData.timeZone), minuteStart)
                ?.let { if (it.startsIn == null) it.copy(openApps = openApps(userRelatedData)) else it }

            // the ring and «через N мин» change every minute
            if (mode != null) maxTime = maxTime.coerceAtMost(minuteStart + MINUTE)

            newResult.value = TimesWidgetContent.Categories(
                categories = categories,
                mode = mode,
                canSwitchToDefaultUser = canSwitchToDefaultUser
            )

            if (isActive && maxTime != Long.MAX_VALUE) {
                val delay = maxTime - realTime.timeInMillis

                handler.postDelayed(updateByClockRunnable, delay)
            }
        }

        timeModificationListener = { update() }
        updateByClockRunnable = Runnable { update() }

        newResult.addSource(deviceAndUserRelatedDataLive) { deviceAndUserRelatedDataLiveLoaded = true; update() }
        newResult.addSource(batteryStatusLive) { update() }

        return newResult.ignoreUnchanged()
    }
}