/*
 * TimeLimit Copyright <C> 2019 - 2026 Jonas Lochmann
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
package io.timelimit.android.ui.model.managechild

import io.timelimit.android.async.Threads
import io.timelimit.android.coroutines.executeAndWait
import io.timelimit.android.data.extensions.getTimezone
import io.timelimit.android.logic.AppLogic
import io.timelimit.android.logic.ForegroundTimeAggregation
import io.timelimit.android.logic.RealTime
import io.timelimit.android.logic.blockingreason.AppBaseHandling
import io.timelimit.android.logic.blockingreason.CategoryItselfHandling
import io.timelimit.android.ui.model.BackStackItem
import io.timelimit.android.ui.model.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate

// @tag:category-limits
object ManageChildAppUsage {
    const val MAX_DAYS_AGO = 6
    private const val REFRESH_INTERVAL = 60 * 1000L

    enum class Counting { Counted, NoActiveRules, TemporarilyAllowed, Whitelisted, NoCategory }

    data class Item(
        val packageName: String,
        val title: String,
        val duration: Long,
        val categoryTitle: String?,
        val counting: Counting
    )

    data class Screen(
        val daysAgo: Int,
        val day: LocalDate,
        val isDeviceUser: Boolean,
        val items: List<Item>,
        val selectDaysAgo: (Int) -> Unit
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun handle(
        logic: AppLogic,
        childId: String,
        stateLive: SharedFlow<State.ManageChild.AppUsage>,
        updateState: ((State.ManageChild.AppUsage) -> State) -> Unit,
        parentBackStackLive: Flow<List<BackStackItem>>
    ): Flow<io.timelimit.android.ui.model.Screen> {
        val selectDaysAgo: (Int) -> Unit = { daysAgo -> updateState { State.ManageChild.AppUsage(it.previousChild, daysAgo.coerceIn(0, MAX_DAYS_AGO)) } }

        val contentLive = stateLive.map { it.daysAgo }.distinctUntilChanged().transformLatest { daysAgo ->
            while (true) {
                emit(load(logic, childId, daysAgo, selectDaysAgo))

                if (daysAgo != 0) break

                delay(REFRESH_INTERVAL)
            }
        }

        return combine(stateLive, contentLive, parentBackStackLive) { state, content, backStack ->
            io.timelimit.android.ui.model.Screen.ManageChildAppUsage(state, content, backStack)
        }
    }

    private suspend fun load(logic: AppLogic, childId: String, daysAgo: Int, selectDaysAgo: (Int) -> Unit): Screen {
        val (userRelatedData, deviceAndUserRelatedData) = Threads.database.executeAndWait {
            Pair(
                logic.database.derivedDataDao().getUserRelatedDataSync(childId),
                logic.database.derivedDataDao().getUserAndDeviceRelatedDataSync()
            )
        }

        val time = RealTime.newInstance().also { logic.realTimeLogic.getRealTime(it) }
        val zone = userRelatedData?.user.getTimezone().toZoneId()
        val day = Instant.ofEpochMilli(time.timeInMillis).atZone(zone).toLocalDate().minusDays(daysAgo.toLong())
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(time.timeInMillis)
        val deviceRelatedData = deviceAndUserRelatedData?.deviceRelatedData

        val items = Threads.backgroundOSInteraction.executeAndWait {
            val durations = ForegroundTimeAggregation.foregroundTime(logic.context, start, end)
            val battery = logic.platformIntegration.getBatteryStatus()

            durations.map { (packageName, duration) ->
                val handling = if (userRelatedData != null && deviceRelatedData != null) AppBaseHandling.calculate(
                    foregroundAppPackageName = packageName,
                    foregroundAppActivityName = null,
                    pauseForegroundAppBackgroundLoop = false,
                    pauseCounting = false,
                    userRelatedData = userRelatedData,
                    deviceRelatedData = deviceRelatedData,
                    isSystemImageApp = logic.platformIntegration.isSystemImageApp(packageName)
                ) else AppBaseHandling.BlockDueToNoCategory

                val categoryTitle = (handling as? AppBaseHandling.UseCategories)?.let {
                    userRelatedData?.categoryById?.get(it.categoryIds.first())?.category?.title
                }

                // ponytail: the counting status is evaluated for now, not for the selected day
                val counting = when (handling) {
                    is AppBaseHandling.Whitelist -> Counting.Whitelisted
                    AppBaseHandling.TemporarilyAllowed -> Counting.TemporarilyAllowed
                    is AppBaseHandling.UseCategories -> if (handling.categoryIds.any { categoryId ->
                        CategoryItselfHandling.calculate(
                            categoryRelatedData = userRelatedData!!.categoryById[categoryId]!!,
                            user = userRelatedData,
                            batteryStatus = battery,
                            shouldTrustTimeTemporarily = time.shouldTrustTimeTemporarily,
                            timeInMillis = time.timeInMillis,
                            assumeCurrentDevice = true,
                            currentNetworkId = null,
                            hasPremiumOrLocalMode = true
                        ).shouldCountTime
                    }) Counting.Counted else Counting.NoActiveRules
                    else -> Counting.NoCategory
                }

                Item(
                    packageName = packageName,
                    title = logic.platformIntegration.getLocalAppTitle(packageName) ?: packageName,
                    duration = duration,
                    categoryTitle = categoryTitle,
                    counting = counting
                )
            }.sortedByDescending { it.duration }
        }

        return Screen(
            daysAgo = daysAgo,
            day = day,
            isDeviceUser = deviceRelatedData?.deviceEntry?.currentUserId == childId,
            items = items,
            selectDaysAgo = selectDaysAgo
        )
    }
}
