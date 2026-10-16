package info.skyblond.fediverse.toot.trans.llm.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetryingChatClientTest {

    private val request = ChatCompletionRequest(model = "m", messages = listOf(ChatMessage.user("hi")))

    /**
     * Delegate scripted with one [Result] per attempt; the last entry repeats
     * once the script is exhausted, so a single failure means "always fails".
     * */
    private class ScriptedChatClient(private val outcomes: List<Result<String>>) : ChatClient {
        var attempts = 0
            private set

        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
            val outcome = outcomes[minOf(attempts, outcomes.lastIndex)]
            attempts++
            return outcome.fold(
                onSuccess = { text ->
                    ChatCompletionResponse(
                        id = "chatcmpl-test",
                        choices = listOf(
                            ChatChoice(
                                index = 0,
                                message = AssistantMessage(role = "assistant", content = ChatContent.Text(text)),
                                finishReason = "stop",
                            )
                        ),
                    )
                },
                onFailure = { throw it },
            )
        }
    }

    private val neverCallClient = object : ChatClient {
        override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse =
            error("this client must not be called")
    }

    @Test
    fun `retries a transient API error and returns the next success`() {
        runTest {
            val fake = ScriptedChatClient(
                listOf(Result.failure(ChatApiException(500, "boom")), Result.success("ok"))
            )
            val client = RetryingChatClient(fake, maxAttempts = 4, retryDelay = 1.seconds)

            val response = client.complete(request)

            assertEquals("ok", response.firstChoiceText())
            assertEquals(2, fake.attempts)
            assertEquals(1.seconds.inWholeMilliseconds, currentTime)
        }
    }

    @Test
    fun `retries every transient status code`() {
        runTest {
            for (code in listOf(408, 429, 500, 502, 503, 504, 529)) {
                val fake = ScriptedChatClient(
                    listOf(Result.failure(ChatApiException(code, "retry me")), Result.success("ok"))
                )
                val client = RetryingChatClient(fake, maxAttempts = 2, retryDelay = 1.seconds)

                assertEquals("ok", client.complete(request).firstChoiceText(), "status $code should be retried")
                assertEquals(2, fake.attempts, "status $code should be retried")
            }
        }
    }

    @Test
    fun `does not retry non-transient status codes`() {
        runTest {
            // 402 (out of quota) and 403 (blocked) are not transient here: see
            // the retry policy in RetryingChatClient's KDoc
            for (code in listOf(400, 401, 402, 403, 404, 413, 422)) {
                val fake = ScriptedChatClient(listOf(Result.failure(ChatApiException(code, "nope"))))
                val client = RetryingChatClient(fake, maxAttempts = 4, retryDelay = 1.seconds)

                val e = assertFailsWith<ChatApiException> { client.complete(request) }

                assertEquals(code, e.httpCode, "status $code should not be retried")
                assertEquals(1, fake.attempts, "status $code should not be retried")
            }
            // a rejection is not a reason to wait
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `does not retry malformed success bodies`() {
        runTest {
            val fake = ScriptedChatClient(listOf(Result.failure(SerializationException("bad body"))))
            val client = RetryingChatClient(fake, maxAttempts = 4, retryDelay = 1.seconds)

            val e = assertFailsWith<SerializationException> { client.complete(request) }

            assertEquals("bad body", e.message)
            assertEquals(1, fake.attempts)
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `retries transport failures`() {
        runTest {
            val fake = ScriptedChatClient(
                listOf(Result.failure(IOException("connection reset")), Result.success("ok"))
            )
            val client = RetryingChatClient(fake, maxAttempts = 2, retryDelay = 3.seconds)

            assertEquals("ok", client.complete(request).firstChoiceText())
            assertEquals(2, fake.attempts)
            assertEquals(3.seconds.inWholeMilliseconds, currentTime)
        }
    }

    @Test
    fun `gives up after the attempt budget and rethrows the last failure`() {
        runTest {
            val fake = ScriptedChatClient(listOf(Result.failure(ChatApiException(503, "overloaded"))))
            val client = RetryingChatClient(fake, maxAttempts = 3, retryDelay = 2.seconds)

            val e = assertFailsWith<ChatApiException> { client.complete(request) }

            assertEquals(503, e.httpCode)
            assertEquals(3, fake.attempts)
            // two fixed waits, the third failure propagates immediately
            assertEquals(4.seconds.inWholeMilliseconds, currentTime)
        }
    }

    @Test
    fun `maxAttempts of one disables retrying`() {
        runTest {
            val fake = ScriptedChatClient(listOf(Result.failure(ChatApiException(500, "boom"))))
            val client = RetryingChatClient(fake, maxAttempts = 1, retryDelay = 1.seconds)

            assertFailsWith<ChatApiException> { client.complete(request) }

            assertEquals(1, fake.attempts)
            assertEquals(0L, currentTime)
        }
    }

    @Test
    fun `cancellation during the retry delay propagates`() {
        runTest {
            val fake = ScriptedChatClient(listOf(Result.failure(IOException("boom"))))
            val client = RetryingChatClient(fake, maxAttempts = 5, retryDelay = 1.hours)

            val job = launch {
                assertFailsWith<CancellationException> { client.complete(request) }
            }
            // let the first attempt fail and the client suspend in its wait
            runCurrent()
            assertEquals(1, fake.attempts)

            job.cancelAndJoin()
            // no further attempt was made
            assertEquals(1, fake.attempts)
        }
    }

    @Test
    fun `rejects an attempt budget below one`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RetryingChatClient(neverCallClient, maxAttempts = 0)
        }

        assertEquals("maxAttempts must be at least 1 (the initial attempt), got 0", e.message)
    }

    @Test
    fun `rejects a non-positive retry delay`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RetryingChatClient(neverCallClient, retryDelay = Duration.ZERO)
        }

        assertEquals("retryDelay must be finite and at least 1ms, got 0s", e.message)
    }
}
