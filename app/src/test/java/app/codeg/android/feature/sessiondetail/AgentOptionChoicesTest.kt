package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOptionChoicesTest {

    private fun modelOption(groups: List<SessionConfigSelectGroup> = emptyList()) = SessionConfigOption(
        id = "model",
        name = "Model",
        category = "model",
        kind = SessionConfigKind(
            groups = groups,
            options = if (groups.isEmpty()) listOf(
                SessionConfigSelectOption(value = "glm", name = "GLM"),
                SessionConfigSelectOption(value = "deepseek", name = "DeepSeek"),
                SessionConfigSelectOption(value = "hy3", name = "Hy3"),
            ) else emptyList(),
        ),
    )

    @Test
    fun `flat options become searchable choices`() {
        val choices = modelOption().toChoices(currentValue = "deepseek")
        assertEquals(3, choices.size)
        assertEquals("DeepSeek", choices[1].label)
        assertTrue(choices[1].selected)
    }

    @Test
    fun `grouped options flatten without losing labels`() {
        val grouped = SessionConfigOption(
            id = "model",
            kind = SessionConfigKind(
                groups = listOf(
                    SessionConfigSelectGroup(
                        group = "magpie",
                        name = "Magpie",
                        options = listOf(SessionConfigSelectOption(value = "x", name = "Model X")),
                    ),
                ),
            ),
        )
        val choice = grouped.kind.groups.single().options.single()
        assertEquals("x", choice.value)
        assertEquals("Model X", choice.name)
    }

    @Test
    fun `searched choice keeps its real option value`() {
        val choices = flattenChoiceValues(modelOption())
        assertEquals("glm", choices.first().value)
    }

    @Test
    fun `empty option catalog yields no compact model selector`() {
        val option = SessionConfigOption(
            id = "model",
            category = "model",
            kind = SessionConfigKind(groups = emptyList(), options = emptyList()),
        )
        assertTrue(flattenChoiceValues(option).isEmpty())
    }
}
