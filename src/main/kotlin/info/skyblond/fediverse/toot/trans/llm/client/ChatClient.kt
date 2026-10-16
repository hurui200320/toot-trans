package info.skyblond.fediverse.toot.trans.llm.client

/**
 * Stateless client for the OpenAI-compatible **Chat Completions** API
 * (`POST /chat/completions`), used to translate toots into the target language.
 *
 * **Why Chat Completions and not the Responses API** (the main reference for the
 * modeled parameters is https://developers.openai.com/api/reference/resources/chat/):
 * - *Stateless by nature*: a completion is fully described by one request — the
 *   conversation is the caller-supplied [ChatCompletionRequest.messages]. There is no
 *   server-side response object to create, chain via `previous_response_id`, expire
 *   or delete, which matches this project's no-state design.
 * - *Broadest compatibility*: every OpenAI-compatible provider this project targets
 *   serves it — llama.cpp's server, DeepInfra, synthetic.new, Novita, ZenMux's
 *   protocol conversion, Gemini's OpenAI compatibility layer — while the Responses
 *   API is OpenAI-centric (DeepInfra, for example, does not serve it).
 * - *Simplest implementation*: one request shape, one response shape, no input-item
 *   conversion.
 *
 * **No streaming**: this project never needs partial answers, so `stream` is pinned
 * to `false` on the wire by the transport ([OkHttpChatClient]) and is deliberately
 * not a request knob; the response is always parsed as one complete object.
 *
 * Implementations are stateless and safe to share. Multi-turn history is
 * caller-managed: keep the previous turns in the request's message list (see
 * [ChatMessage]). Transient-failure retry is layered on top by
 * [RetryingChatClient].
 * */
interface ChatClient {
    /**
     * Send [request] and return the model's completion.
     *
     * Fails fast: non-2xx responses fail with [ChatApiException], malformed 2xx
     * bodies with [kotlinx.serialization.SerializationException]. Use
     * [firstChoiceText] to read the answer as plain text.
     * */
    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse
}
