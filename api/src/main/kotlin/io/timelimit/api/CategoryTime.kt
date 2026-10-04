package io.timelimit.api

/**
 * A tablet counts a category's own time only while a rule of that category is active, the time of an
 * app always. So the time of a category without a limit is the time of its apps and of the apps of
 * every category below it.
 *
 * [tablet] null — the whole day, otherwise only what the named tablet spent.
 */
// @tag:category-time
fun appsTimeOfCategory(categories: List<ParentCategory>, categoryId: String, apps: List<AppTime>, tablet: String? = null): Long {
    val ids = descendantIds(categories, categoryId)

    return apps.filter { it.category != null && it.category.id in ids }
        .sumOf { app -> if (tablet == null) app.ms else app.byTablet[tablet] ?: 0 }
}

/** The category itself and everything below it, however deep. */
fun descendantIds(categories: List<ParentCategory>, categoryId: String): Set<String> {
    val children = categories.groupBy({ it.parentId }, { it.ref.id })
    val ids = mutableSetOf(categoryId)
    val toVisit = ArrayDeque(listOf(categoryId))

    while (toVisit.isNotEmpty()) {
        children[toVisit.removeFirst()].orEmpty().forEach { if (ids.add(it)) toVisit.addLast(it) }
    }

    return ids
}
