package info.skyblond.fediverse.toot.trans.llm.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * The message author roles this client supports.
 *
 * The rest of the wire role set (`developer`, `tool`, `function`) is deliberately
 * not modeled: this project sends a fixed instruction plus the toot conversation,
 * so `system` / `user` / `assistant` cover every message it will send. `system` is
 * accepted by every OpenAI-compatible server this project targets.
 *
 * Entries are uppercase per Kotlin style; [SerialName] pins the lowercase wire
 * values (e.g. `{"role": "user"}`).
 * */
@Serializable
enum class ChatRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

/**
 * One message of the conversation.
 *
 * History is caller-managed: build the list from the oldest turn to the newest,
 * echoing previous answers with [assistant] (see [ChatClient]'s KDoc).
 *
 * Text content must not be blank and content-part lists must not be empty; the
 * `init` block enforces both, so direct construction is as safe as the factories.
 * */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: ChatContent,
) {
    init {
        when (content) {
            is ChatContent.Text -> require(content.text.isNotBlank()) {
                "$role message text must not be blank"
            }

            is ChatContent.Parts -> require(content.parts.isNotEmpty()) {
                "$role message content parts must not be empty"
            }
        }
    }

    companion object {
        /** A system instruction (or the first turn of a translation prompt). */
        fun system(text: String): ChatMessage = ChatMessage(ChatRole.SYSTEM, ChatContent.Text(text))

        /** A text-only user turn, serialized as a plain JSON string. */
        fun user(text: String): ChatMessage = ChatMessage(ChatRole.USER, ChatContent.Text(text))

        /**
         * A user turn with attachments, serialized as a content-part array.
         * [parts] must not be empty; see [ContentPart.image] for base64 images.
         * */
        fun user(parts: List<ContentPart>): ChatMessage = ChatMessage(ChatRole.USER, ChatContent.Parts(parts))

        /** A previous model answer, appended to the history as-is. */
        fun assistant(text: String): ChatMessage = ChatMessage(ChatRole.ASSISTANT, ChatContent.Text(text))
    }
}

/**
 * Content of a chat message: plain text or an ordered list of [ContentPart]s.
 *
 * The wire shape is a JSON **string** for text-only content and a JSON **array** of
 * parts for attachment-bearing content (the Chat Completions "content parts" model,
 * one array element per text block or attachment). Decoding accepts either shape
 * wherever a content field appears, because providers also echo history back in both
 * forms; unmodeled part types are dropped instead of failing the response, see
 * [ChatContentSerializer].
 * */
@Serializable(with = ChatContentSerializer::class)
sealed interface ChatContent {
    /** Plain text content: a bare JSON string on the wire. */
    data class Text(val text: String) : ChatContent

    /** Attachment-bearing content: an array of [ContentPart]s on the wire. */
    data class Parts(val parts: List<ContentPart>) : ChatContent
}

/**
 * One element of an attachment-bearing message content.
 *
 * The hierarchy is sealed and extensible: further wire types (files, audio, ...)
 * can be added as new members later without breaking callers.
 * */
@Serializable
sealed interface ContentPart {
    /** Plain text block: `{"type": "text", "text": "..."}`. */
    @Serializable
    @SerialName("text")
    data class TextPart(val text: String) : ContentPart

    /**
     * Image block: `{"type": "image_url", "image_url": {"url": ...}}`.
     *
     * This project only builds base64 data URLs (see [image]); the model keeps the
     * raw wire field so the type stays faithful to the protocol.
     * */
    @Serializable
    @SerialName("image_url")
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl,
    ) : ContentPart

    companion object {
        /**
         * Build an [ImagePart] from raw image bytes, encoded as a
         * `data:{mediaType};base64,{...}` URL.
         *
         * Callers must download the media themselves: the provider never fetches
         * URLs on our behalf (private or followers-only toot media would not be
         * reachable for it anyway), which is why only this self-contained form is
         * offered. Base64 inflates the payload by about a third, and the client
         * buffers the whole request body in memory and resends it on every retry,
         * so callers must bound the number and size of images per request.
         * */
        fun image(bytes: ByteArray, mediaType: String, detail: ImageDetail? = null): ImagePart {
            require(bytes.isNotEmpty()) { "image bytes must not be empty" }
            require(mediaType.isNotBlank()) { "mediaType must not be blank" }
            // the media type is embedded verbatim in the data URL, so anything that
            // would terminate or split the URL must fail fast
            require(mediaType.none { it.isWhitespace() || it == ';' || it == ',' || it == ':' }) {
                "mediaType must be a bare type/subtype like image/png, got `$mediaType`"
            }
            val base64 = Base64.getEncoder().encodeToString(bytes)
            return ImagePart(ImageUrl("data:$mediaType;base64,$base64", detail))
        }
    }
}

