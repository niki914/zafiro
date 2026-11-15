package com.tricreta.zafiro.autonomy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tricreta.zafiro.autonomy.core.Quotas
import com.tricreta.zafiro.autonomy.data.AutonomyDatabase
import com.tricreta.zafiro.autonomy.team.TeamServiceImpl
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamServiceTest {
    private lateinit var db: AutonomyDatabase
    private lateinit var svc: TeamServiceImpl
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutonomyDatabase::class.java).allowMainThreadQueries().build()
        svc = TeamServiceImpl(db.dao(), { now }, { ZoneId.of("Africa/Nairobi") }, { Quotas(mapOf("comment" to 2, "reply" to 50)) }, { 0.5 })
    }

    @After fun tearDown() = db.close()

    private fun call(tool: String, vararg kv: Pair<String, String>) = runBlocking { svc.run(tool, kv.toMap()) }

    @Test fun boardHandoffLifecycle() {
        val id = JSONObject(call("board", "op" to "add", "title" to "Draft note", "owner" to "Writer", "workspace" to "substack").text).getLong("id")
        val claimed = JSONObject(call("board", "op" to "next", "owner" to "writer", "workspace" to "substack").text).getJSONObject("item")
        assertEquals(id, claimed.getLong("id")); assertEquals("DOING", claimed.getString("status"))
        assertTrue(JSONObject(call("board", "op" to "next", "owner" to "Writer", "workspace" to "substack").text).isNull("item"))
        assertFalse(call("board", "op" to "update", "id" to "$id", "status" to "DONE").ok, "finishing needs a result")
        assertTrue(call("board", "op" to "update", "id" to "$id", "status" to "DONE", "result" to "saved notes/1.md").ok)
        assertFalse(call("board", "op" to "update", "id" to "$id", "status" to "TODO").ok, "done never reopens")
        assertEquals(1, JSONArray(call("board", "op" to "list", "workspace" to "substack").text).length())
    }

    @Test fun boardRefusesDelegationBeyondDepthThree() {
        assertTrue(call("board", "op" to "add", "title" to "x", "depth" to "2").ok)
        assertFalse(call("board", "op" to "add", "title" to "x", "depth" to "3").ok)
    }

    @Test fun staleDoingWorkIsReopenedAfterACrash() {
        val id = JSONObject(call("board", "op" to "add", "title" to "t", "owner" to "A").text).getLong("id")
        call("board", "op" to "next", "owner" to "A")
        now += 30 * 60_000L
        assertEquals(1, JSONObject(call("board", "op" to "resume").text).getInt("reopened"))
        assertEquals(id, JSONObject(call("board", "op" to "next", "owner" to "A").text).getJSONObject("item").getLong("id"))
    }

    @Test fun engageIsAutonomousUntilTheDailyCap() {
        val plan = { call("engage", "op" to "plan", "action" to "comment", "platform" to "substack", "workspace" to "s", "text" to "nice") }
        assertEquals("AUTO", JSONObject(plan().text).getString("gate"))
        assertEquals("AUTO", JSONObject(plan().text).getString("gate"))
        assertEquals("DENIED", JSONObject(plan().text).getString("gate"), "third plan exceeds the cap of 2")
    }

    @Test fun seriousActionsWaitForApprovalAndCannotBeRecordedDoneBefore() {
        val first = JSONObject(call("engage", "op" to "plan", "action" to "dm", "workspace" to "s", "text" to "hello").text)
        assertEquals("NEEDS_APPROVAL", first.getString("gate"))
        val id = first.getLong("id")
        assertFalse(call("engage", "op" to "done", "id" to "$id").ok, "cannot record done before approval")
        assertTrue(call("engage", "op" to "approve", "id" to "$id").ok)
        assertTrue(call("engage", "op" to "done", "id" to "$id").ok)
    }

    @Test fun tenApprovalsPromoteASeriousTypeAndOneRejectionDemotesIt() {
        repeat(10) {
            val id = JSONObject(call("engage", "op" to "plan", "action" to "dm", "workspace" to "p").text).getLong("id")
            call("engage", "op" to "approve", "id" to "$id")
        }
        assertEquals("AUTO", JSONObject(call("engage", "op" to "plan", "action" to "dm", "workspace" to "p").text).getString("gate"))
        val id = JSONObject(call("engage", "op" to "plan", "action" to "dm", "workspace" to "q").text).getLong("id")
        call("engage", "op" to "reject", "id" to "$id", "reason" to "no")
        assertEquals("NEEDS_APPROVAL", JSONObject(call("engage", "op" to "plan", "action" to "dm", "workspace" to "q").text).getString("gate"))
    }

    @Test fun rejectionStoresABadExampleWithTheReason() {
        val id = JSONObject(call("engage", "op" to "plan", "action" to "comment", "workspace" to "s", "platform" to "substack", "text" to "Great post!", "serious" to "true").text).getLong("id")
        call("engage", "op" to "reject", "id" to "$id", "reason" to "generic")
        val ex = JSONArray(call("examples", "op" to "list", "platform" to "substack").text)
        assertEquals("BAD", ex.getJSONObject(0).getString("verdict")); assertEquals("generic", ex.getJSONObject(0).getString("reason"))
    }

    @Test fun paceReturnsWithinRange() {
        val w = JSONObject(call("engage", "op" to "pace", "min_s" to "10", "max_s" to "30").text).getInt("wait_seconds")
        assertEquals(20, w)
    }

    @Test fun badInputIsReportedNotThrown() {
        assertFalse(call("board", "op" to "update").ok)
        assertFalse(call("examples", "op" to "add", "text" to "x", "verdict" to "MEH").ok)
        assertFalse(call("nope").ok)
    }
}
