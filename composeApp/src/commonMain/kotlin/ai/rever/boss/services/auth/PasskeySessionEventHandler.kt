package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * PasskeySessionEventHandler - Handles passkey session completion events from deep links
 *
 * This service coordinates cross-device passkey flows by:
 * - Tracking active passkey sessions by sessionId
 * - Notifying listeners when passkey operations complete via deep links
 * - Triggering appropriate UI updates and authentication completion
 *
 * Concurrency: every operation on the session registry is individually
 * atomic - concurrent-map access, and [MutableStateFlow.value] assignment.
 * That is all this object guarantees: a [getSessionMetadata] read returns
 * null or one thread's fully published record, whatever other threads are
 * doing. It does not make anything atomic across operations - a completion
 * can still observe the absence of a session another thread is part way
 * through registering, and two overlapping completions emit their events
 * in last-writer-wins order. No lock is held anywhere in this object.
 */
object PasskeySessionEventHandler {
    private val logger = BossLogger.forComponent("PasskeySessionEventHandler")

    /**
     * Passkey session event types
     */
    sealed class PasskeySessionEvent {
        data class RegistrationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()

        data class AuthenticationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()
    }

    /**
     * Flow of passkey session events
     */
    private val _sessionEvents = MutableStateFlow<PasskeySessionEvent?>(null)

    /**
     * Map of active sessions being tracked
     * Key: sessionId, Value: session metadata
     *
     * Deep-link completion callbacks register and look up sessions from
     * whatever thread delivers the callback, while UI composition reads
     * through [getSessionMetadata] while the cross-device QR dialog is up
     * (#1269). This class only ever performs single-key gets and puts - it
     * never iterates the map - so on the old plain map neither of the
     * originally named hazards was reachable: HashMap.get cannot throw
     * ConcurrentModificationException without iteration, and
     * [SessionMetadata] is immutable, so final-field semantics publish it
     * whole. What the unsynchronized map could not survive is the real
     * hazard pair: a get concurrent with a table resize can return null for
     * a key that was inserted - a lost lookup that silently strands the
     * ceremony - and a put on the callback thread carries no happens-before
     * to a later get on the UI thread. The concurrent registry rules both
     * out; PasskeySessionEventHandlerConcurrencyTest pins that by failing
     * on mutableMapOf.
     */
    private val activeSessions = ConcurrentHashMap<String, SessionMetadata>()

    data class SessionMetadata(
        val sessionId: String,
        val email: String,
        val type: SessionType,
        val timestamp: Long = System.currentTimeMillis(),
    )

    enum class SessionType {
        /**
         * Cross-device passkey registration ceremony (QR shown, waiting for enrolment).
         */
        REGISTRATION,

        /**
         * Cross-device passkey authentication ceremony (QR shown, waiting for sign-in).
         */
        AUTHENTICATION,
    }

    /**
     * Track a ceremony session as active.
     *
     * Called from the thread that opens a cross-device passkey ceremony,
     * before the QR dialog starts reading the session. The put is atomic on
     * the concurrent registry, so a completion arriving on another thread
     * observes either the previous or the new record, never a half-written
     * one.
     *
     * Retention boundary: a tracked record holds the user's email (PII) in
     * a process-lifetime singleton, and this object has no removal or
     * expiry verb - nothing retires a session on completion, dialog close,
     * cancel or sign-out. There is no production caller yet; whoever wires
     * one must pair it with that lifecycle, so ceremonies do not leave
     * emails resident for the life of the process.
     */
    internal fun trackSession(
        sessionId: String,
        email: String,
        type: SessionType,
    ): SessionMetadata {
        val metadata = SessionMetadata(sessionId = sessionId, email = email, type = type)
        activeSessions[sessionId] = metadata
        return metadata
    }

    /**
     * Handle passkey registration completion from deep link
     */
    fun handleRegistrationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Registration completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.RegistrationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of registration completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for registration completion")
        }
    }

    /**
     * Handle passkey authentication completion from deep link
     */
    fun handleAuthenticationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Authentication completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.AuthenticationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of authentication completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for authentication completion")
        }
    }

    /**
     * Get metadata for an active session
     *
     * Called from UI composition while the QR dialog is up, possibly while a
     * deep-link completion callback or a registration is writing on another
     * thread (#1269). A single concurrent-registry read is wait-free and
     * cannot lose the key to a concurrent resize: it returns either null or
     * one thread's fully published [SessionMetadata].
     */
    fun getSessionMetadata(sessionId: String): SessionMetadata? = activeSessions[sessionId]
}
