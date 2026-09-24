package io.timelimit.android.child

/** Names the new interface shows for categories that still carry the upstream English defaults. */
// @tag:new-ui
object CategoryTitles {
    // ponytail: a display mapping only; renaming the categories in the family is the lasting fix,
    // the web console keeps showing the stored names
    private val names = mapOf(
        "allowed" to "Всегда можно",
        "allowed apps" to "Всегда можно",
        "разрешено" to "Всегда можно",
        "system" to "Системные",
        "allowed games" to "Игры",
        "games" to "Игры",
        "other" to "Прочее",
    )

    fun display(title: String): String = names[title.trim().lowercase()] ?: title
}
