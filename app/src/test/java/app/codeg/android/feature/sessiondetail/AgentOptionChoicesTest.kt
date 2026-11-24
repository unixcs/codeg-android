package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentOptionChoicesTest {

    private fun option(groups: List<SessionConfigSelectGroup> = emptyList()) = SessionConfigOption(
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
        val choices = option().let {
            it.id to it.kind.options.map { value ->
                Triple(value.value, value.name, value.value == "deepseek")
            }
        }
        assertEquals("model", choices.first)
        assertEquals(3, choices.second.size)
        assertEquals("DeepSeek", choices.second[1].second)
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
}
