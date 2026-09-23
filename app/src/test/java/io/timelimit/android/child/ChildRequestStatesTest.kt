package io.timelimit.android.child

import io.timelimit.android.data.model.ChildRequest
import io.timelimit.android.data.model.ChildRequestAnswer
import io.timelimit.api.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class ChildRequestStatesTest {
    private val minute = 60_000L
    private val asked = ChildRequest("Ab12Cd", "com.roblox.client", "gAm3s1", "dEv1ce", "ещё полчасика", 0, 30 * minute, null)

    @Test
    fun waitingExpiredAndRefused() {
        val name = { _: String -> "Папа" }

        assertEquals(Request.Sent(0, "ещё полчасика"), ChildRequestStates.forApp(listOf(asked), asked.packageName, 10 * minute, name))
        assertEquals(Request.Expired("ещё полчасика"), ChildRequestStates.forApp(listOf(asked), asked.packageName, 31 * minute, name))

        val refused = asked.copy(answer = ChildRequestAnswer("deny", 0, "", "pApa01", 5 * minute, 35 * minute))
        assertEquals(Request.Refused(5 * minute, "Папа", null, 35 * minute), ChildRequestStates.forApp(listOf(refused), asked.packageName, 10 * minute, name))
    }
}
