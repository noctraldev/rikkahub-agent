package me.rerere.rikkahub.data.autonomous

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Small, deterministic execution contract used by autonomous UI/workspace integrations. */
data class AutonomousStep(val id: String, val title: String, val action: String = "")
enum class AutonomousStepStatus { PENDING, ACTIVE, SUCCEEDED, FAILED, SKIPPED }
data class AutonomousStepState(val step: AutonomousStep, val status: AutonomousStepStatus = AutonomousStepStatus.PENDING, val attempts: Int = 0, val observation: String? = null)
data class AutonomousTaskState(val goal: String, val steps: List<AutonomousStepState>, val status: Status = Status.PLANNING, val error: String? = null) {
    enum class Status { PLANNING, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED, LIMIT_REACHED }
}

fun interface AutonomousPlanner { suspend fun plan(goal: String): List<AutonomousStep> }
fun interface AutonomousAction { suspend fun execute(step: AutonomousStep): String }

/**
 * Genuine PLAN -> ACT -> OBSERVE -> EVALUATE loop. It does not synthesize longer replies: every
 * step executes an action, records its observation, retries bounded failures, and exposes state.
 */
class AutonomousAgentLoop(private val maxSteps: Int = 20, private val maxAttemptsPerStep: Int = 2) {
    init { require(maxSteps in 1..100); require(maxAttemptsPerStep in 1..5) }
    suspend fun run(goal: String, planner: AutonomousPlanner, action: AutonomousAction, onState: (AutonomousTaskState) -> Unit = {}): AutonomousTaskState {
        require(goal.isNotBlank())
        val planned = planner.plan(goal).take(maxSteps)
        if (planned.isEmpty()) return AutonomousTaskState(goal, emptyList(), AutonomousTaskState.Status.FAILED, "Planner returned no steps")
        var states = planned.map(::AutonomousStepState)
        var current = AutonomousTaskState(goal, states, AutonomousTaskState.Status.RUNNING)
        onState(current)
        for (index in states.indices) {
            currentCoroutineContext().ensureActive()
            states = states.mapIndexed { i, s -> if (i == index) s.copy(status = AutonomousStepStatus.ACTIVE) else s }
            onState(current.copy(steps = states))
            var success = false
            var lastError: String? = null
            for (attempt in 1..maxAttemptsPerStep) {
                currentCoroutineContext().ensureActive()
                states = states.mapIndexed { i, s -> if (i == index) s.copy(attempts = attempt) else s }
                onState(current.copy(steps = states))
                try {
                    val observation = action.execute(states[index].step)
                    states = states.mapIndexed { i, s -> if (i == index) s.copy(status = AutonomousStepStatus.SUCCEEDED, observation = observation) else s }
                    success = true
                    onState(current.copy(steps = states))
                    break
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t.message ?: t::class.simpleName
                }
            }
            if (!success) {
                states = states.mapIndexed { i, s -> if (i == index) s.copy(status = AutonomousStepStatus.FAILED, observation = lastError) else s }
                val failed = current.copy(steps = states, status = AutonomousTaskState.Status.FAILED, error = lastError)
                onState(failed)
                return failed
            }
        }
        return current.copy(steps = states, status = AutonomousTaskState.Status.COMPLETED)
            .also(onState)
    }
}
