package io.timelimit.android.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentCodeTotpTest {
    // RFC 6238, appendix B: secret "12345678901234567890", T = 59 s gives 94287082 (8 digits)
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun rfcVectorAndNeighbourStep() {
        assertEquals(1L, ParentCodeTotp.matchingStep(secret, "287082", 59_000))
        assertEquals(1L, ParentCodeTotp.matchingStep(secret, "287082", 89_000))
        assertNull(ParentCodeTotp.matchingStep(secret, "287083", 59_000))
    }
}
