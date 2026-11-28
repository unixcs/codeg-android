package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOptionPresentationTest {
    @Test
    fun `rate comes from name before description`() {
        assertEquals("0.29x", modelRate("Foo · 0.29x", "Provider"))
        assertEquals("0.08x", modelRate("Foo · 0.08 ×", null))
        assertEquals("0.08x", modelRate("Foo · 0.08 X", null))
        assertEquals("0.08x", modelRate(null, "0.08x cheap"))
        assertNull(modelRate("Foo", "Provider"))
    }

    @Test
    fun `flat slash values derive groups and strip shared display prefix`() {
        val option = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(
                    SessionConfigSelectOption("magpie/a", "Magpie/Alpha Long Name", "0.08x"),
                    SessionConfigSelectOption("magpie/b", "Magpie/Beta", "0.11x"),
                ),
            ),
        )
        val groups = deriveModelGroups(option).orEmpty()
        assertEquals(1, groups.size)
        assertEquals("Magpie", groups.single().name)
        assertEquals("Alpha Long Name", groups.single().options[0].name)
        val presentation = modelPresentation(groups.single().options[0], groups.single())
        assertEquals("Alpha Long Name", presentation.shortLabel)
        assertEquals("0.08x", presentation.rate)
    }

    @Test
    fun `mixed floating and prefixed options keep server order`() {
        val option = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(
                    SessionConfigSelectOption("z", "Zed", null),
                    SessionConfigSelectOption("magpie/a", "Magpie/Alpha", null),
                    SessionConfigSelectOption("magpie/b", "Magpie/Beta", null),
                ),
            ),
        )
        val groups = deriveModelGroups(option).orEmpty()
        assertEquals(2, groups.size)
        assertEquals("__ungrouped__", groups[0].key)
        assertNull(groups[0].name)
        assertEquals("magpie", groups[1].key)
        assertEquals("Magpie", groups[1].name)
    }

    @Test
    fun `single prefixed item strips only when display name repeats prefix`() {
        val strippable = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(SessionConfigSelectOption("magpie/model", "Magpie/Model", null)),
            ),
        )
        val derived = deriveModelGroups(strippable).orEmpty()
        assertEquals("Model", derived.single().options.single().name)
        assertEquals("Model", modelPresentation(derived.single().options.single(), derived.single()).shortLabel)

        val flat = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(SessionConfigSelectOption("magpie/model", "Model", null)),
            ),
        )
        assertNull(deriveModelGroups(flat))
        assertEquals(listOf(AgentModelGroup("__all__", null, flat.kind.options)), modelGroups(flat))
    }

    @Test
    fun `server groups are preserved verbatim`() {
        val option = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                groups = listOf(
                    SessionConfigSelectGroup(
                        group = "provider_models",
                        name = "Provider models",
                        options = listOf(SessionConfigSelectOption("x", "X", "Provider")),
                    ),
                ),
            ),
        )
        val groups = modelGroups(option)
        assertEquals("provider_models", groups.single().key)
        assertEquals("Provider models", groups.single().name)
        assertEquals("x", groups.single().options.single().value)
    }

    @Test
    fun `search matches label value and provider description`() {
        val groups = listOf(
            AgentModelGroup(
                key = "magpie",
                name = "Magpie",
                options = listOf(
                    SessionConfigSelectOption("magpie/model-a", "model-a", "Provider A"),
                    SessionConfigSelectOption("magpie/model-b", "model-b", "Provider B"),
                ),
            ),
        )
        assertEquals(1, filterModelGroups(groups, "provider b").single().options.size)
        assertEquals(1, filterModelGroups(groups, "model-a").single().options.size)
        assertEquals(1, filterModelGroups(groups, "").size)
    }

    @Test
    fun `grid density falls back for long labels`() {
        val short = listOf(ConfigOptionChoice("a", "ab", null, false), ConfigOptionChoice("b", "cd", "desc", false))
        val long = listOf(ConfigOptionChoice("a", "x".repeat(19), null, false))
        assertEquals(2, requestedConfigColumns(short, 2))
        assertEquals(1, requestedConfigColumns(long, 2))
        assertEquals(listOf(listOf("a"), listOf("b")), gridRows(listOf("a", "b"), 2))
    }

    @Test
    fun `model grid density falls back for long labels`() {
        val short = listOf(SessionConfigSelectOption("a", "short", null))
        val long = listOf(SessionConfigSelectOption("a", "x".repeat(19), null))
        assertEquals(2, requestedModelColumns(short, 2))
        assertEquals(1, requestedModelColumns(long, 2))
    }

}
