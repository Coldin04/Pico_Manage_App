package com.cold04.inkreadermgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IncomingFilesInboxTest {
    @Test
    fun repeatedEqualSharesAreDistinctEvents() {
        val inbox = IncomingFilesInbox<String>()

        inbox.publish("book.epub")
        val first = requireNotNull(inbox.state.value)
        inbox.publish("book.epub")
        val second = requireNotNull(inbox.state.value)

        assertNotEquals(first.id, second.id)
        assertEquals("book.epub", second.value)
    }

    @Test
    fun staleConsumerCannotClearANewerShare() {
        val inbox = IncomingFilesInbox<String>()

        inbox.publish("first.epub")
        val first = requireNotNull(inbox.state.value)
        inbox.publish("second.epub")
        val second = requireNotNull(inbox.state.value)
        inbox.consume(first.id)

        assertEquals(second, inbox.state.value)
    }
}
