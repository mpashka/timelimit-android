package io.timelimit.android.child

import io.timelimit.api.App
import io.timelimit.api.AppTime
import io.timelimit.api.CategoryRef
import io.timelimit.api.ParentCategory
import io.timelimit.api.appsTimeOfCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTimeTest {
    private val minute = 60_000L

    private fun category(id: String, parentId: String?) = ParentCategory(
        ref = CategoryRef(id, id), depth = if (parentId == null) 0 else 1, parentId = parentId,
        remaining = null, usedToday = 0, limit = null, week = null, closedByModeUntil = null,
        closedByParentUntil = null, closedByParent = false, allowedUntil = null
    )

    private val categories = listOf(
        category("games", null),
        category("shooters", "games"),
        category("retro", "shooters"),
        category("school", null),
    )

    private fun app(packageName: String, categoryId: String?, vararg byTablet: Pair<String, Long>) = AppTime(
        app = App(packageName, packageName),
        ms = byTablet.sumOf { it.second },
        category = categoryId?.let { CategoryRef(it, it) },
        byTablet = byTablet.toMap(),
        rule = null
    )

    private val apps = listOf(
        app("game", "games", "legion" to 10 * minute),
        app("shooter", "shooters", "legion" to 20 * minute, "redmi" to 5 * minute),
        app("oldschool", "retro", "redmi" to 30 * minute),
        app("lesson", "school", "redmi" to 40 * minute),
        app("launcher", null, "legion" to 50 * minute),
    )

    @Test
    fun `apps of the category and of every category below it`() {
        assertEquals(65 * minute, appsTimeOfCategory(categories, "games", apps))
        assertEquals(55 * minute, appsTimeOfCategory(categories, "shooters", apps))
        assertEquals(30 * minute, appsTimeOfCategory(categories, "retro", apps))
    }

    @Test
    fun `an app without a category belongs to nobody`() {
        assertEquals(40 * minute, appsTimeOfCategory(categories, "school", apps))
    }

    @Test
    fun `one tablet takes only its own time`() {
        assertEquals(30 * minute, appsTimeOfCategory(categories, "games", apps, tablet = "legion"))
        assertEquals(35 * minute, appsTimeOfCategory(categories, "games", apps, tablet = "redmi"))
        assertEquals(0L, appsTimeOfCategory(categories, "games", apps, tablet = "unknown"))
    }

    @Test
    fun `a category loop does not hang the sum`() {
        val looped = listOf(category("games", "shooters"), category("shooters", "games"))

        assertEquals(35 * minute, appsTimeOfCategory(looped, "games", apps))
    }
}
