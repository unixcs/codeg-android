package app.codeg.android.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The two project sheets (batch v132). Both are single-instance, hoisted by the
 * calling screen — a ModalBottomSheet owns a window, so composing one per row is
 * off the table (same constraint as the row menus).
 */

/** One-way "jump to Settings → session projects" signal (same shape as
 *  FloatingBallSettingsBus): fired by the sheets' 管理 link in MainShell, consumed
 *  by SettingsScreen on composition. */
object ProjectSettingsBus {
    val pending = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun open() {
        pending.value = true
    }
}

/** Long-press → "添加到项目": pick an existing project or create-and-join inline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToProjectSheet(
    targetTitle: String,
    projects: List<SessionProject>,
    /** Project ids the target session already belongs to (checked rows). */
    memberOf: Set<String>,
    memberCount: (SessionProject) -> Int,
    onToggle: (projectId: String, member: Boolean) -> Unit,
    /** Creates the project; [onCreated] runs on the main thread afterwards so the
     *  caller can add the target to it (create-and-join in one gesture). */
    onCreate: (name: String, onCreated: (SessionProject) -> Unit) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var newName by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.add_project_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = targetTitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (projects.isEmpty()) {
                Text(
                    text = stringResource(R.string.add_project_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                Column(Modifier.padding(top = 8.dp)) {
                    projects.forEach { project ->
                        val member = project.id in memberOf
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onToggle(project.id, !member) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = member, onCheckedChange = { onToggle(project.id, it) })
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                            )
                            Text(
                                text = memberCount(project).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.add_project_new_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            onCreate(name) { created ->
                                onToggle(created.id, true)
                            }
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.add_project_new),
                        tint = if (newName.isNotBlank()) colors.accent else colors.textTertiary,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManage)
                    .padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.project_filter_manage),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** Tapping the "全部" device chip: pick a project to filter the aggregate list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilterSheet(
    projects: List<SessionProject>,
    counts: Map<String, Int>,
    activeId: String?,
    /** null = clear the filter ("全部会话"). */
    onSelect: (String?) -> Unit,
    onCreateActivate: (name: String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var newName by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.project_filter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(null) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = if (activeId == null) colors.accent else colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.project_filter_all),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (activeId == null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (activeId == null) colors.accent else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (activeId == null) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                }
            }

            projects.forEach { project ->
                val active = project.id == activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(project.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.Bookmark,
                        contentDescription = null,
                        tint = if (active) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) colors.accent else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (counts[project.id] ?: 0).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                    if (active) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.add_project_new_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            onCreateActivate(name)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.project_filter_create),
                        tint = if (newName.isNotBlank()) colors.accent else colors.textTertiary,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManage)
                    .padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.project_filter_manage),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
