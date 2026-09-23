package ai.rever.boss.services.auth

import ai.rever.boss.services.auth.PasskeySessionEventHandler.SessionType
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Concurrency tests for the passkey session registry (issue #1269).
 *
 * The registry behind [PasskeySessionEventHandler.getSessionMetadata] is
 * shared between the threads that deliver deep-link passkey completions
 * and UI composition reading session state for the cross-device QR dialog.
 * It was a plain `mutableMapOf` (a LinkedHashMap).
 *
 * The defect of the old map is not the one originally named. The handler
 * only ever performs single-key gets and puts - it never iterates the
 * registry - so HashMap.get could not throw
 * ConcurrentModificationException on this path, and because
 * [PasskeySessionEventHandler.SessionMetadata] is an immutable data class,
 * final-field semantics made a torn record impossible on either map. What
 * the unsynchronized map could not survive is a get concurrent with a
 * table resize returning null for a key that was inserted - a lost lookup
 * that silently strands the ceremony - plus the missing happens-before
 * between a put on one thread and a get on another. The concurrent
 * registration test below fails on mutableMapOf and passes on the
 * ConcurrentHashMap; the other three pin the interleavings of the issue as
 * invariants of the registry.
 */
class PasskeySessionEventHandlerConcurrencyTest {
    private fun newPool(threads: Int): ExecutorService = Executors.newFixedThreadPool(threads)

    /**
     * Worker failures surface here: an assertion blown inside a submitted
     * task (torn record, crash) rethrows from `get` and fails the test.
     */
    private fun awaitAll(futures: List<Future<Unit>>) {
        for (future in futures) {
            future.get(60, TimeUnit.SECONDS)
        }
    }

    private val sessionTypes = listOf(SessionType.REGISTRATION, SessionType.AUTHENTICATION)

    private fun typeFor(writer: Int): SessionType = sessionTypes[writer % 2]

    /**
     * One ceremony-writer task: re-registers its own session and the
     * contended session on every round, mirroring overlapping deep links.
     */
    private fun submitCeremonyWriter(
        pool: ExecutorService,
        start: CountDownLatch,
        writer: Int,
        contended: String,
    ): Future<Unit> =
        pool.submit(
            {
                start.await()
                repeat(250) {
                    PasskeySessionEventHandler.trackSession(
                        "solo-1269-$writer",
                        "writer-$writer-ceremony",
                        typeFor(writer),
                    )
                    PasskeySessionEventHandler.trackSession(
                        contended,
                        "writer-$writer-ceremony",
                        typeFor(writer),
                    )
                }
            },
            Unit,
        )

    /**
     * One QR-dialog reader task for the torn-record check: every observed
     * record must be one writer's complete (email, type) pair.
     */
    private fun submitTornRecordReader(
        pool: ExecutorService,
        start: CountDownLatch,
        contended: String,
        valid: List<Pair<String, SessionType>>,
    ): Future<Unit> =
        pool.submit(
            {
                start.await()
                repeat(500) {
                    val observed = PasskeySessionEventHandler.getSessionMetadata(contended)
                    if (observed != null) {
                        assertTrue(
                            observed.email to observed.type in valid,
                            "Torn record observed mid-race: $observed",
                        )
                    }
                }
            },
            Unit,
        )

    /**
     * The acceptance-criteria interleaving: N concurrent deep-link
     * completions against N concurrent QR-dialog reads on the same session
     * id — every read must return one writer's complete record, and the
     * session must survive the race.
     */
    @Test
    fun `deep-link completions racing QR-dialog reads on the same session stay consistent`() {
        val sessionId = "concurrent-completion-1269"
        val seed =
            PasskeySessionEventHandler.trackSession(
                sessionId,
                "agent@hunt-seat-2.local",
                SessionType.AUTHENTICATION,
            )
        val unknownId = "$sessionId-unknown"

        val pool = newPool(8)
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<Unit>>()
        try {
            repeat(4) {
                futures +=
                    pool.submit(
                        {
                            start.await()
                            repeat(500) { round ->
                                if (round % 2 == 0) {
                                    PasskeySessionEventHandler.handleRegistrationCompleted(sessionId)
                                } else {
                                    PasskeySessionEventHandler.handleAuthenticationCompleted(sessionId)
                                }
                            }
                        },
                        Unit,
                    )
            }
            repeat(4) {
                futures +=
                    pool.submit(
                        {
                            start.await()
                            repeat(500) {
                                val metadata = PasskeySessionEventHandler.getSessionMetadata(sessionId)
                                assertNotNull(metadata, "Tracked session must never disappear mid-race")
                                assertEquals(sessionId, metadata.sessionId)
                                assertEquals(seed.email, metadata.email)
                                assertEquals(seed.type, metadata.type)
                                assertEquals(seed.timestamp, metadata.timestamp)
                                assertNull(PasskeySessionEventHandler.getSessionMetadata(unknownId))
                            }
                        },
                        Unit,
                    )
            }
            start.countDown()
            awaitAll(futures)
        } finally {
            pool.shutdownNow()
        }

        val finalMetadata = PasskeySessionEventHandler.getSessionMetadata(sessionId)
        assertNotNull(finalMetadata, "Session must survive the completion race")
        assertEquals(seed, finalMetadata, "Registry record must be intact after the race")
    }

    /**
     * Two overlapping deep links must not trample each other's metadata:
     * every distinct session survives, and a contended session always reads
     * back as one writer's complete record — never a hybrid of two.
     */
    @Test
    fun `overlapping ceremony registrations cannot trample each other's metadata`() {
        val contended = "contended-ceremony-1269"
        val writers = 8
        val valid = (0 until writers).map { writer -> "writer-$writer-ceremony" to typeFor(writer) }

        val pool = newPool(writers + 2)
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<Unit>>()
        try {
            repeat(writers) { writer -> futures += submitCeremonyWriter(pool, start, writer, contended) }
            repeat(2) { futures += submitTornRecordReader(pool, start, contended, valid) }
            start.countDown()
            awaitAll(futures)
        } finally {
            pool.shutdownNow()
        }

        // Final-map consistency: no distinct session was lost to a concurrent put.
        repeat(writers) { writer ->
            val metadata = PasskeySessionEventHandler.getSessionMetadata("solo-1269-$writer")
            assertNotNull(metadata, "Distinct session $writer was lost to a concurrent put")
            assertEquals("writer-$writer-ceremony", metadata.email)
            assertEquals(typeFor(writer), metadata.type)
        }
        val contendedFinal = assertNotNull(PasskeySessionEventHandler.getSessionMetadata(contended))
        assertTrue(
            contendedFinal.email to contendedFinal.type in valid,
            "Contended session ended as a torn hybrid: $contendedFinal",
        )
    }

    /**
     * The issue's exact scenario: a `boss://` passkey completion callback
     * (the email magic-link / cross-device path on Linux cold start) landing
     * while the QR dialog is still reading session state — the dialog read
     * must not crash and must not observe a half-initialized record.
     */
    @Test
    fun `deep-link completion callback racing an open QR dialog read never crashes the read`() {
        val sessionId = "qr-dialog-1269"
        val seed =
            PasskeySessionEventHandler.trackSession(
                sessionId,
                "human@pair-seat.local",
                SessionType.AUTHENTICATION,
            )

        val pool = newPool(1)
        try {
            val callbackDone = CountDownLatch(1)
            val callback =
                pool.submit(
                    {
                        repeat(2000) { round ->
                            if (round % 2 == 0) {
                                PasskeySessionEventHandler.handleAuthenticationCompleted(sessionId)
                            } else {
                                PasskeySessionEventHandler.handleRegistrationCompleted(sessionId)
                            }
                            // Overlapping deep links re-register as they arrive.
                            if (round % 4 == 0) {
                                PasskeySessionEventHandler.trackSession(sessionId, seed.email, seed.type)
                            }
                        }
                        callbackDone.countDown()
                    },
                    Unit,
                )

            // UI-composition side: keep reading for the QR dialog while the
            // callback lands. Every read must see a complete record.
            var reads = 0
            while (!callbackDone.await(20, TimeUnit.MILLISECONDS) || reads < 100) {
                val metadata = PasskeySessionEventHandler.getSessionMetadata(sessionId)
                assertNotNull(metadata, "QR-dialog read must never see the session vanish")
                assertEquals(sessionId, metadata.sessionId)
                assertEquals(seed.email, metadata.email)
                assertEquals(seed.type, metadata.type)
                assertTrue(metadata.timestamp > 0, "Timestamp field must be initialized")
                reads++
            }
            callback.get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The actual defect of the unsynchronized registry, as a regression pin:
     * a get concurrent with a table resize can return null for an inserted
     * key, and concurrent puts racing the resize can orphan entries
     * outright. Writers cross the map's resize threshold several times
     * (~1e3 entries: the mutableMapOf LinkedHashMap defaults to 16 buckets
     * and resizes in place as it grows), each one reading back what it just
     * wrote, then every id is looked up again after the race. This test
     * fails against a plain `mutableMapOf` registry and passes against the
     * ConcurrentHashMap the handler now uses; it is the one assertion in
     * this class that can tell them apart.
     */
    @Test
    fun `concurrent registrations crossing the resize threshold lose no session`() {
        val writers = 8
        val idsPerWriter = 250

        val pool = newPool(writers)
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<Unit>>()
        try {
            repeat(writers) { writer ->
                futures +=
                    pool.submit(
                        {
                            start.await()
                            repeat(idsPerWriter) { round ->
                                val id = "resize-$writer-$round"
                                PasskeySessionEventHandler.trackSession(
                                    id,
                                    "writer-$writer@resize",
                                    typeFor(writer),
                                )
                                assertNotNull(
                                    PasskeySessionEventHandler.getSessionMetadata(id),
                                    "Session $id was lost to a concurrent registry resize",
                                )
                            }
                        },
                        Unit,
                    )
            }
            start.countDown()
            awaitAll(futures)
        } finally {
            pool.shutdownNow()
        }

        // 2000 sessions from 8 threads: several in-place resizes past the
        // ~1e3 threshold, with concurrent readers probing throughout.
        repeat(writers) { writer ->
            repeat(idsPerWriter) { round ->
                val id = "resize-$writer-$round"
                val metadata = assertNotNull(PasskeySessionEventHandler.getSessionMetadata(id))
                assertEquals("writer-$writer@resize", metadata.email)
                assertEquals(typeFor(writer), metadata.type)
            }
        }
    }
}
