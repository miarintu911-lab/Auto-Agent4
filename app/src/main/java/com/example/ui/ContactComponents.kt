package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.ChatPlatform
import com.example.agent.ContactProfile
import com.example.agent.RelationshipType
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactProfilesCard(
    contacts: List<ContactProfile>,
    onAddClick: () -> Unit,
    onEditClick: (ContactProfile) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { expanded = !expanded }
                    .testTag("toggle_contacts_section"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.contacts_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ElectricCyan.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${contacts.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.cd_toggle_contacts),
                    tint = TextSecondary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(
                        onClick = onAddClick,
                        border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("add_contact_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = CyberTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.contacts_add_button),
                            color = CyberTeal
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (contacts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.contacts_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            contacts.forEach { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SlateSurfaceElevated)
                                        .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = contact.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!contact.autoReplyEnabled) {
                                                Text(
                                                    text = "Paused",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AmberWarning
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            ContactTagChip(
                                                text = contact.platform.label,
                                                accent = ElectricCyan
                                            )
                                            ContactTagChip(
                                                text = contact.relationship.label,
                                                accent = CyberTeal
                                            )
                                        }
                                        if (contact.customStyleNote.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = contact.customStyleNote,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onEditClick(contact) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("edit_contact_${contact.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = stringResource(R.string.cd_edit_contact),
                                                tint = ElectricCyan
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteClick(contact.id) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("delete_contact_${contact.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = stringResource(R.string.cd_delete_contact),
                                                tint = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactTagChip(
    text: String,
    accent: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditContactDialog(
    initialContact: ContactProfile?,
    onDismiss: () -> Unit,
    onSave: (ContactProfile) -> Unit
) {
    var name by remember(initialContact) { mutableStateOf(initialContact?.name.orEmpty()) }
    var platform by remember(initialContact) {
        mutableStateOf(initialContact?.platform ?: ChatPlatform.BOTH)
    }
    var relationship by remember(initialContact) {
        mutableStateOf(initialContact?.relationship ?: RelationshipType.FRIEND)
    }
    var customNote by remember(initialContact) {
        mutableStateOf(initialContact?.customStyleNote.orEmpty())
    }
    var autoReplyEnabled by remember(initialContact) {
        mutableStateOf(initialContact?.autoReplyEnabled ?: true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        title = {
            Text(
                text = if (initialContact == null) {
                    stringResource(R.string.contacts_dialog_add_title)
                } else {
                    stringResource(R.string.contacts_dialog_edit_title)
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.contacts_name_label)) },
                    placeholder = { Text(stringResource(R.string.contacts_name_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_name_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = CyberTeal
                    )
                )

                Text(
                    text = stringResource(R.string.contacts_platform_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChatPlatform.entries.forEach { item ->
                        FilterChip(
                            selected = platform == item,
                            onClick = { platform = item },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("contact_platform_${item.name.lowercase()}"),
                            label = { Text(item.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberTealDark,
                                selectedLabelColor = CyberTeal
                            )
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.contacts_relationship_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RelationshipType.entries.forEach { rel ->
                        FilterChip(
                            selected = relationship == rel,
                            onClick = { relationship = rel },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("contact_rel_${rel.name.lowercase()}"),
                            label = { Text(rel.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberTealDark,
                                selectedLabelColor = CyberTeal
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = customNote,
                    onValueChange = { customNote = it },
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.contacts_note_label)) },
                    placeholder = { Text(stringResource(R.string.contacts_note_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_note_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberTeal,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = CyberTeal
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.contacts_auto_reply_switch),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Switch(
                        checked = autoReplyEnabled,
                        onCheckedChange = { autoReplyEnabled = it },
                        modifier = Modifier.testTag("contact_auto_reply_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = CyberTeal
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotBlank()) {
                        onSave(
                            ContactProfile(
                                id = initialContact?.id ?: UUID.randomUUID().toString(),
                                name = clean,
                                platform = platform,
                                relationship = relationship,
                                customStyleNote = customNote.trim(),
                                autoReplyEnabled = autoReplyEnabled,
                                createdAtMs = initialContact?.createdAtMs ?: System.currentTimeMillis()
                            )
                        )
                    }
                },
                enabled = name.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberTeal,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("save_contact_button")
            ) {
                Text(stringResource(R.string.contacts_save_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("cancel_contact_button")
            ) {
                Text(
                    text = stringResource(R.string.contacts_cancel_button),
                    color = TextSecondary
                )
            }
        }
    )
}
