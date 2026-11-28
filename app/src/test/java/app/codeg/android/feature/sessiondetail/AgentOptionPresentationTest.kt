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
        assertEquals("0.08x", modelRate(null, "0.08x"))
        assertEquals("0.21x", modelRate("Foo", "x0.21"))
        assertEquals("0.21x", modelRate("Foo", "Fast model · x0.21 credits"))
        assertEquals("0.08x", modelRate("Foo · 0.08 credits", null))
        assertNull(modelRate("Foo", "Provider"))
    }

    @Test
    fun `model presentation strips trailing rate from label`() {
        val group = AgentModelGroup("magpie", "Magpie", emptyList())
        val presentation = modelPresentation(
            SessionConfigSelectOption("magpie/foo", "Magpie/Foo · 0.08x", "0.08x"),
            group,
        )
        assertEquals("Foo", presentation.shortLabel)
        assertEquals("0.08x", presentation.rate)
    }

    @Test
    fun `codebuddy prefixed rate stays on one compact model row`() {
        val option = SessionConfigSelectOption(
            "deepseek-v4.1-flash",
            "DeepSeek-V4.1-Flash · x0.08",
            "Fast model · x0.08 credits",
        )
        val presentation = modelPresentation(option, null)

        assertEquals("DeepSeek-V4.1-Flash", presentation.shortLabel)
        assertEquals("0.08x", presentation.rate)
    }

    @Test
    fun `model presentation compacts magpie plumbing and keeps model suffix`() {
        val option = SessionConfigSelectOption(
            "group/deepseek-v4.1-flash:free",
            "magpie › group/deepseek-v4.1-flash:free · 0.08x",
            null,
        )
        val flat = AgentModelGroup("__all__", null, listOf(option))
        val presentation = modelPresentation(option, flat)

        assertEquals("deepseek-v4.1-flash:free", presentation.shortLabel)
        assertEquals("0.08x", presentation.rate)
    }

    @Test
    fun `active model label uses compact row presentation and rate`() {
        val option = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(
                    SessionConfigSelectOption("group/alpha", "magpie › group/alpha · 0.08x", null),
                    SessionConfigSelectOption("group/beta", "magpie › group/beta · 0.11x", null),
                ),
            ),
        )

        assertEquals("alpha · 0.08x", activeModelLabel(option, "group/alpha"))
        assertEquals("beta · 0.11x", activeModelLabel(option, "group/beta"))
        assertNull(activeModelLabel(option, "missing"))
    }

    @Test
    fun `current config value marks exactly one choice selected`() {
        val option = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                options = listOf(
                    SessionConfigSelectOption("magpie/a", "Magpie/A", "0.08x"),
                    SessionConfigSelectOption("magpie/b", "Magpie/B", "0.11x"),
                ),
            ),
        )
        val choices = option.toChoices("magpie/b")

        assertEquals(listOf(false, true), choices.map { it.selected })
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
        assertEquals(1, derived.size)
        assertEquals("Model", derived.first().options.single().name)
        assertEquals("Model", modelPresentation(derived.first().options.single(), derived.first()).shortLabel)

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
    fun `explicit grid density honors columns and shrinks to option count`() {
        val short = listOf(ConfigOptionChoice("a", "ab", null, false), ConfigOptionChoice("b", "cd", "desc", false))
        val longOne = listOf(ConfigOptionChoice("a", "x".repeat(19), null, false))
        val longThree = listOf(
            ConfigOptionChoice("a", "x".repeat(19), null, false),
            ConfigOptionChoice("b", "y".repeat(19), null, false),
            ConfigOptionChoice("c", "z".repeat(19), null, false),
        )
        assertEquals(2, requestedConfigColumns(short, 2))
        assertEquals(2, requestedConfigColumns(short, 3))
        assertEquals(1, requestedConfigColumns(longOne, 2))
        assertEquals(2, requestedConfigColumns(longThree, 2))
        assertEquals(3, requestedConfigColumns(longThree, 3))
        assertEquals(2, requestedConfigColumns(short, 0))
        assertEquals(1, requestedConfigColumns(longOne, 0))
        assertEquals(listOf(listOf("a", "b")), gridRows(listOf("a", "b"), 2))
    }

    @Test
    fun `auto config density caps columns by label length and choice count`() {
        val shortThree = listOf(
            ConfigOptionChoice("plan", "Plan", null, false),
            ConfigOptionChoice("build", "Build", null, false),
            ConfigOptionChoice("yolo", "YOLO", null, false),
        )
        val medium = listOf(
            ConfigOptionChoice("standard", "Standard", null, false),
            ConfigOptionChoice("pro", "Pro", null, false),
            ConfigOptionChoice("max", "Max", null, false),
        )
        assertEquals(3, requestedConfigColumns(shortThree, 0))
        assertEquals(2, requestedConfigColumns(medium, 0))
    }

    @Test
    fun `model grid density honors the explicit column choice`() {
        assertEquals(1, requestedModelColumns(1))
        assertEquals(2, requestedModelColumns(2))
        assertEquals(1, requestedModelColumns(0))
        assertEquals(2, requestedModelColumns(9))
    }

}
