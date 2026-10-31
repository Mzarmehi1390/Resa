package com.roman.zemzeme.onboarding

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.roman.zemzeme.ui.theme.NunitoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.roman.zemzeme.R
import com.roman.zemzeme.security.AppLockPreferenceManager

private enum class SetupSubStep { INTRO, SET_PIN, CONFIRM_PIN }

@Composable
fun AppLockSetupScreen(
    modifier: Modifier = Modifier,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val activity = LocalContext.current as? FragmentActivity
    val colorScheme = MaterialTheme.colorScheme

    val biometricManager = remember { BiometricManager.from(context) }
    val canUseBiometric = remember {
        biometricManager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    var subStep by remember { mutableStateOf(SetupSubStep.INTRO) }
    var enteredPin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Pre-fetch strings for use in non-Composable biometric callbacks
    val bioTitle = stringResource(R.string.app_lock_biometric_title)
    val bioSubtitle = stringResource(R.string.app_lock_biometric_subtitle)
    val setPinLabel = stringResource(R.string.app_lock_set_pin)
    val errMismatch = stringResource(R.string.app_lock_pin_mismatch)

    fun launchBiometric() {
        val act = activity ?: run { subStep = SetupSubStep.SET_PIN; return }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                subStep = SetupSubStep.SET_PIN
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Includes "Set up PIN" negative button — proceed to PIN setup regardless
                subStep = SetupSubStep.SET_PIN
            }
            override fun onAuthenticationFailed() {}
        }
        val prompt = BiometricPrompt(act, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(bioTitle)
            .setSubtitle(bioSubtitle)
            .setNegativeButtonText(setPinLabel)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        when (subStep) {
            SetupSubStep.INTRO -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Scrollable content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Header
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Text(
                                text = stringResource(R.string.app_lock_protect_title),
                                fontSize = 14.sp,
                                fontFamily = NunitoFontFamily,
                                color = colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }

                        // Lock icon
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.CenterHorizontally)
                        )

                        // Biometric card (only if available)
                        if (canUseBiometric) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Filled.Fingerprint,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = stringResource(R.string.app_lock_biometric_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = colorScheme.onBackground
                                        )
                                        Text(
                                            text = stringResource(R.string.app_lock_biometric_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onBackground.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }

                        // PIN card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.app_lock_set_pin),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = colorScheme.onBackground
                                    )
                                    Text(
                                        text = stringResource(R.string.app_lock_protect_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Fixed bottom buttons
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (canUseBiometric && activity != null) {
                                    launchBiometric()
                                } else {
                                    subStep = SetupSubStep.SET_PIN
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.app_lock_enable),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = NunitoFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        TextButton(
                            onClick = onComplete,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.app_lock_not_now),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = NunitoFontFamily
                                ),
                                color = colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            SetupSubStep.SET_PIN, SetupSubStep.CONFIRM_PIN -> {
                val isConfirm = subStep == SetupSubStep.CONFIRM_PIN
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        Text(
                            text = if (isConfirm)
                                stringResource(R.string.app_lock_confirm_pin)
                            else
                                stringResource(R.string.app_lock_set_pin),
                            fontSize = 14.sp,
                            fontFamily = NunitoFontFamily,
                            color = colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // 6 PIN dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        repeat(6) { i ->
                            val filled = i < enteredPin.length
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .background(
                                        if (filled) colorScheme.primary else Color.Transparent,
                                        CircleShape
                                    )
                                    .border(
                                        1.5.dp,
                                        if (filled) colorScheme.primary
                                        else colorScheme.onBackground.copy(alpha = 0.35f),
                                        CircleShape
                                    )
                            )
                        }
                    }

                    // Error text (reserved height so dots don't jump)
                    Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                        if (pinError != null) {
                            Text(
                                text = pinError!!,
                                color = Color(0xFFFF3B30),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Numpad
                    SetupNumpad(
                        onDigit = { digit ->
                            if (enteredPin.length < 6) {
                                enteredPin += digit
                                pinError = null
                                if (enteredPin.length == 6) {
                                    if (isConfirm) {
                                        if (enteredPin == firstPin) {
                                            AppLockPreferenceManager.savePin(enteredPin)
                                            AppLockPreferenceManager.setEnabled(true)
                                            onComplete()
                                        } else {
                                            pinError = errMismatch
                                            enteredPin = ""
                                        }
                                    } else {
                                        firstPin = enteredPin
                                        enteredPin = ""
                                        subStep = SetupSubStep.CONFIRM_PIN
                                    }
                                }
                            }
                        },
                        onBackspace = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                                pinError = null
                            }
                        },
                        colorScheme = colorScheme
                    )

                    TextButton(
                        onClick = {
                            enteredPin = ""
                            pinError = null
                            subStep = if (isConfirm) SetupSubStep.SET_PIN else SetupSubStep.INTRO
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = colorScheme.onBackground.copy(alpha = 0.5f),
                            fontFamily = NunitoFontFamily
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupNumpad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    colorScheme: ColorScheme
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .then(
                                if (key.isNotEmpty())
                                    Modifier
                                        .background(colorScheme.surface, RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (key == "⌫") onBackspace() else onDigit(key)
                                        }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (key.isNotEmpty()) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (key == "⌫")
                                    colorScheme.onSurface.copy(alpha = 0.6f)
                                else
                                    colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
