package dev.pranav.applock.features.lockscreen.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.vibrate
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

// Exact Samsung One UI Frosted Circle Tokens matching screenshot
private val SamsungKeyFill = Color.White.copy(alpha = 0.20f)
private val SamsungKeyPressedFill = Color.White.copy(alpha = 0.36f)
private val SamsungPillFill = Color.White.copy(alpha = 0.22f)

private val SamsungT9Map = mapOf(
    "1" to "",
    "2" to "ABC",
    "3" to "DEF",
    "4" to "GHI",
    "5" to "JKL",
    "6" to "MNO",
    "7" to "PQRS",
    "8" to "TUV",
    "9" to "WXYZ",
    "0" to ""
)

class PasswordOverlayActivity : FragmentActivity() {
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var appLockRepository: AppLockRepository
    internal var lockedPackageNameFromIntent: String? = null
    internal var triggeringPackageNameFromIntent: String? = null

    private var isBiometricPromptShowingLocal = false
    private var appName: String = ""

    private val TAG = "PasswordOverlayActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lockedPackageNameFromIntent = intent.getStringExtra("locked_package")
        triggeringPackageNameFromIntent = intent.getStringExtra("triggering_package")
        if (lockedPackageNameFromIntent == null) {
            Log.e(TAG, "No locked_package name provided in intent. Finishing.")
            finishAffinity()
            return
        }

        enableEdgeToEdge()

        appLockRepository = applicationContext.appLockRepository()

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Log.d(TAG, "Back pressed ignored on AppLock overlay")
                }
            })

        setupWindow()
        loadAppNameAndSetupUI()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        setupBiometricPromptInternal()
    }

    override fun onPostResume() {
        super.onPostResume()
        setupBiometricPromptInternal()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed - orientation: ${newConfig.orientation}")
    }

    private fun setupWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        val layoutParams = window.attributes
        layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT

        if (appLockRepository.shouldUseMaxBrightness()) {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        window.attributes = layoutParams
    }

    private fun loadAppNameAndSetupUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appName = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(lockedPackageNameFromIntent!!, 0)
                ).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app name: ${e.message}")
                appName = getString(R.string.default_app_name)
            }
        }
        setupUI()
    }

    private fun setupUI() {
        val onPinAttemptCallback = { pin: String ->
            val isValid = appLockRepository.validatePassword(pin)
            if (isValid) {
                lockedPackageNameFromIntent?.let { pkgName ->
                    AppLockManager.unlockApp(pkgName)
                    finishAfterTransition()
                }
            }
            isValid
        }

        val onPatternAttemptCallback = { pattern: String ->
            val isValid = appLockRepository.validatePattern(pattern)
            if (isValid) {
                lockedPackageNameFromIntent?.let { pkgName ->
                    AppLockManager.unlockApp(pkgName)
                    finishAfterTransition()
                }
            }
            isValid
        }

        setContent {
            AppLockTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF1C2326),
                    contentColor = Color.White
                ) { innerPadding ->
                    val lockType = appLockRepository.getLockType()
                    when (lockType) {
                        PreferencesRepository.LOCK_TYPE_PATTERN -> {
                            PatternLockScreen(
                                modifier = Modifier.padding(innerPadding),
                                fromMainActivity = false,
                                lockedAppName = appName,
                                triggeringPackageName = triggeringPackageNameFromIntent,
                                onPatternAttempt = onPatternAttemptCallback
                            )
                        }

                        PreferencesRepository.LOCK_TYPE_PASSWORD -> {
                            AlphanumericPasswordOverlayScreen(
                                modifier = Modifier.padding(innerPadding),
                                showBiometricButton = false,
                                fromMainActivity = false,
                                onBiometricAuth = { triggerBiometricPrompt() },
                                onAuthSuccess = {},
                                lockedAppName = appName,
                                triggeringPackageName = triggeringPackageNameFromIntent,
                                onPasswordAttempt = onPinAttemptCallback,
                                showCloseButton = true,
                                onClose = { finish() }
                            )
                        }

                        else -> {
                            PinPasswordOverlayScreen(
                                modifier = Modifier.padding(innerPadding),
                                showBiometricButton = false,
                                fromMainActivity = false,
                                onBiometricAuth = { triggerBiometricPrompt() },
                                onAuthSuccess = {},
                                lockedAppName = appName,
                                triggeringPackageName = triggeringPackageNameFromIntent,
                                onPinAttempt = onPinAttemptCallback,
                                showCloseButton = true,
                                onClose = { finish() }
                            )

                            BackHandler { }
                        }
                    }
                }
            }
        }
    }

    private fun setupBiometricPromptInternal() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt =
            BiometricPrompt(this@PasswordOverlayActivity, executor, authenticationCallbackInternal)

        val appNameForPrompt = appName.ifEmpty { getString(R.string.this_app) }
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_app_title, appNameForPrompt))
            .setSubtitle(getString(R.string.confirm_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin_button))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            .setConfirmationRequired(false)
            .build()
    }

    private val authenticationCallbackInternal =
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isBiometricPromptShowingLocal = false
                AppLockManager.reportBiometricAuthFinished()
                Log.w(TAG, "Authentication error: $errString ($errorCode)")
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isBiometricPromptShowingLocal = false
                lockedPackageNameFromIntent?.let { pkgName ->
                    AppLockManager.temporarilyUnlockAppWithBiometrics(pkgName)
                }
                finishAfterTransition()
            }
        }

    override fun onResume() {
        super.onResume()
        AppLockManager.isLockScreenShown.set(true)
        lifecycleScope.launch {
            applyUserPreferences()
        }
    }

    private fun applyUserPreferences() {
        if (appLockRepository.shouldUseMaxBrightness()) {
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
            if (window.decorView.isAttachedToWindow) {
                windowManager.updateViewLayout(window.decorView, window.attributes)
            }
        }
    }

    fun triggerBiometricPrompt() {
        if (appLockRepository.isBiometricAuthEnabled()) {
            AppLockManager.reportBiometricAuthStarted()
            isBiometricPromptShowingLocal = true
            try {
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error calling biometricPrompt.authenticate: ${e.message}", e)
                isBiometricPromptShowingLocal = false
                AppLockManager.reportBiometricAuthFinished()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations && !isBiometricPromptShowingLocal) {
            Log.d(TAG, "Overlay paused; lock screen hidden but app remains locked")
            AppLockManager.isLockScreenShown.set(false)
        }
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        AppLockManager.isLockScreenShown.set(true)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            return
        }
        Log.d(TAG, "Overlay stopped; finishing lock overlay")
        AppLockManager.isLockScreenShown.set(false)
        if (!isFinishing && !isDestroyed) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLockManager.isLockScreenShown.set(false)
        AppLockManager.reportBiometricAuthFinished()
        Log.d(TAG, "PasswordOverlayActivity onDestroy for $lockedPackageNameFromIntent")
    }
}

