package com.roman.zemzeme.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roman.zemzeme.R
import kotlinx.coroutines.delay

/**
 * Permission explanation screen shown during onboarding.
 * Shows a checklist of all permissions with:
 * - Status circles (green check when granted, empty when not)
 * - "Required" / "Recommended" labels
 * - (i) info buttons opening benefit dialogs
 * - Sequential per-category permission granting with live circle updates
 */
@Composable
fun PermissionExplanationScreen(
    modifier: Modifier,
    permissionManager: PermissionManager,
    onRequestPermissionForCategory: (PermissionCategory) -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    var categories by remember { mutableStateOf(permissionManager.getCategorizedPermissions()) }
    var isGranting by remember { mutableStateOf(false) }
    var requestedCategories by remember { mutableStateOf(setOf<PermissionType>()) }
    var showInfoDialogFor by remember { mutableStateOf<PermissionCategory?>(null) }
    var resumeCount by remember { mutableStateOf(0) }
    var hasAttemptedGrant by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                categories = permissionManager.getCategorizedPermissions()
                resumeCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // After each system dialog returns, auto-request the next ungranted category
    LaunchedEffect(resumeCount) {
        if (isGranting && resumeCount > 0) {
            delay(500) // Brief pause to show the updated circle
            val next = findNextUngranted(categories, requestedCategories)
            if (next != null) {
                requestedCategories = requestedCategories + next.type
                onRequestPermissionForCategory(next)
            } else {
                isGranting = false
            }
        }
    }

    val allRequiredGranted = categories.filter { it.isRequired }.all { it.isGranted }

    // Info dialog
    showInfoDialogFor?.let { category ->
        AlertDialog(
            onDismissRequest = { showInfoDialogFor = null },
            title = {
                Text(
                    text = stringResource(category.type.nameRes),
                    fontFamily = NunitoFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = category.benefitDescription,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialogFor = null }) {
                    Text(stringResource(R.string.perm_info_dialog_ok))
                }
            }
        )
    }

    Box(modifier = modifier) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 88.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Header Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = NunitoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        ),
                        color = colorScheme.onBackground
                    )
                }

                Text(
                    text = stringResource(R.string.about_tagline),
                    fontSize = 12.sp,
                    fontFamily = NunitoFontFamily,
                    color = colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            // Privacy assurance section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = stringResource(R.string.cd_privacy_protected),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.privacy_protected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.privacy_bullets),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // Section header
            Text(
                text = stringResource(R.string.permissions_header),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            // Permission categories as checklist rows
            categories.forEach { category ->
                PermissionChecklistRow(
                    category = category,
                    colorScheme = colorScheme,
                    onInfoClick = { showInfoDialogFor = category },
                    onRowClick = if (!category.isGranted) {
                        {
                            if (category.type in requestedCategories) {
                                // Already requested in this session — dialog won't show again
                                onOpenSettings()
                            } else {
                                onRequestPermissionForCategory(category)
                            }
                        }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Fixed button at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Button(
                onClick = {
                    if (allRequiredGranted) {
                        onContinue()
                    } else if (hasAttemptedGrant) {
                        // Sequential flow already ran once — dialogs may not show again.
                        // Send user to app settings where they can grant manually.
                        onOpenSettings()
                    } else {
                        hasAttemptedGrant = true
                        isGranting = true
                        requestedCategories = emptySet()
                        val first = findNextUngranted(categories, emptySet())
                        if (first != null) {
                            requestedCategories = setOf(first.type)
                            onRequestPermissionForCategory(first)
                        }
                    }
                },
                enabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                )
            ) {
                Text(
                    text = if (allRequiredGranted)
                        stringResource(R.string.continue_button)
                    else
                        stringResource(R.string.grant_permissions),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = NunitoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionChecklistRow(
    category: PermissionCategory,
    colorScheme: ColorScheme,
    onInfoClick: () -> Unit,
    onRowClick: (() -> Unit)? = null
) {
    val grantedColor = Color(0xFF4CAF50)

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onRowClick != null) Modifier.clickable { onRowClick() }
                else Modifier
            )
            .padding(vertical = 8.dp)
    ) {
        // Permission icon
        Icon(
            imageVector = getPermissionIcon(category.type),
            contentDescription = stringResource(category.type.nameRes),
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))

        // Name, description, and required/recommended label
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = stringResource(category.type.nameRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onBackground.copy(alpha = 0.8f)
            )
            if (!category.isGranted) {
                Spacer(modifier = Modifier.height(4.dp))
                if (category.isRequired) {
                    Text(
                        text = stringResource(R.string.perm_required_label),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = stringResource(R.string.perm_recommended_label),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // (i) info button
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Status circle
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (category.isGranted) grantedColor
                    else colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (category.isGranted) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Find the next ungranted permission category that hasn't been requested yet.
 * Skips background location if foreground location isn't granted first.
 */
private fun findNextUngranted(
    categories: List<PermissionCategory>,
    alreadyRequested: Set<PermissionType>
): PermissionCategory? {
    return categories.firstOrNull { category ->
        !category.isGranted &&
        category.type !in alreadyRequested &&
        // Background location can only be requested after foreground location is granted
        (category.type != PermissionType.BACKGROUND_LOCATION ||
            categories.any { it.type == PermissionType.PRECISE_LOCATION && it.isGranted })
    }
}

private fun getPermissionIcon(permissionType: PermissionType): ImageVector {
    return when (permissionType) {
        PermissionType.NEARBY_DEVICES -> Icons.Filled.Bluetooth
        PermissionType.PRECISE_LOCATION -> Icons.Filled.LocationOn
        PermissionType.BACKGROUND_LOCATION -> Icons.Filled.LocationOn
        PermissionType.MICROPHONE -> Icons.Filled.Mic
        PermissionType.NOTIFICATIONS -> Icons.Filled.Notifications
        PermissionType.BATTERY_OPTIMIZATION -> Icons.Filled.Power
        PermissionType.OTHER -> Icons.Filled.Settings
    }
}
