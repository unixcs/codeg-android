package app.codeg.android.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.SessionProjectRepository
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.designsystem.theme.CodegTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → 会话项目管理 (batch v132). CRUD over the cross-device session
 * projects. Removing a project never deletes sessions — membership only; the
 * confirm dialog says so explicitly.
 */
@Composable
fun SessionProjectsScreen(viewModel: SessionProjectsViewModel = hiltViewModel()) {
    val colors = CodegTheme.colors
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()

    // null = no dialog; DialogKind drives which one renders (one dialog at a time).
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialogText by rememberSaveable { mutableStateOf("") }

    fun openCreate() {
        dialogProjectId = null
        dialogText = ""
        dialog = "create"
    }

    fun openRename(project: SessionProject) {
        dialogProjectId = project.id
        dialogText = project.name
        dialog = "rename"
    }

    fun openDelete(project: SessionProject) {
        dialogProjectId = project.id
        dialog = "delete"
    }

    Box(Modifier.fillMaxSize()) {
        if (projects.isEmpty()) {
            Text(
                text = stringResource(R.string.projects_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textTertiary,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    val count = members[project.id]?.size ?: 0
                    ListItem(
                        headlineContent = {
                            Text(
                                text = project.name,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.projects_member_count, count),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { openRename(project) }) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = stringResource(R.string.projects_rename),
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(onClick = { openDelete(project) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.projects_delete),
                                        tint = colors.danger,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = colors.textPrimary,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { openRename(project) },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { openCreate() },
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.projects_create)) },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    when (dialog) {
        "create", "rename" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = {
                Text(stringResource(if (dialog == "create") R.string.projects_create else R.string.projects_rename))
            },
            text = {
                OutlinedTextField(
                    value = dialogText,
                    onValueChange = { dialogText = it },
                    placeholder = { Text(stringResource(R.string.projects_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = dialogText.trim()
                        if (name.isNotEmpty()) {
                            val id = dialogProjectId
                            if (dialog == "create") viewModel.create(name) else if (id != null) viewModel.rename(id, name)
                            dialog = null
                        }
                    },
                    enabled = dialogText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.projects_delete)) },
            text = { Text(stringResource(R.string.projects_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    dialogProjectId?.let { viewModel.remove(it) }
                    dialog = null
                }) {
                    Text(stringResource(R.string.common_delete), color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Thin pass-through to [SessionProjectRepository]. */
@HiltViewModel
class SessionProjectsViewModel @Inject constructor(
    private val repository: SessionProjectRepository,
) : ViewModel() {
    val projects = repository.projects
    val members = repository.members

    fun create(name: String) = viewModelScope.launch { repository.create(name) }
    fun rename(id: String, name: String) = viewModelScope.launch { repository.rename(id, name) }
    fun remove(id: String) = viewModelScope.launch { repository.remove(id) }
}