/**
 * Value of an `image_url` content part: the URL (for this project always a base64
 * data URL, see [ContentPart.image]) and the optional vision-quality hint.
 * */
@Serializable
data class ImageUrl(
    val url: String,
    /** Vision detail hint: `auto`, `low` or `high`; omitted from the wire when null. */
    val detail: ImageDetail? = null,
)

@Serializable
enum class ImageDetail {
    @SerialName("auto") AUTO,
    @SerialName("low") LOW,
    @SerialName("high") HIGH,
}

/**
 * Wire (de)serializer for [ChatContent]: a bare JSON string for [ChatContent.Text],
 * a JSON array of parts for [ChatContent.Parts]. JSON-only by construction — the
 * shape is a JSON union with no cross-format meaning — so non-JSON decoders are
 * rejected.
 *
 * The descriptor is metadata only: no standard descriptor kind fits "string or
 * array", so this follows kotlinx.serialization's own JSON value serializers and
 * declares a primitive; the concrete shape is decided in [serialize] and
 * [deserialize], and no format branches on this descriptor.
 *
 * Decoding drops parts with unmodeled types instead of failing
 * [ChatContent.Parts], so a provider answering with e.g. a `refusal` part cannot
 * break the whole response.
 * */
object ChatContentSerializer : KSerializer<ChatContent> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "info.skyblond.fediverse.toot.trans.llm.client.ChatContent",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: ChatContent) {
        when (value) {
            is ChatContent.Text -> encoder.encodeString(value.text)
            is ChatContent.Parts -> encoder.encodeSerializableValue(
                ListSerializer(ContentPart.serializer()), value.parts,
            )
        }
    }

    override fun deserialize(decoder: Decoder): ChatContent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ChatContent is a JSON-only wire shape, got ${decoder::class}")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> ChatContent.Text(
                element.contentOrNull
                    ?: throw SerializationException("Chat message content must not be JSON null")
            )

            is JsonArray -> ChatContent.Parts(element.mapNotNull { decodePart(jsonDecoder.json, it) })
            else -> throw SerializationException("Chat message content must be a string or an array, got $element")
        }
    }

    /**
     * Decode one array element, or null for parts this project does not model
     * (e.g. `refusal`, `input_audio`), see the class KDoc.
     * */
    private fun decodePart(json: Json, element: JsonElement): ContentPart? {
        val part = element as? JsonObject ?: return null
        // casts instead of `jsonPrimitive`: a non-primitive shape is a malformed
        // body and must surface as SerializationException, see ChatClient
        return when ((part["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> ContentPart.TextPart(
                (part["text"] as? JsonPrimitive)?.contentOrNull
                    ?: throw SerializationException("text content part without a text field: $part")
            )

            "image_url" -> ContentPart.ImagePart(
                json.decodeFromJsonElement(
                    ImageUrl.serializer(),
                    part["image_url"]
                        ?: throw SerializationException("image_url content part without an image_url field: $part"),
                )
            )

            else -> null
        }
    }
}

/**
 * Value of the `reasoning_effort` request field: the standard reasoning-depth knob.
 * The union below is OpenAI's current value set, which the other providers accept
 * as a subset (`synthetic.new` only supports `low`/`medium`/`high`, Gemini's
 * compatibility layer maps `none`..`high` onto its thinking levels, DeepInfra also
 * accepts `xhigh`/`max`); not every model supports every value and unsupported
 * values fail with a 4xx. Values outside the union can be sent through
 * [ChatCompletionRequest.extra].
 *
 * Some thinking models cannot turn thinking off at all: `none` is then ignored, and
 * the caller must leave enough room under [ChatCompletionRequest.maxCompletionTokens]
 * for the thinking tokens plus the answer.
 * */
@Serializable
enum class ReasoningEffort {
    @SerialName("none") NONE,
    @SerialName("minimal") MINIMAL,
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("xhigh") XHIGH,
    @SerialName("max") MAX,
}

/**
 * Value of the `response_format` request field: how the provider constrains the
 * answer's shape.
 *
 * Structured outputs ([JsonSchema]) are a requirement of this project: the
 * translation stage sends the strict schema on every request, and a provider or
 * model that cannot enforce the schema cannot serve a deployment. The protocol's
 * other response formats (`text`, `json_object`) are deliberately not modeled;
 * the hierarchy stays sealed because its polymorphic serializer is what turns
 * the [SerialName] into the wire `type` discriminator, e.g.
 * `{"type": "json_schema", ...}`.
 * */
