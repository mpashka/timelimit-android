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

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.map
import io.timelimit.android.R
import io.timelimit.android.async.Threads
import io.timelimit.android.child.WidgetClickActivity
import io.timelimit.android.livedata.mergeLiveDataWaitForValues
import io.timelimit.android.logic.DefaultAppLogic
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Bar
import io.timelimit.android.ui.widget.TimesWidgetContent.Categories.Mode
import io.timelimit.ui.child.formatClock
import io.timelimit.ui.child.modeNameId
import io.timelimit.ui.R as UiR

class TimesWidgetService: RemoteViewsService() {
    companion object {
        private const val EXTRA_APP_WIDGET_ID = "appWidgetId"

        fun intent(context: Context, appWidgetId: Int) = Intent(context, TimesWidgetService::class.java)
            .setData(Uri.parse("widget:$appWidgetId"))
            .putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)

        fun notifyContentChanges(context: Context) {
            context.getSystemService<AppWidgetManager>()?.also { appWidgetManager ->
                val widgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, TimesWidgetProvider::class.java))

                appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, android.R.id.list)
            }
        }
    }

    private val content: LiveData<Pair<TimesWidgetContent, TimesWidgetConfig>> by lazy {
        val logic = DefaultAppLogic.with(this)

        val content = TimesWidgetContentLoader.with(logic)
        val config = logic.database.widgetCategory().queryLive().map { TimesWidgetConfig(it) }

        mergeLiveDataWaitForValues(content, config)
    }

    private var observerCounter = 0
    private var contentInput: Pair<TimesWidgetContent, TimesWidgetConfig>? = null

    private val contentObserver = Observer<Pair<TimesWidgetContent, TimesWidgetConfig>> {
        contentInput = it

        notifyContentChanges(this)
    }

    private fun createFactory(appWidgetId: Int) = object : RemoteViewsFactory {
        private var currentItems: List<TimesWidgetItem> = emptyList()

        init { onDataSetChanged() }

        override fun onCreate() {
            Threads.mainThreadHandler.post {
                if (observerCounter < 0) throw IllegalStateException()
                else if (observerCounter == 0) content.observeForever(contentObserver)

                observerCounter++
            }
        }

        override fun onDestroy() {
            Threads.mainThreadHandler.post {
                if (observerCounter <= 0) throw IllegalStateException()
                else if (observerCounter == 1) content.removeObserver(contentObserver)

                observerCounter--
            }
        }

        override fun onDataSetChanged() {
            currentItems = contentInput?.let { TimesWidgetItems.with(it.first, it.second, appWidgetId) } ?: emptyList()
        }

        override fun getCount(): Int = currentItems.size

        // @tag:new-ui
        override fun getViewAt(position: Int): RemoteViews {
            val item = currentItems.getOrNull(position) ?: return RemoteViews(packageName, R.layout.widget_times_category_item)

            return when (item) {
                is TimesWidgetItem.Category -> categoryItem(item.category.categoryName, item.category.day, item.category.week)
                is TimesWidgetItem.Mode -> modeItem(item.mode)
                is TimesWidgetItem.TextMessage -> categoryItem(getString(item.textRessourceId), null, null)
                is TimesWidgetItem.DefaultUserButton -> RemoteViews(packageName, R.layout.widget_times_button).also { result ->
                    result.setTextViewText(R.id.button, getString(R.string.manage_device_default_user_switch_btn))
                    result.setOnClickFillInIntent(R.id.button, WidgetClickActivity.switchToDefaultUser())
                }
            }
        }

        override fun getLoadingView(): RemoteViews?  = null

        override fun getViewTypeCount(): Int = 3

        override fun getItemId(position: Int): Long {
            if (position >= currentItems.size) {
                return -(position.toLong())
            }

            val item = currentItems[position]

            return when (item) {
                is TimesWidgetItem.Category -> item.category.categoryId.hashCode()
                else -> item.hashCode()
            }.toLong()
        }

        override fun hasStableIds(): Boolean = true
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = createFactory(intent.getIntExtra(EXTRA_APP_WIDGET_ID, 0))

    // @tag:new-ui
    private fun categoryItem(title: String, day: Bar?, week: Bar?) = RemoteViews(packageName, R.layout.widget_times_category_item).also { result ->
        result.setOnClickFillInIntent(R.id.widgetInnerContainer, Intent())
        result.setTextViewText(R.id.title, title)
        showBar(result, day, R.id.dayBar, R.id.dayProgress, R.id.dayOver)
        showBar(result, week, R.id.weekBar, R.id.weekProgress, R.id.weekOver)
    }

    // RemoteViews cannot tint a ProgressBar below API 31: the red bar is a second view
    private fun showBar(views: RemoteViews, bar: Bar?, container: Int, progress: Int, over: Int) {
        views.setViewVisibility(container, if (bar == null) View.GONE else View.VISIBLE)
        if (bar == null) return

        views.setProgressBar(progress, 100, bar.usedPercent, false)
        views.setViewVisibility(progress, if (bar.over) View.GONE else View.VISIBLE)
        views.setViewVisibility(over, if (bar.over) View.VISIBLE else View.GONE)
    }

    // @tag:new-ui
    private fun modeItem(mode: Mode) = RemoteViews(packageName, R.layout.widget_times_mode_item).also { result ->
        val name = getString(modeNameId(mode.kind))
        val ring = when {
            mode.startsIn == null -> R.id.ringMode
            mode.startsIn <= TimesWidgetModel.SOON_MINUTES -> R.id.ringSoon
            else -> R.id.ringNormal
        }

        result.setOnClickFillInIntent(R.id.widgetInnerContainer, Intent())
        result.setTextViewText(R.id.modeText, when {
            mode.startsIn == null -> getString(UiR.string.widget_mode_until, name.replaceFirstChar { it.uppercase() }, formatClock(mode.until))
            mode.startsIn <= TimesWidgetModel.SOON_MINUTES -> getString(UiR.string.widget_mode_soon, mode.startsIn, name, formatClock(mode.until))
            else -> getString(UiR.string.widget_mode_at, name.replaceFirstChar { it.uppercase() }, formatClock(mode.from))
        })
        listOf(R.id.ringNormal, R.id.ringSoon, R.id.ringMode).forEach {
            result.setViewVisibility(it, if (it == ring) View.VISIBLE else View.GONE)
        }
        result.setProgressBar(ring, 100, mode.leftPercent, false)
    }
}