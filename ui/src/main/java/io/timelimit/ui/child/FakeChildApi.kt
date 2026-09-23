package io.timelimit.ui.child

import io.timelimit.api.App
import io.timelimit.api.AppAccess
import io.timelimit.api.CategoryToday
import io.timelimit.api.ChildApi
import io.timelimit.api.CloseReason
import io.timelimit.api.GrantChoice
import io.timelimit.api.GrantScope
import io.timelimit.api.ModeKind
import io.timelimit.api.ModeWindow
import io.timelimit.api.ParentCode
import io.timelimit.api.Request
import io.timelimit.api.Today
import io.timelimit.api.WaitingRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.ZoneId

/** The family example of docs/specification/ui-contract.md: Tuesday, 18:20. For previews and tests. */
class FakeChildApi : ChildApi {
    companion object {
        val NOW: Long = LocalDateTime.of(2026, 9, 22, 18, 20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        private const val MINUTE = 60_000L

        val minecraft = App("com.mojang.minecraftpe", "Minecraft")
        private val brawlStars = App("com.supercell.brawlstars", "Brawl Stars")
        private val roblox = App("com.roblox.client", "Roblox")
        private val youtube = App("com.google.android.youtube", "YouTube")
        private val duolingo = App("com.duolingo", "Duolingo")
        private val uchiru = App("ru.uchi.app", "Учи.ру")
        private val chrome = App("com.android.chrome", "Chrome")
        private val camera = App("com.android.camera", "Камера")

        fun closed(reason: CloseReason, request: Request) = AppAccess.Closed(
            app = minecraft,
            categoryTitle = "Игры",
            reason = reason,
            opensAt = NOW + (12 * 60 + 40) * MINUTE,
            remainingToday = null,
            request = request,
            grant = GrantChoice("Игры", NOW + 160 * MINUTE),
        )

        val exampleToday = Today(
            now = NOW,
            categories = listOf(
                CategoryToday("Игры", 18 * MINUTE, false, listOf(minecraft, brawlStars, roblox)),
                CategoryToday("Видео", 22 * MINUTE, false, listOf(youtube)),
                CategoryToday("Учёба", null, false, listOf(duolingo, uchiru)),
                CategoryToday("Всегда можно", null, false, listOf(chrome, camera)),
            ),
            waiting = listOf(WaitingRequest(roblox, NOW - 6 * MINUTE)),
            nextMode = ModeWindow(ModeKind.Sleep, NOW + 160 * MINUTE, NOW + 760 * MINUTE),
        )
    }

    private val request = MutableStateFlow<Request>(Request.None)

    override fun appAccess(packageName: String): Flow<AppAccess> =
        request.map { closed(CloseReason.LimitOver, it) }

    override val today: Flow<Today> = MutableStateFlow(exampleToday)

    override suspend fun ask(packageName: String, word: String) {
        request.value = Request.Sent(System.currentTimeMillis(), word)
    }

    override suspend fun checkParentCode(code: String): ParentCode? = if (code == "417000") ParentCode(code, 0) else null

    override suspend fun grant(packageName: String, code: ParentCode, scope: GrantScope, until: Long) {}
}
