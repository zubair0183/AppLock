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
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.pranav.applock.ui.icons.Backspace
import dev.pranav.applock.ui.theme.AppLockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

private val OneUiBgColor = Color(0xFF0E1014)
private val OneUiKeyFill = Color.White.copy(alpha = 0.11f)
private val OneUiKeyPressedFill = Color.White.copy(alpha = 0.25f)
private val OneUiKeyBorder = Color.White.copy(alpha = 0.17f)

private val T9SubLabels = mapOf(
    "1" to "",
    "2" to "ABC",
    "3" to "DEF",
    "4" to "GHI",
    "5" to "JKL",
    "6" to "MNO",
    "7" to "PQRS",
    "8" to "TUV",
    "9" to "WXYZ",
    "0" to "+"
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
                    containerColor = OneUiBgColor,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class)
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

    val screenWidth = windowInfo.containerSize.width
    val screenHeight = windowInfo.containerSize.height
    val isLandscape = screenWidth > screenHeight

    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp

    Surface(
        modifier = modifier.fillMaxSize(),
        color = OneUiBgColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (showCloseButton) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 8.dp, top = 8.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            val passwordState = remember { mutableStateOf("") }
            var showError by remember { mutableStateOf(false) }
            val minLength = 4

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (!fromMainActivity && !lockedAppName.isNullOrEmpty())
                                "Enter PIN to open $lockedAppName"
                            else
                                "Enter PIN",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        PasswordIndicators(
                            passwordLength = passwordState.value.length,
                            isError = showError
                        )

                        if (showError) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.incorrect_pin_try_again),
                                color = Color(0xFFFF6B6B),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
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
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = if (fromMainActivity) 24.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val topSpacerHeight = if (screenHeightDp < 600.dp) 24.dp else 68.dp
                    Spacer(modifier = Modifier.height(topSpacerHeight))

                    Text(
                        text = if (!fromMainActivity && !lockedAppName.isNullOrEmpty())
                            "Enter PIN to open $lockedAppName"
                        else
                            "Enter PIN",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PasswordIndicators(
                        passwordLength = passwordState.value.length,
                        isError = showError
                    )

                    if (showError) {
                        Text(
                            text = stringResource(R.string.incorrect_pin_try_again),
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

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
            }
        }
    }
}

@Composable
fun PasswordIndicators(
    passwordLength: Int,
    isError: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val lazyListState = rememberLazyListState()

    val dotCount = maxOf(4, passwordLength)
    val dotColor = if (isError) Color(0xFFFF6B6B) else Color.White

    LaunchedEffect(passwordLength) {
        if (passwordLength > 0) {
            lazyListState.animateScrollToItem(
                index = passwordLength - 1,
                scrollOffset = 0
            )
        }
    }

    Box(
        modifier = Modifier
            .width(screenWidthDp * 0.8f)
            .height(28.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyRow(
            state = lazyListState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(dotCount) { index ->
                val isFilled = index < passwordLength
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isFilled) dotColor else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (isFilled) dotColor else Color.White.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                )
            }
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

    val buttonSpacing = remember(screenWidthDp, screenHeightDp, isLandscape) {
        if (isLandscape) 12.dp else 18.dp
    }

    val buttonSize = remember(screenWidthDp, screenHeightDp, isLandscape) {
        if (isLandscape) {
            minOf((screenHeightDp * 0.72f - 36.dp) / 4f, 64.dp)
        } else {
            minOf((screenWidthDp - 96.dp) / 3f, 76.dp)
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
        verticalArrangement = Arrangement.spacedBy(buttonSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        KeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("1", "2", "3"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            buttonSpacing = buttonSpacing
        )
        KeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("4", "5", "6"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            buttonSpacing = buttonSpacing
        )
        KeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("7", "8", "9"),
            onKeyClick = onDigitKeyClick,
            buttonSize = buttonSize,
            buttonSpacing = buttonSpacing
        )
        KeypadRow(
            disableHaptics = disableHaptics,
            keys = listOf("backspace", "0", "proceed"),
            icons = listOf(Backspace, null, Icons.AutoMirrored.Rounded.KeyboardArrowRight),
            onKeyClick = onSpecialKeyClick,
            buttonSize = buttonSize,
            buttonSpacing = buttonSpacing
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
                passwordState.value = ""
                return
            }
            if (passwordState.value.length >= minLength) {
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
}

@Composable
fun KeypadRow(
    disableHaptics: Boolean = false,
    keys: List<String>,
    icons: List<ImageVector?> = emptyList(),
    onKeyClick: (String) -> Unit,
    buttonSize: Dp,
    buttonSpacing: Dp
) {
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEachIndexed { index, key ->
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val isActionIcon = icons.isNotEmpty() && index < icons.size && icons[index] != null

            val targetColor = when {
                isPressed -> OneUiKeyPressedFill
                isActionIcon -> Color.Transparent
                else -> OneUiKeyFill
            }

            val animatedContainerColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(durationMillis = 120),
                label = "OneUiButtonColor"
            )

            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.91f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "OneUiButtonScale"
            )

            Surface(
                modifier = Modifier
                    .size(buttonSize)
                    .scale(scale)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (!disableHaptics) vibrate(context, 40)
                            onKeyClick(key)
                        }
                    ),
                shape = CircleShape,
                color = animatedContainerColor,
                border = if (isActionIcon) null else BorderStroke(1.dp, OneUiKeyBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isActionIcon) {
                        Icon(
                            imageVector = icons[index]!!,
                            contentDescription = key,
                            modifier = Modifier.size(26.dp),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    } else {
                        val subText = T9SubLabels[key].orEmpty()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = key,
                                color = Color.White,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 32.sp
                            )
                            if (subText.isNotEmpty()) {
                                Text(
                                    text = subText,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.2.sp
                                )
                            } else {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
