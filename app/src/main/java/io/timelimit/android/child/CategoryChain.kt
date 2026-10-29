package io.timelimit.android.child

import io.timelimit.android.data.model.derived.UserRelatedData
import io.timelimit.android.logic.blockingreason.CategoryHandlingCache

/**
 * Time left of a category as its apps get it: they spend the time of every ancestor too, so the
 * least of the chain closes them. [limitedBy] — the ancestor with less left than the category.
 */
// @tag:category-limits
class CategoryChain(user: UserRelatedData, categoryId: String, cache: CategoryHandlingCache) {
    private val ancestors = generateSequence(user.categoryById[user.categoryById[categoryId]?.category?.parentCategoryId]) {
        user.categoryById[it.category.parentCategoryId]
    }.takeWhile { it.category.id != categoryId }.take(10).toList()
    private val own = cache.get(categoryId)
    private val tighter = ancestors
        .mapNotNull { ancestor -> cache.get(ancestor.category.id).remainingTime?.includingExtraTime?.let { ancestor.category.title to it } }
        .minByOrNull { it.second }
        ?.takeIf { it.second < (own.remainingTime?.includingExtraTime ?: Long.MAX_VALUE) }

    val remaining: Long? = tighter?.second ?: own.remainingTime?.includingExtraTime
    val limitedBy: String? = tighter?.first
    val closedNow: Boolean = own.shouldBlockActivities || ancestors.any { cache.get(it.category.id).shouldBlockActivities }
}
