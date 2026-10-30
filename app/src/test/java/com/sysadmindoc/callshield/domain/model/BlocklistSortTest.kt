package com.sysadmindoc.callshield.domain.model

import com.sysadmindoc.callshield.data.model.SpamNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class BlocklistSortTest {
    private val older = SpamNumber(id = 1, number = "+12125550103", type = "unknown", isUserBlocked = true, blockedAt = 1_000L)
    private val newer = SpamNumber(id = 2, number = "+12125550101", type = "unknown", isUserBlocked = true, blockedAt = 2_000L)
    private val untimedLow = SpamNumber(id = 3, number = "+12125550104", type = "unknown", isUserBlocked = true)
    private val untimedHigh = SpamNumber(id = 4, number = "+12125550102", type = "unknown", isUserBlocked = true)
    private val numbers = listOf(untimedLow, older, untimedHigh, newer)

    @Test
    fun `newest first puts blocks made before block times existed last, newest row first`() {
        assertEquals(listOf(newer, older, untimedHigh, untimedLow), BlocklistSort.NEWEST.sort(numbers, emptyMap()))
    }

    @Test
    fun `by number keeps the order the list always had`() {
        assertEquals(listOf(newer, untimedHigh, older, untimedLow), BlocklistSort.NUMBER.sort(numbers, emptyMap()))
    }

    @Test
    fun `most blocked counts each number's blocked calls, ties in number order`() {
        val counts = mapOf(older.number to 5, untimedLow.number to 2, newer.number to 2)

        assertEquals(listOf(older, newer, untimedLow, untimedHigh), BlocklistSort.MOST_BLOCKED.sort(numbers, counts))
    }

    @Test
    fun `a stored sort reads back, and a missing or unknown one falls back to number order`() {
        BlocklistSort.entries.forEach { assertEquals(it, BlocklistSort.fromStored(it.storedValue)) }
        assertEquals(BlocklistSort.NUMBER, BlocklistSort.fromStored(null))
        assertEquals(BlocklistSort.NUMBER, BlocklistSort.fromStored("oldest"))
    }
}
