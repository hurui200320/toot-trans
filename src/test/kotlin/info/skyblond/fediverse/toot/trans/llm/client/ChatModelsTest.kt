package info.skyblond.fediverse.toot.trans.llm.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

/**
 * Wire-shape tests for the models, independent of the transport: the exact JSON
 * the provider sees and the leniency of the decode paths.
 * */
class ChatModelsTest {
    // the same wire configuration the transport uses: see OkHttpChatClient
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `text-only messages serialize the content as a plain string`() {
        val encoded = json.encodeToString(ChatMessage.serializer(), ChatMessage.user("hi"))

        assertEquals("""{"role":"user","content":"hi"}""", encoded)
    }

    @Test
    fun `attachment messages serialize the content as a parts array`() {
        val message = ChatMessage.user(
            listOf(ContentPart.TextPart("look"), ContentPart.image(byteArrayOf(1), "image/png"))
        )

        val element = json.encodeToJsonElement(ChatMessage.serializer(), message).jsonObject
        val parts = element.getValue("content").jsonArray

        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("look", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        val imagePart = parts[1].jsonObject
        assertEquals("image_url", imagePart.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1)),
            imagePart.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun `decoding accepts both content shapes`() {
        val text = json.decodeFromString(ChatMessage.serializer(), """{"role":"user","content":"hi"}""")
        assertEquals("hi", assertIs<ChatContent.Text>(text.content).text)

        val parts = json.decodeFromString(
            ChatMessage.serializer(),
            """{"role":"user","content":[{"type":"text","text":"a"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/png;base64,AA==","detail":"high"}}]}""",
        )
        val decoded = assertIs<ChatContent.Parts>(parts.content).parts
        assertEquals(2, decoded.size)
        assertEquals("a", assertIs<ContentPart.TextPart>(decoded[0]).text)
        assertEquals(ImageDetail.HIGH, assertIs<ContentPart.ImagePart>(decoded[1]).imageUrl.detail)
    }

    @Test
    fun `unmodeled content parts are dropped while decoding`() {
        val message = json.decodeFromString(
            AssistantMessage.serializer(),
            """{"role":"assistant","content":[{"type":"refusal","refusal":"no"},{"type":"text","text":"answer"}]}""",
        )

        val parts = assertIs<ChatContent.Parts>(message.content).parts

        assertEquals(1, parts.size)
        assertEquals("answer", assertIs<ContentPart.TextPart>(parts[0]).text)
    }

    @Test
    fun `null response content decodes as null`() {
        val message = json.decodeFromString(
            AssistantMessage.serializer(),
            """{"role":"assistant","content":null,"tool_calls":[]}""",
        )

        assertNull(message.content)
    }

    @Test
    fun `content must be a string or an array`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString(ChatMessage.serializer(), """{"role":"user","content":{"nested":true}}""")
        }

        assertTrue(e.message.orEmpty().contains("must be a string or an array"))
    }

    @Test
    fun `a text part with a non-primitive text field fails as a serialization error`() {
        val e = assertFailsWith<SerializationException> {
            json.decodeFromString(
                AssistantMessage.serializer(),
                """{"role":"assistant","content":[{"type":"text","text":["a"]}]}""",
            )
        }

        assertTrue(e.message.orEmpty().contains("text content part without a text field"))
    }

    @Test
    fun `an image_url part with a missing or malformed image_url field fails as a serialization error`() {
        val missing = assertFailsWith<SerializationException> {
            json.decodeFromString(
                AssistantMessage.serializer(),
                """{"role":"assistant","content":[{"type":"image_url"}]}""",
            )
        }
        assertTrue(missing.message.orEmpty().contains("image_url content part without an image_url field"))

        // a mis-shaped field is a kotlinx-level error, so only the exception type
        // is part of this project's contract
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                AssistantMessage.serializer(),
                """{"role":"assistant","content":[{"type":"image_url","image_url":"https://example.com/a.png"}]}""",
            )
        }
    }

    @Test
    fun `image factory builds a data URL from raw bytes`() {
        val image = ContentPart.image(byteArrayOf(1, 2, 3), "image/jpeg", ImageDetail.HIGH)

        assertEquals(
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            image.imageUrl.url,
        )
        assertEquals(ImageDetail.HIGH, image.imageUrl.detail)
    }

    @Test
    fun `image factory rejects invalid inputs`() {
        assertFailsWith<IllegalArgumentException> { ContentPart.image(ByteArray(0), "image/png") }
        assertFailsWith<IllegalArgumentException> { ContentPart.image(byteArrayOf(1), " ") }
        assertFailsWith<IllegalArgumentException> { ContentPart.image(byteArrayOf(1), "image/png;base64") }
    }

    @Test
    fun `image detail is omitted from the wire when unset`() {
        val encoded = json.encodeToString(ImageUrl.serializer(), ImageUrl("data:image/png;base64,AA=="))

        assertEquals("""{"url":"data:image/png;base64,AA=="}""", encoded)
    }

    @Test
    fun `message factories reject blank text and empty parts`() {
        assertFailsWith<IllegalArgumentException> { ChatMessage.system(" ") }
        assertFailsWith<IllegalArgumentException> { ChatMessage.user("") }
        assertFailsWith<IllegalArgumentException> { ChatMessage.user(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ChatMessage.assistant("\t") }
        // the init block guards direct construction the same way
        assertFailsWith<IllegalArgumentException> { ChatMessage(ChatRole.USER, ChatContent.Text(" ")) }
        assertFailsWith<IllegalArgumentException> { ChatMessage(ChatRole.USER, ChatContent.Parts(emptyList())) }
    }

    @Test
    fun `request validation fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(model = " ", messages = listOf(ChatMessage.user("hi")))
        }
        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(model = "m", messages = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(model = "m", messages = listOf(ChatMessage.user("hi")), maxCompletionTokens = 0)
        }
        // every client-owned key is off limits for extra
        for (reserved in ChatCompletionRequest.RESERVED_KEYS) {
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = "m",
                    messages = listOf(ChatMessage.user("hi")),
                    extra = buildJsonObject { put(reserved, "overridden") },
                )
            }
        }
    }

    @Test
    fun `extra stays out of the serialized request and stream is transport-owned`() {
        val request = ChatCompletionRequest(
            model = "m",
            messages = listOf(ChatMessage.user("hi")),
            extra = buildJsonObject { put("temperature", 0.2) },
        )

        val element = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request).jsonObject

        assertFalse(element.containsKey("extra"))
        assertFalse(element.containsKey("temperature"))
        assertFalse(element.containsKey("stream"))
    }

    @Test
    fun `multi-turn history keeps roles and order`() {
        val messages = listOf(
            ChatMessage.system("translate"),
            ChatMessage.user("你好"),
            ChatMessage.assistant("Hello"),
            ChatMessage.user("第二段"),
        )

        val element = json.encodeToJsonElement(ListSerializer(ChatMessage.serializer()), messages)

        assertEquals(
            listOf("system", "user", "assistant", "user"),
            element.jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
    }
}