@Serializable
sealed interface ResponseFormat {
    /** `{"type": "json_schema", "json_schema": {...}}`: shape-guaranteed output. */
    @Serializable
    @SerialName("json_schema")
    data class JsonSchema(
        @SerialName("json_schema") val jsonSchema: JsonSchemaSpec,
    ) : ResponseFormat
}

/**
 * The `json_schema` payload of [ResponseFormat.JsonSchema]: [name] labels the
 * schema, [schema] is the JSON Schema itself, and [strict] asks the provider to
 * enforce it exactly.
 *
 * [schema] must stay inside the structured-outputs subset every provider
 * follows: each object needs `additionalProperties: false`, every property must
 * be listed in `required`, and an optional value is a nullable type
 * (`"type": ["string", "null"]`), not an omitted field. The caller owns that
 * contract; this model only validates its own fields.
 * https://developers.openai.com/api/docs/guides/structured-outputs
 * */
@Serializable
data class JsonSchemaSpec(
    val name: String,
    val description: String? = null,
    @SerialName("strict") val strict: Boolean? = null,
    val schema: JsonObject,
) {
    init {
        require(SCHEMA_NAME_PATTERN.matches(name)) {
            "name must match ${SCHEMA_NAME_PATTERN.pattern}, got `$name`"
        }
        require(schema.isNotEmpty()) { "schema must not be empty" }
    }

    companion object {
        /**
         * The schema-name charset the providers accept.
         * */
        private val SCHEMA_NAME_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")
    }
}

/**
 * Body of a Chat Completions request; wire docs live on [ChatClient].
 *
 * Only the fields this project needs are modeled. Deliberately absent (pass
 * provider-specific knobs through [extra]): sampling parameters (`temperature`,
 * `top_p`, `seed`, penalties) because current reasoning models reject or ignore
 * them; `max_tokens`, deprecated and incompatible with o-series models, replaced by
 * [maxCompletionTokens]; tools/function calling, audio and logprobs, none of
 * which a translation flow consumes. `response_format` is modeled as
 * [responseFormat] because the translation flow must send it on every request
 * (see [ResponseFormat]).
 * */
@Serializable
data class ChatCompletionRequest(
    /** Model ID, e.g. `gpt-5-mini` or a provider-specific name. */
    val model: String,

    /**
     * Conversation so far, oldest first; at least one message. Multi-turn history
     * is simply longer lists with [ChatMessage.assistant] answers echoed back.
     * */
    val messages: List<ChatMessage>,

    /**
     * Upper bound on tokens generated for this completion, **including thinking
     * tokens** on reasoning models (`max_completion_tokens` on the wire; the
     * deprecated `max_tokens` is deliberately not modeled). Some models are
     * always-thinking: too small a bound can surface as an empty answer with
     * `finish_reason` `length`, see [firstChoiceText].
     * */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,

    /** Reasoning depth, see [ReasoningEffort]; omitted from the wire when null. */
    @SerialName("reasoning_effort") val reasoningEffort: ReasoningEffort? = null,

    /**
     * Output constraint for the answer, see [ResponseFormat]; omitted from the
     * wire when null. Optional at this layer, but the translation stage always
     * sets the strict [ResponseFormat.JsonSchema] on every request.
     * */
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,

    /**
     * Provider-specific top-level body fields merged in after the standard ones
     * (e.g. `{"temperature": 0.2}`). Never serialized under an `extra` key; keys
     * that collide with fields this client owns are rejected by the `init` block
     * so the body cannot be silently patched, `stream` included. [JsonObject]
     * delegates map access to its backing map, so a caller could still add keys
     * after construction; the transport therefore snapshots the object and
     * re-validates the snapshot right before serialization.
     *
     * Thinking control examples for the providers this project targets (each
     * provider's own docs are authoritative; see also [reasoningEffort]):
     * - llama.cpp server: `{"chat_template_kwargs": {"enable_thinking": false}}`
     *   (it does not accept `reasoning_effort` in the request body).
     * - Novita: `{"enable_thinking": false, "separate_reasoning": true}`
     *   (switchable models only; always-on models ignore the switch).
     * - DeepInfra: `reasoning_effort`, or `{"reasoning": {"enabled": false}}`
     *   for finer control.
     * - ZenMux: `reasoning_effort`, or `{"reasoning": {"effort": "low"}}`.
     * - synthetic.new: `reasoning_effort` (`low` / `medium` / `high` only).
     * - Gemini (OpenAI compatibility layer): `reasoning_effort` (`none` only on
     *   2.5 models; newer models cannot disable thinking), or
     *   `{"extra_body": {"google": {"thinking_config": {"thinking_level": "low"}}}}`
     *   which cannot be combined with `reasoning_effort`.
     * */
    @Transient val extra: JsonObject? = null,
) {
    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(maxCompletionTokens == null || maxCompletionTokens > 0) {
            "maxCompletionTokens must be positive, got $maxCompletionTokens"
        }
        requireExtraKeysAllowed(extra)
    }

    companion object {
        /**
         * Top-level body fields this client owns: [ChatCompletionRequest.model],
         * [ChatCompletionRequest.messages], [ChatCompletionRequest.maxCompletionTokens],
         * [ChatCompletionRequest.reasoningEffort],
         * [ChatCompletionRequest.responseFormat], and the transport-pinned `stream`.
         * [extra] must not name any of them.
         * */
        val RESERVED_KEYS =
            setOf("model", "messages", "max_completion_tokens", "reasoning_effort", "response_format", "stream")

        /**
         * Rejects [extra] keys that collide with [RESERVED_KEYS]. Called by the
         * `init` block and again by the transport on its send-time snapshot, see
         * [ChatCompletionRequest.extra].
         * */
        internal fun requireExtraKeysAllowed(extra: JsonObject?) {
            require(extra == null || extra.keys.none { it in RESERVED_KEYS }) {
                "extra must not override client-owned fields $RESERVED_KEYS, got ${extra?.keys}"
            }
        }
    }
}

