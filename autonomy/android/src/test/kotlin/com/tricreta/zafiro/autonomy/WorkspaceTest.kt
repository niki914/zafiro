package com.tricreta.zafiro.autonomy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tricreta.zafiro.autonomy.data.AgentProfileEntity
import com.tricreta.zafiro.autonomy.data.AutonomyDatabase
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.workspace.WorkspaceDef
import com.tricreta.zafiro.autonomy.workspace.WorkspaceInstaller
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceTest {
    private lateinit var db: AutonomyDatabase
    private val dao get() = db.dao()
    private val installer get() = WorkspaceInstaller(dao) { 1_000L }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutonomyDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun def(version: Int = 1, extraTask: Boolean = false) = WorkspaceDef.parse(
        """{"id":"substack","version":$version,"name":"Substack","platform":"substack","quotas":"comment=3",
          "agents":[{"id":"director","name":"Director","instructions":"lead","tools":["agent","board"],"skills":["a"]},
                    {"id":"poster","name":"Poster","instructions":"post","usesScreen":true}],
          "tasks":[{"key":"morning","name":"Morning","prompt":"go","agent":"director","trigger":"DAILY","time":"07:15"},
                   {"key":"weekly","name":"Weekly","prompt":"plan","agent":"director","trigger":"WEEKLY","time":"18:00","days":["SUN"]},
                   {"key":"off","name":"Optional","prompt":"x","agent":"poster","trigger":"DAILY","time":"09:00","enabled":false}
                   ${if (extraTask) ""","{"key":"new","name":"New","prompt":"n","agent":"poster","trigger":"INTERVAL","intervalMinutes":120}""" else ""}]}""",
    )

    @Test fun installAddsEverythingSwitchedOffAndIsIdempotent() = runBlocking {
        val r = installer.install(def())
        assertTrue(r.installed); assertEquals(2, r.agents); assertEquals(3, r.tasks)
        assertTrue(dao.agentsOfWorkspace("substack").none { it.enabled })
        assertTrue(dao.tasksOfWorkspace("substack").none { it.enabled })
        assertFalse(installer.install(def()).installed, "same version installs nothing")
        assertEquals("comment=3", dao.workspace("substack")!!.quotas)
    }

    @Test fun enablingSchedulesDefaultEnabledTasksOnlyAndDisablingStopsAll() = runBlocking {
        installer.install(def())
        assertEquals(2, installer.setEnabled(def(), true))
        assertEquals(setOf("ws.substack.morning", "ws.substack.weekly"), dao.tasksOfWorkspace("substack").filter { it.enabled }.map { it.id }.toSet())
        assertTrue(dao.agentsOfWorkspace("substack").all { it.enabled })
        installer.setEnabled(def(), false)
        assertTrue(dao.tasksOfWorkspace("substack").none { it.enabled })
        assertTrue(dao.agentsOfWorkspace("substack").none { it.enabled })
    }

    @Test fun newerVersionUpdatesContentKeepsOnStateAndDropsRemovedTasks() = runBlocking {
        installer.install(def(1)); installer.setEnabled(def(1), true)
        val r = installer.install(def(2, extraTask = true))
        assertTrue(r.installed)
        assertTrue(dao.workspace("substack")!!.enabled)
        assertTrue(dao.task("ws.substack.new")!!.enabled)
        assertEquals(120L, dao.task("ws.substack.new")!!.intervalMinutes)
        assertEquals(2, dao.tasksOfWorkspace("substack").count { it.enabled && it.id != "ws.substack.new" })
    }

    @Test fun handMadeTasksOfTheSameAgentsAreRetiredNotDeleted() = runBlocking {
        dao.upsertAgent(AgentProfileEntity("director", "Old", "old", "cfg-1", "", "", false, true, 1, 1))
        dao.upsertTask(
            TaskEntity("old1", "Old task", "p", true, "DAILY", null, null, null, "08:00", 127, 1, "", 1, 1, 0, "SKIP", 80, 60_000, "", 1000, agentId = "director"),
        )
        val r = installer.install(def())
        assertEquals(1, r.retiredLegacyTasks)
        val old = dao.task("old1")!!
        assertFalse(old.enabled); assertEquals("legacy:substack", old.workspaceId)
        assertEquals("cfg-1", dao.agent("director")!!.configId, "the owner's model choice survives")
        assertEquals("lead", dao.agent("director")!!.instructions)
    }

    @Test fun badDefinitionsAreRejectedWithAReason() {
        listOf(
            "nope", """{"agents":[]}""", """{"id":"Bad Id"}""",
            """{"id":"ok1","agents":[{"id":"a","name":"A","instructions":"i"}],"tasks":[{"key":"k","name":"n","prompt":"p","agent":"ghost","trigger":"DAILY","time":"07:00"}]}""",
            """{"id":"ok1","tasks":[{"key":"k","name":"n","prompt":"p","trigger":"DAILY","time":"7am"}]}""",
        ).forEach { raw -> assertFailsWith<WorkspaceDef.Invalid>(raw) { WorkspaceDef.parse(raw) } }
    }
}