@Composable
fun SamsungOneUiBlurredBackdrop() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Multi-tone blurred wallpaper ambient layer matching screenshot palette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF434843),
                            Color(0xFF132D33),
                            Color(0xFF4B251B),
                            Color(0xFF281914)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(64.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF7B8585).copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(540f, 1150f),
                        radius = 750f
                    )
                )
        )
        // One UI lock screen dim scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
        )
    }
}

@Composable
fun PinPasswordOverlayScreen(
    modifier: Modifier = Modifier,
    showBiometricButton: Boolean = false,
    fromMainActivity: Boolean = false,
    showCloseButton: Boolean = false,
    onClose: () -> Unit = {},
    onBiometricAuth: () -> Unit = {},
    onAuthSuccess: () -> Unit,
    lockedAppName: String? = null,
    triggeringPackageName: String? = null,
    onPinAttempt: ((pin: String) -> Boolean)? = null
) {
    val appLockRepository = LocalContext.current.appLockRepository()
    val windowInfo = LocalWindowInfo.current
    val configuration = LocalConfiguration.current

    val screenWidth = windowInfo.containerSize.width
    val screenHeight = windowInfo.containerSize.height
    val isLandscape = screenWidth > screenHeight
    val screenHeightDp = configuration.screenHeightDp.dp

    val passwordState = remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val minLength = 4

    Box(modifier = modifier.fillMaxSize()) {
        SamsungOneUiBlurredBackdrop()

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Enter PIN",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (showError)
                            stringResource(R.string.incorrect_pin_try_again)
                        else
                            "Your PIN contains at least 4 digits.",
                        color = if (showError) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.90f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    PasswordIndicators(passwordLength = passwordState.value.length)
                }

                KeypadSection(
                    passwordState = passwordState,
                    minLength = minLength,
                    showBiometricButton = false,
                    fromMainActivity = fromMainActivity,
                    onBiometricAuth = onBiometricAuth,
                    onAuthSuccess = onAuthSuccess,
                    onPinAttempt = onPinAttempt,
                    onPasswordChange = {
                        showError = false
                        if (appLockRepository.isAutoUnlockEnabled()) {
                            onPinAttempt?.invoke(passwordState.value)
                        }
                    },
                    onPinIncorrect = { showError = true }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Lock Icon positioned identically to One UI lock screen
                Spacer(modifier = Modifier.height(if (screenHeightDp < 650.dp) 18.dp else 30.dp))

                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Locked",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Title & Subtitle right above the keypad
                Text(
                    text = "Enter PIN",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (showError)
                        stringResource(R.string.incorrect_pin_try_again)
                    else
                        "Your PIN contains at least 4 digits.",
                    color = if (showError) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )

                // Dynamic PIN Dots row (appears cleanly when typing, reserves subtle height)
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (passwordState.value.isNotEmpty()) {
                        PasswordIndicators(passwordLength = passwordState.value.length)
                    }
                }

                // 3x4 Frosted Circle Keypad
                KeypadSection(
                    passwordState = passwordState,
                    minLength = minLength,
                    showBiometricButton = false,
                    fromMainActivity = fromMainActivity,
                    onBiometricAuth = onBiometricAuth,
                    onAuthSuccess = onAuthSuccess,
                    onPinAttempt = onPinAttempt,
                    onPasswordChange = {
                        showError = false
                        if (appLockRepository.isAutoUnlockEnabled()) {
                            onPinAttempt?.invoke(passwordState.value)
                        }
                    },
                    onPinIncorrect = { showError = true }
                )

                Spacer(modifier = Modifier.height(22.dp))

                // Frosted Pill Action Button (matches "Emergency call" pill shape & tint)
                if (showCloseButton) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SamsungPillFill)
                            .clickable { onClose() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (!lockedAppName.isNullOrEmpty()) "Cancel" else "Close",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(38.dp))
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom-right Samsung One UI back chevron
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 56.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (showCloseButton) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = "Back",
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordIndicators(
    passwordLength: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(passwordLength) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun KeypadSection(
    passwordState: MutableState<String>,
    minLength: Int,
    showBiometricButton: Boolean,
    fromMainActivity: Boolean = false,
    onBiometricAuth: () -> Unit,
    onAuthSuccess: () -> Unit,
    onPinAttempt: ((pin: String) -> Boolean)? = null,
    onPasswordChange: () -> Unit,
    onPinIncorrect: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val windowInfo = LocalWindowInfo.current

    val screenWidth = windowInfo.containerSize.width
    val screenHeight = windowInfo.containerSize.height
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    val isLandscape = screenWidth > screenHeight

    // Exact proportions from screenshot: ~76dp frosted circles with ~22dp horizontal & ~14dp vertical spacing
    val horizontalSpacing = remember(screenWidthDp, isLandscape) {
        if (isLandscape) 16.dp else 24.dp
    }
    val verticalSpacing = remember(screenHeightDp, isLandscape) {
        if (isLandscape) 10.dp else 14.dp
    }

    val buttonSize = remember(screenWidthDp, screenHeightDp, isLandscape) {
        if (isLandscape) {
            minOf((screenHeightDp * 0.72f - 30.dp) / 4f, 62.dp)
        } else {
            minOf((screenWidthDp - 116.dp) / 3f, 76.dp)
        }
    }

    val onDigitKeyClick = remember(passwordState, minLength, onPasswordChange) {
        { key: String ->
            addDigitToPassword(
                passwordState,
                key,
                onPasswordChange
            )
        }
    }

    val disableHaptics = context.appLockRepository().shouldDisableHaptics()

    val onSpecialKeyClick = remember(
        passwordState,
        minLength,
        fromMainActivity,
        onAuthSuccess,
        onPinAttempt,
        context,
        onPasswordChange,
        onPinIncorrect
    ) {
        { key: String ->
            handleKeypadSpecialButtonLogic(
                key = key,
                passwordState = passwordState,
                minLength = minLength,
                fromMainActivity = fromMainActivity,
                onAuthSuccess = onAuthSuccess,
                onPinAttempt = onPinAttempt,
                context = context,
                onPasswordChange = onPasswordChange,
                onPinIncorrect = onPinIncorrect
            )
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SamsungKeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("1", "2", "3"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            horizontalSpacing = horizontalSpacing
        )
        SamsungKeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("4", "5", "6"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            horizontalSpacing = horizontalSpacing
        )
        SamsungKeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("7", "8", "9"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            horizontalSpacing = horizontalSpacing
        )
        SamsungBottomRow(
            disableHaptics = disableHaptics,
            hasMinDigits = passwordState.value.length >= minLength,
            onBackspaceClick = { onSpecialKeyClick("backspace") },
            onZeroClick = { onDigitKeyClick("0") },
            onOkClick = { onSpecialKeyClick("proceed") },
            buttonSize = buttonSize,
            horizontalSpacing = horizontalSpacing
        )
    }
}

private fun addDigitToPassword(
    passwordState: MutableState<String>,
    digit: String,
    onPasswordChange: () -> Unit
) {
    passwordState.value += digit
    onPasswordChange()
}

private fun handleKeypadSpecialButtonLogic(
    key: String,
    passwordState: MutableState<String>,
    minLength: Int,
    fromMainActivity: Boolean,
    onAuthSuccess: () -> Unit,
    onPinAttempt: ((pin: String) -> Boolean)?,
    context: Context,
    onPasswordChange: () -> Unit,
    onPinIncorrect: () -> Unit
) {
    val appLockRepository = context.appLockRepository()

    when (key) {
        "0" -> addDigitToPassword(passwordState, key, onPasswordChange)
        "backspace" -> {
            if (passwordState.value.isNotEmpty()) {
                passwordState.value = passwordState.value.dropLast(1)
                onPasswordChange()
            }
        }

        "proceed" -> {
            if (passwordState.value.length < minLength) {
                if (!appLockRepository.shouldDisableHaptics()) {
                    vibrate(context, 100)
                }
                return
            }
            if (fromMainActivity) {
                if (appLockRepository.validatePassword(passwordState.value)) {
                    onAuthSuccess()
                } else {
                    passwordState.value = ""
                    if (!appLockRepository.shouldDisableHaptics()) {
                        vibrate(context, 100)
                    }
                    onPinIncorrect()
                }
            } else {
                onPinAttempt?.let { attempt ->
                    val pinWasCorrectAndProcessed = attempt(passwordState.value)
                    if (!pinWasCorrectAndProcessed) {
                        passwordState.value = ""
                        if (!appLockRepository.shouldDisableHaptics()) {
                            vibrate(context, 100)
                        }
                        onPinIncorrect()
                    }
                } ?: run {
                    Log.e(
                        "PasswordOverlayScreen",
                        "onPinAttempt callback is null for app unlock path."
                    )
                    passwordState.value = ""
                }
            }
        }
    }
}

@Composable
private fun SamsungKeypadRow(
    disableHaptics: Boolean,
    keys: List<String>,
    onKeyClick: (String) -> Unit,
    buttonSize: Dp,
    horizontalSpacing: Dp
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEach { digit ->
            SamsungFrostedCircleKey(
                digit = digit,
                subLetters = SamsungT9Map[digit].orEmpty(),
                disableHaptics = disableHaptics,
                buttonSize = buttonSize,
                onClick = { onKeyClick(digit) }
            )
        }
    }
}

@Composable
private fun SamsungBottomRow(
    disableHaptics: Boolean,
    hasMinDigits: Boolean,
    onBackspaceClick: () -> Unit,
    onZeroClick: () -> Unit,
    onOkClick: () -> Unit,
    buttonSize: Dp,
    horizontalSpacing: Dp
) {
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Borderless solid white Backspace tag icon (exact screenshot match)
        val backInteraction = remember { MutableInteractionSource() }
        val backPressed by backInteraction.collectIsPressedAsState()
        val backScale by animateFloatAsState(
            targetValue = if (backPressed) 0.88f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "backScale"
        )

        Box(
            modifier = Modifier
                .size(buttonSize)
                .scale(backScale)
                .clip(CircleShape)
                .clickable(
                    interactionSource = backInteraction,
                    indication = null,
                    onClick = {
                        if (!disableHaptics) vibrate(context, 35)
                        onBackspaceClick()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center: "0" inside frosted circle (no + sub-text, exact screenshot match)
        SamsungFrostedCircleKey(
            digit = "0",
            subLetters = "",
            disableHaptics = disableHaptics,
            buttonSize = buttonSize,
            onClick = onZeroClick
        )

        // Right: Borderless "OK" text (dimmed until 4+ digits, bright white when ready)
        val okInteraction = remember { MutableInteractionSource() }
        val okPressed by okInteraction.collectIsPressedAsState()
        val okScale by animateFloatAsState(
            targetValue = if (okPressed) 0.90f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "okScale"
        )

        Box(
            modifier = Modifier
                .size(buttonSize)
                .scale(okScale)
                .clip(CircleShape)
                .clickable(
                    interactionSource = okInteraction,
                    indication = null,
                    onClick = {
                        if (!disableHaptics) vibrate(context, 35)
                        onOkClick()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "OK",
                color = if (hasMinDigits) Color.White else Color.White.copy(alpha = 0.50f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun SamsungFrostedCircleKey(
    digit: String,
    subLetters: String,
    disableHaptics: Boolean,
    buttonSize: Dp,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) SamsungKeyPressedFill else SamsungKeyFill,
        animationSpec = tween(durationMillis = 100),
        label = "samsungKeyFill"
    )

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "samsungKeyScale"
    )

    Box(
        modifier = Modifier
            .size(buttonSize)
            .scale(scale)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (!disableHaptics) vibrate(context, 35)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (subLetters.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.offset(y = (-1).dp)
            ) {
                Text(
                    text = digit,
                    color = Color.White,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 33.sp
                )
                Text(
                    text = subLetters,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.1.sp,
                    lineHeight = 11.sp
                )
            }
        } else {
            // Key "1" and Key "0" have no sub-letters in Samsung One UI, centered vertically
            Text(
                text = digit,
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
