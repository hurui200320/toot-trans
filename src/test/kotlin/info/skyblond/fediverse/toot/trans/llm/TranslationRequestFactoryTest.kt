package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.ChatContent
import info.skyblond.fediverse.toot.trans.llm.client.ChatMessage
import info.skyblond.fediverse.toot.trans.llm.client.ChatRole
import info.skyblond.fediverse.toot.trans.llm.client.ContentPart
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import info.skyblond.fediverse.toot.trans.llm.client.ResponseFormat
import info.skyblond.fediverse.toot.trans.masto.client.statuses.MediaAttachment
import info.skyblond.fediverse.toot.trans.masto.client.statuses.PreviewCard
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.statuses.original
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prompt contract of [TranslationRequestFactory]: message order,
 * JSON shapes, the placeholder, the three variants and the request
 * parameters.
 * */
class TranslationRequestFactoryTest {
    private val factory = TranslationRequestFactory(
        targetLanguage = "English",
        model = "test-model",
        reasoningEffort = ReasoningEffort.LOW,
        maxCompletionTokens = 1234,
        extraParameter = JsonObject(mapOf("temperature" to JsonPrimitive(0.2))),
    )

    private fun entry(
        id: String,
        text: String,
        spoilerText: String = "",
        translation: String? = null,
        translationSpoilerText: String? = null,
        configure: (Status) -> Status = { it },
    ) = TranslationContextEntry(
        status = configure(original(id).copy(spoilerText = spoilerText.takeIf { it.isNotBlank() })),
        source = StatusSource(id, text, spoilerText),
        translation = translation,
        translationSpoilerText = translationSpoilerText,
    )

    private fun userJson(message: ChatMessage): JsonObject =
        Json.parseToJsonElement(messageText(message)).jsonObject

    private fun messageText(message: ChatMessage): String =
        (message.content as ChatContent.Text).text

    @Test
    fun `single toot request carries the prompt contract and request parameters`() {
        val context = TranslationContext(listOf(entry("P", "你好", spoilerText = "警告")))
        val request = factory.createTextRequest(context)

        assertEquals(2, request.messages.size)
        assertEquals(ChatRole.SYSTEM, request.messages[0].role)
        val system = messageText(request.messages[0])
        assertTrue(system.contains("English"), system)
        assertTrue(system.contains("\"content\""), system)
        assertTrue(system.contains("\"spoiler_text\""), system)
        assertTrue(system.contains(MISSING_TRANSLATION_PLACEHOLDER), system)

        val user = userJson(request.messages[1])
        assertEquals("你好", user["content"]?.jsonPrimitive?.content)
        assertEquals("警告", user["spoiler_text"]?.jsonPrimitive?.content)

        assertEquals("test-model", request.model)
        assertEquals(1234, request.maxCompletionTokens)
        assertEquals(ReasoningEffort.LOW, request.reasoningEffort)
        assertEquals("0.2", request.extra?.get("temperature")?.jsonPrimitive?.content)
    }

