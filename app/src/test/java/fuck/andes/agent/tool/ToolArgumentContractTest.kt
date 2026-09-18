package fuck.andes.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgumentContractTest {
    @Test
    fun `personal search bounds query and limit`() {
        assertEquals(
            "query",
            ToolArgumentContract.validate(
                "search_messages",
                JSONObject().put("query", "x".repeat(201)),
            )?.field,
        )
        assertEquals(
            "limit",
            ToolArgumentContract.validate(
                "search_media",
                JSONObject().put("limit", 31),
            )?.field,
        )
        assertEquals(
            "max_age_hours",
            ToolArgumentContract.validate(
                "search_notification_history",
                JSONObject().put("max_age_hours", 169),
            )?.field,
        )
        assertEquals(
            "days",
            ToolArgumentContract.validate(
                "get_health_summary",
                JSONObject().put("days", 31),
            )?.field,
        )
        assertEquals(
            "limit",
            ToolArgumentContract.validate(
                "search_personal_orders",
                JSONObject().put("limit", 31),
            )?.field,
        )
        assertEquals(
            "path",
            ToolArgumentContract.validate("read_image", JSONObject())?.field,
        )
        assertEquals(
            "limit",
            ToolArgumentContract.validate(
                "search_wechat_chat_images",
                JSONObject().put("limit", 31),
            )?.field,
        )
        assertEquals(
            "limit",
            ToolArgumentContract.validate(
                "search_coloros_memories",
                JSONObject().put("limit", 31),
            )?.field,
        )
    }

    @Test
    fun `missing coordinates cannot become a zero coordinate gesture`() {
        assertEquals("x", ToolArgumentContract.validate("tap", JSONObject())?.field)
        assertEquals(
            "y2",
            ToolArgumentContract.validate(
                "swipe",
                JSONObject("""{"x1":1,"y1":2,"x2":3}"""),
            )?.field,
        )
    }

    @Test
    fun `missing text cannot silently clear text or clipboard`() {
        assertEquals(
            "text",
            ToolArgumentContract.validate("replace_text", JSONObject())?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate("input_text", JSONObject())?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate("set_clipboard", JSONObject())?.field,
        )
    }

    @Test
    fun `wrong json types are rejected before a side effect`() {
        assertEquals(
            "x",
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":"100","y":200}"""),
            )?.field,
        )
        assertEquals(
            "text",
            ToolArgumentContract.validate(
                "paste_text",
                JSONObject("""{"text":123}"""),
            )?.field,
        )
        assertEquals(
            "x",
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":-1,"y":200}"""),
            )?.field,
        )
    }

    @Test
    fun `indexed text edit requires matching observation reference`() {
        assertEquals(
            "observation_id",
            ToolArgumentContract.validate(
                "replace_text",
                JSONObject("""{"text":"new","index":4}"""),
            )?.field,
        )
        assertEquals(
            "index",
            ToolArgumentContract.validate(
                "input_text",
                JSONObject("""{"text":"new","index":4,"observation_id":"o1"}"""),
            )?.field,
        )
    }

    @Test
    fun `valid destructive argument including explicit empty text is accepted`() {
        assertNull(
            ToolArgumentContract.validate(
                "replace_text",
                JSONObject("""{"text":"","index":4,"observation_id":"o1"}"""),
            ),
        )
        assertNull(
            ToolArgumentContract.validate(
                "tap",
                JSONObject("""{"x":100,"y":200,"coordinate_space":"screen"}"""),
            ),
        )
    }

    @Test
    fun `empty paste is rejected without touching clipboard`() {
        assertEquals(
            "text",
            ToolArgumentContract.validate(
                "paste_text",
                JSONObject("""{"text":""}"""),
            )?.field,
        )
    }

    @Test
    fun `device side effects reject missing and oversized arguments`() {
        assertEquals(
            "hour",
            ToolArgumentContract.validate("set_alarm", JSONObject())?.field,
        )
        assertEquals(
            "hour",
            ToolArgumentContract.validate(
                "set_alarm",
                JSONObject("""{"hour":24,"minute":0}"""),
            )?.field,
        )
    }

    @Test
    fun `memory writes require revision and mode specific arguments`() {
        val revision = "a".repeat(64)
        assertEquals(
            "revision",
            ToolArgumentContract.validate(
                "memory_write",
                JSONObject("""{"mode":"clear","revision":"bad"}"""),
            )?.field,
        )
        assertEquals(
            "content",
            ToolArgumentContract.validate(
                "memory_write",
                JSONObject("""{"mode":"append","revision":"$revision","content":""}"""),
            )?.field,
        )
        assertNull(
            ToolArgumentContract.validate(
                "memory_write",
                JSONObject("""{"mode":"replace_range","revision":"$revision","start_line":2,"end_line":3,"content":""}"""),
            ),
        )
        assertEquals(
            "max_chars",
            ToolArgumentContract.validate(
                "memory_get",
                JSONObject("""{"max_chars":32001}"""),
            )?.field,
        )
    }
}
