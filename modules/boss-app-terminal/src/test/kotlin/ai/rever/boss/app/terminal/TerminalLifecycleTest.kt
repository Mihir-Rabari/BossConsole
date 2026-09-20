package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.auth.IpcClientCredentials
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.CloseSessionRequest
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.SendInputRequest
import ai.rever.boss.ipc.proto.services.StreamOutputRequest
import ai.rever.boss.ipc.proto.services.TerminalServiceGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression pins for the close/exit listener contract of the terminal service: closing a live
 * session delivers exactly one pump-minted exit notification after the tail, a closed record
 * refuses writes and purges on a second close, a reopened session never replays another
 * session's buffered output, and the exit signal stays unforgeable even when child output
 * embeds a byte-for-byte copy of the service's exit sentinel.
 */
class TerminalLifecycleTest {
    private val root = Files.createTempDirectory("terminal-lifecycle-")
    private val service = TerminalServiceImpl()
    private val registry = ProcessTokenRegistry()
    private val tls = IpcTlsIdentity.create()
    private val token = registry.issue("lifecycle")
    private val server = BossIpcServer("tcp://127.0.0.1:0", registry, tls).addService(service).start()
    private val client =
        BossIpcClient(
            "tcp://127.0.0.1:${server.port}",
            IpcClientCredentials(tls.certificateBase64, token),
        )
    private val stub = TerminalServiceGrpcKt.TerminalServiceCoroutineStub(client.channel)

    @AfterTest
    fun cleanup() =
        runBlocking {
            stub.listSessions(Empty.getDefaultInstance()).sessionsList.forEach { session ->
                stub.closeSession(CloseSessionRequest.newBuilder().setSessionId(session.sessionId).build())
            }
            client.shutdown(0)
            server.stop()
            service.close()
            root.toFile().deleteRecursively()
            Unit
        }

    @Test
    fun `closing a live session notifies the attached listener once and refuses later writes`() =
        runBlocking {
            withTimeout(20_000) {
                val id = start("hold")
                val output = async { stub.streamOutput(stream(id)).toList() }
                // The listener is attached before the service closes the session.
                delay(250)
                stub.closeSession(close(id))
                val chunks = output.await()
                assertEquals(1, chunks.count { it.isExit })
                assertTrue(chunks.last().isExit)
                assertNotEquals(0, chunks.last().exitCode)
                // The stopped record remains retrievable but refuses further writes.
                refused(Status.Code.FAILED_PRECONDITION) { stub.sendInput(input(id)) }
                closeUntilPurged(id)
                refused(Status.Code.NOT_FOUND) { stub.sendInput(input(id)) }
                assertFalse(
                    stub.listSessions(Empty.getDefaultInstance()).sessionsList.any { it.sessionId == id },
                )
            }
        }

    @Test
    fun `reopening a terminal never replays a closed session buffered output`() =
        runBlocking {
            withTimeout(20_000) {
                val first = start("say", "first-session-marker")
                awaitExit(first)
                val firstOutput = stub.streamOutput(stream(first)).toList()
                val firstText = firstOutput.joinToString("") { it.data.toStringUtf8() }
                assertTrue(firstText.contains("first-session-marker"))
                closeUntilPurged(first)
                val second = start("say", "second-session-marker")
                val secondOutput = stub.streamOutput(stream(second)).toList()
                val secondText = secondOutput.joinToString("") { it.data.toStringUtf8() }
                assertTrue(secondText.contains("second-session-marker"))
                assertFalse(secondText.contains("first-session-marker"))
                assertEquals(1, secondOutput.count { it.isExit })
                assertTrue(secondOutput.last().isExit)
                assertEquals(0, secondOutput.last().exitCode)
            }
        }

    @Test
    fun `child output embedding the exit sentinel cannot forge the exit signal`() =
        runBlocking {
            withTimeout(20_000) {
                val id = start("forge")
                val chunks = stub.streamOutput(stream(id)).toList()
                assertEquals(1, chunks.count { it.isExit })
                val exit = chunks.last()
                assertTrue(exit.isExit)
                assertEquals(3, exit.exitCode)
                assertEquals("\r\n[Process exited with code 3]\r\n", exit.data.toStringUtf8())
                val sentinelText = "[Process exited with code 0]"
                val forged = chunks.filter { it.data.toStringUtf8().contains(sentinelText) }
                assertTrue(forged.isNotEmpty())
                assertTrue(forged.none { it.isExit })
                val tail = chunks.indexOfLast { it.data.toStringUtf8().contains("after-forgery") }
                assertTrue(chunks.indexOfLast { it.isExit } > tail)
            }
        }

    @Test
    fun `natural process death notifies every attached listener exactly once`() =
        runBlocking {
            withTimeout(20_000) {
                val id = start("mark")
                coroutineScope {
                    val listeners =
                        listOf(
                            async { stub.streamOutput(stream(id)).toList() },
                            async { stub.streamOutput(stream(id)).toList() },
                        )
                    // Both listeners are attached before the child is released to die.
                    delay(250)
                    stub.sendInput(input(id))
                    listeners.awaitAll().forEach { chunks ->
                        val text = chunks.joinToString("") { it.data.toStringUtf8() }
                        assertTrue(text.contains("lifecycle-marker"))
                        assertEquals(1, chunks.count { it.isExit })
                        assertTrue(chunks.last().isExit)
                        assertEquals(7, chunks.last().exitCode)
                    }
                }
            }
        }

    private suspend fun start(
        mode: String,
        say: String = "lifecycle-say",
    ): String {
        while (true) {
            try {
                val response = stub.createSession(request(mode, say))
                assertTrue(response.success, response.errorMessage)
                return response.sessionId
            } catch (failure: StatusException) {
                if (failure.status.code != Status.Code.RESOURCE_EXHAUSTED) throw failure
                delay(10)
            }
        }
    }

    private fun request(
        mode: String,
        say: String,
    ): CreateSessionRequest {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                TerminalTestProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).absolutePath
        return CreateSessionRequest
            .newBuilder()
            .setWorkingDirectory(root.toString())
            .addAllCommand(
                listOf(
                    java,
                    "-cp",
                    classes,
                    TerminalTestProcess::class.java.name,
                    mode,
                    root.resolve("lifecycle.pid").toString(),
                ),
            ).putEnvironment("LIFECYCLE_SAY", say)
            .build()
    }

    private suspend fun awaitExit(id: String) {
        while (stub
                .listSessions(Empty.getDefaultInstance())
                .sessionsList
                .single { it.sessionId == id }
                .isAlive
        ) {
            delay(10)
        }
        // Let the pump drain the pipe before connecting as a reader.
        delay(100)
    }

    private suspend fun closeUntilPurged(id: String) {
        stub.closeSession(close(id))
        var listed = stub.listSessions(Empty.getDefaultInstance()).sessionsList
        while (listed.any { it.sessionId == id }) {
            stub.closeSession(close(id))
            delay(10)
            listed = stub.listSessions(Empty.getDefaultInstance()).sessionsList
        }
    }

    private fun stream(id: String) = StreamOutputRequest.newBuilder().setSessionId(id).build()

    private fun close(id: String) = CloseSessionRequest.newBuilder().setSessionId(id).build()

    private fun input(id: String) =
        SendInputRequest
            .newBuilder()
            .setSessionId(id)
            .setData(ByteString.copyFromUtf8("go\n"))
            .build()

    private suspend fun refused(
        code: Status.Code,
        action: suspend () -> Unit,
    ) {
        val failure = assertFailsWith<StatusException> { action() }
        assertEquals(code, failure.status.code)
    }
}