    @Test
    fun `history pairs each ancestor original with its translation`() {
        val context = TranslationContext(
            listOf(
                entry("root", "root text", translation = "root translation", translationSpoilerText = "root CW"),
                entry("middle", "middle text"),
                entry("P", "target text"),
            )
        )
        val request = factory.createTextRequest(context)

        // system + (user, assistant) per ancestor + the target user
        assertEquals(6, request.messages.size)
        assertEquals(ChatRole.USER, request.messages[1].role)
        assertEquals("root text", userJson(request.messages[1])["content"]?.jsonPrimitive?.content)
        assertEquals(ChatRole.ASSISTANT, request.messages[2].role)
        val rootAssistant = Json.parseToJsonElement(messageText(request.messages[2])).jsonObject
        assertEquals("root translation", rootAssistant["content"]?.jsonPrimitive?.content)
        assertEquals("root CW", rootAssistant["spoiler_text"]?.jsonPrimitive?.content)

        assertEquals(ChatRole.USER, request.messages[3].role)
        assertEquals(ChatRole.ASSISTANT, request.messages[4].role)
        assertEquals(MISSING_TRANSLATION_PLACEHOLDER, messageText(request.messages[4]))

        assertEquals(ChatRole.USER, request.messages[5].role)
        assertEquals("target text", userJson(request.messages[5])["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `translated ancestor without a warning echoes a null spoiler`() {
        val context = TranslationContext(
            listOf(entry("root", "root text", translation = "root translation"), entry("P", "target text"))
        )
        val request = factory.createTextRequest(context)
        val assistant = Json.parseToJsonElement(messageText(request.messages[2])).jsonObject
        assertEquals("root translation", assistant["content"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, assistant["spoiler_text"])
    }

    @Test
    fun `a body-less ancestor translation echoes a null content and its warning`() {
        val context = TranslationContext(
            listOf(
                entry(
                    "root", text = "", spoilerText = "警告",
                    translation = "", translationSpoilerText = "Warning",
                ),
                entry("P", "target text"),
            )
        )
        val request = factory.createTextRequest(context)
        val assistant = Json.parseToJsonElement(messageText(request.messages[2])).jsonObject
        assertEquals(JsonNull, assistant["content"])
        assertEquals("Warning", assistant["spoiler_text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a blank ancestor translation spoiler is rendered as null`() {
        val context = TranslationContext(
            listOf(
                entry(
                    "root", "root text",
                    translation = "root translation", translationSpoilerText = "",
                ),
                entry("P", "target text"),
            )
        )
        val request = factory.createTextRequest(context)
        val assistant = Json.parseToJsonElement(messageText(request.messages[2])).jsonObject
        assertEquals("root translation", assistant["content"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, assistant["spoiler_text"])
    }

    @Test
    fun `full request attaches images as parts after the toot JSON`() {
        val context = TranslationContext(
            listOf(entry("P", "你好")),
            images = listOf(ContentPart.image(byteArrayOf(1, 2, 3), "image/png")),
        )
        val last = factory.createFullRequest(context).messages.last()
        val parts = (last.content as ChatContent.Parts).parts

        assertEquals(2, parts.size)
        val text = assertIs<ContentPart.TextPart>(parts[0])
        assertEquals("你好", Json.parseToJsonElement(text.text).jsonObject["content"]?.jsonPrimitive?.content)
        val image = assertIs<ContentPart.ImagePart>(parts[1])
        assertTrue(image.imageUrl.url.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `text and minimal variants never carry image parts`() {
        val context = TranslationContext(
            listOf(entry("P", "你好")),
            images = listOf(ContentPart.image(byteArrayOf(1), "image/png")),
        )
        assertIs<ChatContent.Text>(factory.createTextRequest(context).messages.last().content)
        assertIs<ChatContent.Text>(factory.createMinimalRequest(context).messages.last().content)
    }

    @Test
    fun `minimal request drops the history and the context blocks`() {
        val context = TranslationContext(
            listOf(
                entry("root", "root text", translation = "root translation"),
                entry(
                    "P", "target text",
                    configure = {
                        it.copy(
                            card = PreviewCard("https://example.com/a", "link", title = "标题", description = "描述"),
                            mediaAttachments = listOf(MediaAttachment("m1", "image", description = "alt text")),
                        )
                    },
                ),
            )
        )

        val text = factory.createTextRequest(context)
        val contextField = userJson(text.messages.last())["context"]?.jsonPrimitive?.content
        assertTrue(contextField!!.contains("[Link preview] 标题 - 描述"), contextField)
        assertTrue(contextField.contains("[Media description] alt text"), contextField)

        val minimal = factory.createMinimalRequest(context)
        assertEquals(2, minimal.messages.size)
        assertEquals(ChatRole.SYSTEM, minimal.messages[0].role)
        assertNull(userJson(minimal.messages[1])["context"])
    }

    @Test
    fun `a content-warning-only toot carries a null content and its warning`() {
        val context = TranslationContext(listOf(entry("P", text = "", spoilerText = "警告")))
        val user = userJson(factory.createTextRequest(context).messages.last())
        assertEquals(JsonNull, user["content"])
        assertEquals("警告", user["spoiler_text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a toot with neither body nor warning is marked as empty content`() {
        val context = TranslationContext(
            listOf(entry("root", text = ""), entry("P", "target text"))
        )
        val request = factory.createTextRequest(context)

        val root = userJson(request.messages[1])
        assertEquals(EMPTY_CONTENT_PLACEHOLDER, root["content"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, root["spoiler_text"])
        // the system instruction explains the marker, like the missing-translation one
        assertTrue(messageText(request.messages[0]).contains(EMPTY_CONTENT_PLACEHOLDER))
    }

    @Test
    fun `every request variant carries the strict translation schema`() {
        val context = TranslationContext(listOf(entry("P", "你好")))
        val requests = listOf(
            factory.createFullRequest(context),
            factory.createTextRequest(context),
            factory.createMinimalRequest(context),
        )

        for (request in requests) {
            val format = assertIs<ResponseFormat.JsonSchema>(request.responseFormat)
            assertEquals("translated_toot", format.jsonSchema.name)
            assertEquals(true, format.jsonSchema.strict)

            // the structured-outputs subset: both fields required, no additional
            // properties, and both nullable fields as nullable types
            val schema = format.jsonSchema.schema
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            assertEquals(false, schema["additionalProperties"]?.jsonPrimitive?.boolean)
            assertEquals(
                listOf("content", "spoiler_text"),
                schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val properties = schema["properties"]!!.jsonObject
            assertEquals(
                listOf("string", "null"),
                properties["content"]!!.jsonObject["type"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("string", "null"),
                properties["spoiler_text"]!!.jsonObject["type"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        }
    }
}
