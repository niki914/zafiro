package com.niki914.zafiro.app.ui.settings.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSpecTest {

    private enum class DummyField {
        Name,
        Age,
        Active,
        Tags,
    }

    private data class DummyState(
        val name: String = "",
        val age: Int = 0,
        val active: Boolean = false,
        val tags: Array<String> = emptyArray(),
    )

    private val spec = buildFormSpec<DummyState, DummyField> {
        field(DummyField.Name, 101) { it.name }
        field(DummyField.Age, 102) { it.age }
        field(DummyField.Active, 103) { it.active }
        field(DummyField.Tags, 104) { it.tags }
    }

    @Test
    fun baselineNull_reportsNoChangesAndEmptyLabels() {
        val current = DummyState(name = "alice", age = 20, active = true)

        assertFalse(spec.hasChanges(baseline = null, current = current))
        assertTrue(spec.dirtyFieldLabelRes(baseline = null, current = current).isEmpty())
    }

    @Test
    fun identicalState_reportsNoChanges() {
        val state = DummyState(name = "bob", age = 25, active = false, tags = arrayOf("a", "b"))

        assertFalse(spec.hasChanges(baseline = state, current = state.copy(tags = arrayOf("a", "b"))))
        assertTrue(spec.dirtyFieldLabelRes(baseline = state, current = state.copy(tags = arrayOf("a", "b"))).isEmpty())
    }

    @Test
    fun singleFieldModified_reportsHasChangesAndMatchesLabelRes() {
        val baseline = DummyState(name = "bob", age = 25, active = false)
        val modified = baseline.copy(name = "charlie")

        assertTrue(spec.hasChanges(baseline = baseline, current = modified))
        assertEquals(listOf(101), spec.dirtyFieldLabelRes(baseline = baseline, current = modified))
    }

    @Test
    fun multipleFieldsModified_reportsAllDirtyLabelResInRegistrationOrder() {
        val baseline = DummyState(name = "bob", age = 25, active = false)
        val modified = baseline.copy(age = 30, active = true)

        assertTrue(spec.hasChanges(baseline = baseline, current = modified))
        assertEquals(listOf(102, 103), spec.dirtyFieldLabelRes(baseline = baseline, current = modified))
    }

    @Test
    fun arrayContentModified_reportsHasChangesAndMatchesLabelRes() {
        val baseline = DummyState(tags = arrayOf("a", "b"))
        val modified = DummyState(tags = arrayOf("a", "c"))

        assertTrue(spec.hasChanges(baseline = baseline, current = modified))
        assertEquals(listOf(104), spec.dirtyFieldLabelRes(baseline = baseline, current = modified))
    }

    @Test
    fun multipleFieldsSharingSameLabelRes_deduplicatesDirtyLabels() {
        val sharedLabelSpec = buildFormSpec<DummyState, DummyField> {
            field(DummyField.Name, 201) { it.name }
            field(DummyField.Age, 201) { it.age } // 共享同一个展示 LabelRes
        }
        val baseline = DummyState(name = "bob", age = 25)
        val modified = DummyState(name = "alice", age = 30)

        assertTrue(sharedLabelSpec.hasChanges(baseline = baseline, current = modified))
        assertEquals(listOf(201), sharedLabelSpec.dirtyFieldLabelRes(baseline = baseline, current = modified))
    }
}
