package dev.bill.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessLocalOnlyDeclarationStateTest {
    @Test
    fun `declaration shows once per process state`() {
        val state = ProcessLocalOnlyDeclarationState()

        assertTrue(state.shouldShow())
        state.acknowledge()
        assertFalse(state.shouldShow())
    }

    @Test
    fun `new process state shows declaration again`() {
        ProcessLocalOnlyDeclarationState().apply { acknowledge() }

        assertTrue(ProcessLocalOnlyDeclarationState().shouldShow())
    }
}
