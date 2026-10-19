package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.AssistantMessage
import info.skyblond.fediverse.toot.trans.llm.client.ChatChoice
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionRequest
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionResponse
import info.skyblond.fediverse.toot.trans.llm.client.ChatContent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [ChatClient] fake for the translation-stage tests: records every
 * request and answers through the swappable [complete] handler, so a
 * test can script a sequence of successes and failures. The default
 * handler fails the test instead of silently answering (same pattern
 * as the status fixtures' error stubs).
 * */
internal class FakeChatClient : ChatClient {
    val requests = mutableListOf<ChatCompletionRequest>()

    var handler: suspend (ChatCompletionRequest) -> ChatCompletionResponse =
        { error("No chat answer configured") }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        requests += request
        return handler(request)
    }

    /**
     * Answer every request with [content] as a plain-text completion.
     * */
    fun answerWith(content: String) {
        handler = { completionResponse(content) }
    }
}

/**
 * A minimal completion response carrying [content] as a plain string.
 * */
internal fun completionResponse(
    content: String,
    finishReason: String = "stop",
): ChatCompletionResponse = ChatCompletionResponse(
    id = "chatcmpl-test",
    choices = listOf(
        ChatChoice(
            index = 0,
            message = AssistantMessage(role = "assistant", content = ChatContent.Text(content)),
            finishReason = finishReason,
        )
    ),
)

/**
 * A JSON answer following `TranslationRequestFactory`'s output
 * contract, for scripting [FakeChatClient]: one `content` and one
 * `spoiler_text` field, null rendered as a literal null.
 * */
internal fun translationAnswer(content: String?, spoilerText: String? = null): String =
    buildJsonObject {
        put("content", content?.let(::JsonPrimitive) ?: JsonNull)
        put("spoiler_text", spoilerText?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()
