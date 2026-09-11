package me.rerere.rikkahub.data.autonomous

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAgentLoopTest {
    @Test fun retriesFailureThenObservesSuccess() = runBlocking {
        var attempts = 0
        val result = AutonomousAgentLoop(maxSteps = 3, maxAttemptsPerStep = 2).run(
            "ship it", AutonomousPlanner { listOf(AutonomousStep("build", "Build")) },
            AutonomousAction { attempts++; if (attempts == 1) error("temporary") else "green" },
        )
        assertEquals(AutonomousTaskState.Status.COMPLETED, result.status)
        assertEquals(2, result.steps.single().attempts)
        assertEquals("green", result.steps.single().observation)
    }

    @Test fun stopsAfterBoundedAttempts() = runBlocking {
        val result = AutonomousAgentLoop(maxAttemptsPerStep = 2).run(
            "fail", AutonomousPlanner { listOf(AutonomousStep("x", "X")) }, AutonomousAction { error("no") },
        )
        assertEquals(AutonomousTaskState.Status.FAILED, result.status)
        assertEquals(2, result.steps.single().attempts)
        assertTrue(result.error!!.contains("no"))
    }
}
