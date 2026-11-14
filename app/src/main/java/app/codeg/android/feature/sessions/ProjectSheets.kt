package app.codeg.android.feature.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import app.codeg.android.core.datastore.ADD_TO_PROJECT_SORT_COUNT
import app.codeg.android.core.datastore.ADD_TO_PROJECT_SORT_CREATED
import app.codeg.android.core.datastore.ADD_TO_PROJECT_SORT_MANUAL
import app.codeg.android.core.datastore.ADD_TO_PROJECT_SORT_NAME
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The two project sheets (batch v132). Both are single-instance, hoisted by the
 * calling screen — a ModalBottomSheet owns a window, so composing one per row is
 * off the table (same constraint as the row menus).
 */

/**
 * Long-press → "添加到项目": pick an existing project or create-and-join inline.
 * 批v179: the projects render as a 1/2/3-column grid of cards (default 2) with a
 * pluggable order (default most-sessions-first, 拍板). Both preferences persist
 * through the caller — this sheet stays stateless (the owning screen hoists one
 * instance), so columns/sort arrive as params and leave via the change callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToProjectSheet(
    targetTitle: String,
    projects: List<SessionProject>,
    /** Project ids the target session already belongs to (checked rows). */
    memberOf: Set<String>,
    memberCount: (SessionProject) -> Int,
    /** 1 = the classic single checkbox list, 2/3 = card grid columns. */
    columns: Int,
    /** One of the [ADD_TO_PROJECT_SORT_COUNT] family constants. */
    sort: String,
    onColumnsChange: (Int) -> Unit,
    onSortChange: (String) -> Unit,
    onToggle: (projectId: String, member: Boolean) -> Unit,
    /** Creates the project; [onCreated] runs on the main thread afterwards so the
     *  caller can add the target to it (create-and-join in one gesture). */
    onCreate: (name: String, onCreated: (SessionProject) -> Unit) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    var newName by rememberSaveable { mutableStateOf("") }
    var sortPickerOpen by rememberSaveable { mutableStateOf(false) }

    // Recomputed on every recomposition on purpose: memberCount reads live state,
    // and a remember(projects, sort) here would go stale when memberships change
    // while the sheet is open (toggle a row → the count-sorted order shifts).
    val sortedProjects = when (sort) {
        ADD_TO_PROJECT_SORT_COUNT -> projects.sortedWith(
            compareByDescending<SessionProject> { memberCount(it) }.thenBy { it.sortOrder },
        )
        ADD_TO_PROJECT_SORT_NAME -> projects.sortedBy { it.name.lowercase() }
        ADD_TO_PROJECT_SORT_CREATED -> projects.sortedByDescending { it.createdAt }
        // ADD_TO_PROJECT_SORT_MANUAL — the creation/sortOrder order.
        else -> projects.sortedBy { it.sortOrder }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .sheetContentScrollLock()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.add_project_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // Column cycler 1→2→3→1. The digit sits beside the icon because a
                // bare icon can't hint a three-way cycle.
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onColumnsChange(columns % 3 + 1) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Rounded.GridView,
                        contentDescription = stringResource(R.string.add_project_columns_cd, columns),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = columns.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
                IconButton(onClick = { sortPickerOpen = true }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.add_project_sort_cd),
                        tint = colors.textSecondary,
                    )
                }
            }
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
            } else if (columns == 1) {
                // The classic single list (pre-v179 look): checkbox rows, scrollable.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    sortedProjects.forEach { project ->
                        AddToProjectListRow(
                            project = project,
                            member = project.id in memberOf,
                            memberCount = memberCount(project),
                            onToggle = { onToggle(project.id, it) },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sortedProjects, key = { it.id }) { project ->
                        AddToProjectCard(
                            project = project,
                            member = project.id in memberOf,
                            memberCount = memberCount(project),
                            onToggle = { onToggle(project.id, project.id !in memberOf) },
                        )
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

    // Same-level conditional sheet (QuickSaveSheet's device-picker pattern) — a
    // second ModalBottomSheet inside the first's slot would nest windows.
    if (sortPickerOpen) {
        OptionListSheet(
            title = stringResource(R.string.add_project_sort_title),
            options = listOf(
                PickerOption(ADD_TO_PROJECT_SORT_COUNT, stringResource(R.string.add_project_sort_count)),
                PickerOption(ADD_TO_PROJECT_SORT_MANUAL, stringResource(R.string.add_project_sort_manual)),
                PickerOption(ADD_TO_PROJECT_SORT_NAME, stringResource(R.string.add_project_sort_name)),
                PickerOption(ADD_TO_PROJECT_SORT_CREATED, stringResource(R.string.add_project_sort_created)),
            ),
            selectedId = sort,
            onSelect = { onSortChange(it.id) },
            onDismiss = { sortPickerOpen = false },
        )
    }
}

/** The classic single-list row (columns == 1): checkbox + name + count. */
@Composable
private fun AddToProjectListRow(
    project: SessionProject,
    member: Boolean,
    memberCount: Int,
    onToggle: (Boolean) -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle(!member) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = member, onCheckedChange = onToggle)
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
            text = memberCount.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
    }
}

/** One project as a selectable grid card: bookmark + name up top, member count
 *  below. Membership = accent border + tinted fill + check, the chip rows'
 *  selected language. */
@Composable
private fun AddToProjectCard(
    project: SessionProject,
    member: Boolean,
    memberCount: Int,
    onToggle: () -> Unit,
) {
    val colors = CodegTheme.colors
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = if (member) colors.selectedBg else colors.bgElevated,
        border = BorderStroke(1.dp, if (member) colors.accent else colors.hairline),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Bookmark,
                    contentDescription = null,
                    tint = if (member) colors.selectedText else colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (member) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (member) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.selectedText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = memberCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
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
                .sheetContentScrollLock()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.project_filter_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
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
}
