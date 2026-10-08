package com.gustmmer.poker.server.timer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CloudTasksSchedulerTest {

    @Test
    fun `a task's schedule time keeps the milliseconds, so a timeout never fires before its deadline`() {
        val time = CloudTasksScheduler.scheduleTime(1_700_000_123_999)

        assertEquals(1_700_000_123, time.seconds)
        assertEquals(999_000_000, time.nanos)
    }
}
