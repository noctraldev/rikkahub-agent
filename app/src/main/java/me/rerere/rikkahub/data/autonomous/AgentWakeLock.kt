package me.rerere.rikkahub.data.autonomous

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a time-bounded partial lock; callers must release in every terminal path. */
class AgentWakeLock(context: Context, private val timeoutMs: Long = 10 * 60 * 1000L) {
    private val power = context.applicationContext.getSystemService(PowerManager::class.java)
    private val held = AtomicBoolean(false)
    private val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RikkaHub::AutonomousAgent")?.apply { setReferenceCounted(false) }
    fun acquire() { if (held.compareAndSet(false, true)) lock?.acquire(timeoutMs) }
    fun release() { if (held.compareAndSet(true, false) && lock?.isHeld == true) lock.release() }
    fun isHeld(): Boolean = held.get() && (lock?.isHeld == true)
}
