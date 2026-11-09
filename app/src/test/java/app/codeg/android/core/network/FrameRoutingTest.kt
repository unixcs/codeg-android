package app.codeg.android.core.network

import app.codeg.android.core.model.AcpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

/** Verifies the WebSocket frame router (dual-layer channel vs attach protocol). */
class FrameRoutingTest {

    private val json = CodegJson.response
    private fun parse(raw: String): StreamFrame? = EventStream.parseFrame(raw, json)

    @Test
    fun `legacy ready channel maps to Ready`() {
        assertEquals(StreamFrame.Ready, parse("""{"channel":"__ready__","payload":null}"""))
    }

    @Test
    fun `firehose frames pass through as Channel with payload intact`() {
        // 批v173 C1: the firehose is no longer dropped — consumers filter on
        // channel and refetch. `__ready__` keeps its Ready mapping above.
        val frame = parse(
            """{"channel":"automation://changed","payload":
               {"kind":"run_settled","automation_id":7,"run_id":9,"status":"failed"}}""",
        )
        assertTrue(frame is StreamFrame.Channel)
        frame as StreamFrame.Channel
        assertEquals("automation://changed", frame.channel)
        assertEquals("run_settled", (frame.payload["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("failed", (frame.payload["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        // A missing payload still routes (empty object) — the server always sends one.
        assertEquals(StreamFrame.Channel("task://changed", kotlinx.serialization.json.JsonObject(emptyMap())), parse("""{"channel":"task://changed"}"""))
    }

    @Test
    fun `event frame unwraps the envelope`() {
        val frame = parse(
            """{"type":"event","subscription_id":"s","envelope":
               {"seq":1,"connection_id":"c","type":"content_delta","text":"hello"}}""",
        )
        assertTrue(frame is StreamFrame.Event)
        val event = (frame as StreamFrame.Event).envelope.event
        assertEquals(AcpEvent.ContentDelta("hello"), event)
    }

    @Test
    fun `pong and detached route correctly`() {
        assertEquals(StreamFrame.Pong, parse("""{"type":"pong"}"""))
        assertEquals(StreamFrame.Detached("lagged"), parse("""{"type":"detached","reason":"lagged"}"""))
    }

    @Test
    fun `garbage and unknown frames are dropped`() {
        assertNull(parse("not json"))
        assertNull(parse("""{"type":"mystery"}"""))
    }

    @Test
    fun `websocket url derives ws and wss with the events path`() {
        assertEquals("ws://host:3080/ws/events", EventStream.websocketUrl("http://host:3080"))
        assertEquals("wss://host/ws/events", EventStream.websocketUrl("https://host/"))
    }
}