/**
 * A decoded Chat Completions response; unknown fields are ignored.
 * https://developers.openai.com/api/reference/resources/chat/subresources/completions/
 * */
@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class ChatChoice(
    val index: Int? = null,
    val message: AssistantMessage,
    /** `stop`, `length`, `content_filter`, ...; see [firstChoiceText]. */
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * The assistant message of a choice. Reasoning output (`reasoning_content`,
 * `reasoning`, ...) is deliberately not modeled: this project only consumes the
 * final answer in [content].
 * */
@Serializable
data class AssistantMessage(
    val role: String? = null,
    val content: ChatContent? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/**
 * The answer text of the first choice, joined with newlines when the provider
 * answered with a content-part array.
 *
 * @throws ChatCompletionException when the response carries no usable text: no
 *   choice, no content or text parts, or only blank text (e.g. a tool-call-only or
 *   filtered response, or a thinking model that burned its entire budget into its
 *   unmodeled reasoning field and answered with an empty string). The exception
 *   carries the choice's [ChatCompletionException.finishReason], so callers can
 *   tell a provider-side refusal from a malformed response (see
 *   [isContentFiltered]); a non-blank truncated answer (`finish_reason` `length`)
 *   still returns its partial text.
 * */
fun ChatCompletionResponse.firstChoiceText(): String {
    val choice = choices.firstOrNull()
        ?: throw ChatCompletionException("Response has no choices (id=$id)")
    val content = choice.message.content
        ?: throw ChatCompletionException(
            "First choice has no content (finishReason=${choice.finishReason})",
            choice.finishReason,
        )
    val text = when (content) {
        is ChatContent.Text -> content.text
        is ChatContent.Parts -> {
            val texts = content.parts.filterIsInstance<ContentPart.TextPart>()
            if (texts.isEmpty()) throw ChatCompletionException(
                "First choice content has no text parts (finishReason=${choice.finishReason})",
                choice.finishReason,
            )
            texts.joinToString("\n") { it.text }
        }
    }
    if (text.isBlank()) throw ChatCompletionException(
        "First choice text is blank (finishReason=${choice.finishReason})",
        choice.finishReason,
    )
    return text
}

/**
 * A 2xx response that cannot serve as an answer: no choices, or a first choice
 * without usable (non-blank) text content, see [firstChoiceText].
 *
 * [finishReason] is the first choice's `finish_reason` when a choice existed to
 * read ([ChatChoice.finishReason]); null when the response carried none. See
 * [isContentFiltered].
 * */
class ChatCompletionException(
    message: String,
    val finishReason: String? = null,
) : RuntimeException(message)

/**
 * Whether the provider's content filter refused the answer rather than the model
 * failing: the first choice finished with `content_filter` and carried no usable
 * text (see [firstChoiceText]). The refusal is deterministic for the same
 * payload, so consumers that retry unusable answers downgrade instead of
 * re-sending (see `Translator`).
 * */
fun ChatCompletionException.isContentFiltered(): Boolean = finishReason == "content_filter"
