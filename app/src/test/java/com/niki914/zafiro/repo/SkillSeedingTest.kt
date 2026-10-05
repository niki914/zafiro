package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SkillApi.Companion.reseedSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class SkillSeedingTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var target: File

    private var assetContent: String = ""

    private val openAsset = { _: String -> ByteArrayInputStream(assetContent.toByteArray()) }

    @Before
    fun setUp() {
        target = temporaryFolder.newFolder("skills").resolve("skill-a")
    }

    private fun reseed() = reseedSkill(
        target,
        listOf("SKILL.md"),
        openAsset,
    )

    @Test
    fun seedsNewSkillAndWritesMarker() {
        assetContent = "v1"

        reseed()

        assertEquals("v1", target.resolve("SKILL.md").readText())
        assertTrue(target.resolve(".seed").isFile)
    }

    @Test
    fun overwritesUnmodifiedInstallWhenAssetChanges() {
        assetContent = "v1"
        reseed()

        assetContent = "v2"
        reseed()

        assertEquals("v2", target.resolve("SKILL.md").readText())
    }

    @Test
    fun leavesUserModifiedSkillAlone() {
        assetContent = "v1"
        reseed()
        target.resolve("SKILL.md").writeText("user edited")

        assetContent = "v2"
        reseed()

        assertEquals("user edited", target.resolve("SKILL.md").readText())
        assertFalse(target.resolve(".seed").readText() == "user edited")
    }

    @Test
    fun restoresUpdateEligibilityAfterUserResetsContentToSeededVersion() {
        assetContent = "v1"
        reseed()
        target.resolve("SKILL.md").writeText("user edited")
        target.resolve("SKILL.md").writeText("v1") // user restores the seeded content

        assetContent = "v3"
        reseed()

        assertEquals("v3", target.resolve("SKILL.md").readText())
    }

    @Test
    fun updatesLegacyInstallWithoutMarkerOnce() {
        assetContent = "v1"
        reseed()
        target.resolve(".seed").delete() // pre-marker install

        assetContent = "v2"
        reseed()

        assertEquals("v2", target.resolve("SKILL.md").readText())
        assertTrue(target.resolve(".seed").isFile)
    }

    @Test
    fun leavesExtraUserFilesIntact() {
        assetContent = "v1"
        reseed()
        val notes = target.resolve("notes.txt").apply { writeText("mine") }

        assetContent = "v2"
        reseed()

        assertEquals("mine", notes.readText())
    }
}
