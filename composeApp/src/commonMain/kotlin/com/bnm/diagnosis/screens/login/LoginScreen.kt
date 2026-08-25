package com.bnm.diagnosis.screens.login

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.diagnosis.auth.AdminUser
import com.bnm.diagnosis.auth.AuthRepository
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.currentFy
import com.bnm.diagnosis.resources.Res
import com.bnm.diagnosis.resources.bnm_logo_mark
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private enum class Provider { GOOGLE, APPLE, EMAIL, PAIR }

@Composable
fun LoginScreen(
    authRepository: AuthRepository,
    onLoggedIn: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val repo = LocalBillingRepository.current
    var loading by remember { mutableStateOf<Provider?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showOwnerSignIn by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    var resettingPassword by remember { mutableStateOf(false) }
    val deviceId = remember { "dev-" + Random.nextLong().toString(16) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF14171C) else Color.White
    val cardBorder = if (isDark) Color(0xFF262A31) else Color(0xFFEAECF1)
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiaryColor = subtleColor.copy(alpha = 0.7f)
    val backdrop = Brush.verticalGradient(
        if (isDark) listOf(Color(0xFF0A0C0F), Color(0xFF0D1117))
        else listOf(Color(0xFFF6F8FC), Color(0xFFE9F0FA))
    )
    val glow = if (isDark) Color(0xFF2563EB).copy(alpha = 0.20f) else Color(0xFF2D7FF0).copy(alpha = 0.14f)

    LaunchedEffect(Unit) {
        if (authRepository.isLoggedIn.value) onLoggedIn()
    }

    fun run(provider: Provider, block: suspend () -> Result<AdminUser>) {
        if (loading != null) return
        loading = provider
        errorMessage = null
        scope.launch {
            val result = block()
            loading = null
            result.onSuccess { onLoggedIn() }
                .onFailure { errorMessage = it.message ?: "Sign-in failed" }
        }
    }

    fun pairDevice() {
        if (loading != null) return
        val c = code.trim()
        if (c.length < 4) {
            errorMessage = "Enter the code from your owner's Billing panel"
            return
        }
        loading = Provider.PAIR
        errorMessage = null
        scope.launch {
            val res = authRepository.pairWithCode(code = c, token = null, deviceId = deviceId, deviceName = "BNM Diagnosis counter")
            res.onSuccess { pr ->
                // Pre-seed the device's invoice series from the pairing so the
                // operator can bill immediately — no manual series registration.
                runCatching { repo.registerSeriesLocal(pr.businessId, pr.seriesCode, currentFy(), pr.prefix, pr.numberFormat, pr.highWater) }
                loading = null
                // The isLoggedIn flip rebuilds the NavHost straight to Main (business
                // + series are already set) — no business-picker, no onLoggedIn().
            }.onFailure { loading = null; errorMessage = it.message ?: "Pairing failed" }
        }
    }

    fun forgotPassword() {
        if (loading != null || resettingPassword) return
        errorMessage = null
        resetMessage = null
        if (email.isBlank()) {
            errorMessage = "Enter your email to reset your password"
            return
        }
        resettingPassword = true
        scope.launch {
            val result = authRepository.sendPasswordReset(email)
            resettingPassword = false
            result
                .onSuccess {
                    resetMessage = "If an account exists for ${email.trim()}, a reset link is on its way. Check your inbox."
                }
                .onFailure { errorMessage = it.message ?: "Couldn't send reset link" }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(backdrop),
        contentAlignment = Alignment.Center
    ) {
        // Soft brand glow behind the card
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.radialGradient(colors = listOf(glow, Color.Transparent), radius = 1100f)
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep the gradient/glow edge-to-edge but keep content out from
                // under the status bar / home indicator / notch (Android 15+, iOS).
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                shadowElevation = if (isDark) 0.dp else 18.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(Res.drawable.bnm_logo_mark),
                        contentDescription = "BNM",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(150.dp).height(54.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Set up this counter",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Scan the QR or enter the 6-digit code from your owner's Billing panel",
                        fontSize = 15.sp,
                        color = subtleColor,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(28.dp))

                    if (errorMessage != null) {
                        ErrorBanner(errorMessage!!)
                        Spacer(Modifier.height(16.dp))
                    }
                    if (resetMessage != null) {
                        InfoBanner(resetMessage!!)
                        Spacer(Modifier.height(16.dp))
                    }

                    // ── Pair this counter (primary) ──────────────────────────
                    AuthTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                        label = "6-digit code",
                        leadingIcon = Icons.Outlined.Lock,
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        enabled = loading == null,
                        onImeAction = { pairDevice() },
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        label = "Pair device",
                        loading = loading == Provider.PAIR,
                        enabled = loading == null && code.trim().length >= 4,
                        onClick = { pairDevice() },
                    )

                    Spacer(Modifier.height(20.dp))
                    OrDivider(lineColor = cardBorder, textColor = tertiaryColor)
                    Spacer(Modifier.height(16.dp))

                    if (!showOwnerSignIn) {
                        Text(
                            text = "Sign in as owner",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2D7FF0),
                            modifier = Modifier.clickable(enabled = loading == null) { showOwnerSignIn = true },
                        )
                    }

                    if (showOwnerSignIn) {
                    // ── Email + password ─────────────────────────────────────
                    val submitEmail = {
                        if (email.isNotBlank() && password.isNotBlank()) {
                            run(Provider.EMAIL) {
                                authRepository.signInWithEmailPassword(email, password)
                            }
                        }
                    }
                    AuthTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = "Email",
                        leadingIcon = Icons.Outlined.MailOutline,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        enabled = loading == null,
                        onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                    Spacer(Modifier.height(12.dp))
                    AuthTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        leadingIcon = Icons.Outlined.Lock,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        enabled = loading == null,
                        isPassword = true,
                        passwordVisible = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        onImeAction = { submitEmail() },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = if (resettingPassword) "Sending…" else "Forgot password?",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2D7FF0),
                            modifier = Modifier.clickable(
                                enabled = loading == null && !resettingPassword,
                            ) { forgotPassword() },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        label = "Sign in",
                        loading = loading == Provider.EMAIL,
                        enabled = loading == null && email.isNotBlank() && password.isNotBlank(),
                        onClick = submitEmail,
                    )
                    } // end owner sign-in

                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = tertiaryColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Secured with end-to-end encryption",
                            fontSize = 12.sp,
                            color = tertiaryColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "By continuing, you agree to our Terms & Privacy Policy",
                fontSize = 12.sp,
                color = tertiaryColor,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "BNM for Business · v1.4.0",
                fontSize = 11.sp,
                color = tertiaryColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun InfoBanner(message: String) {
    val brand = Color(0xFF2D7FF0)
    Surface(
        color = brand.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                tint = brand,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SocialButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    loading: Boolean,
    enabled: Boolean,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "press")

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leading(contentColor)
                    Spacer(Modifier.width(12.dp))
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    enabled: Boolean,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    onImeAction: () -> Unit = {},
) {
    val brand = Color(0xFF2D7FF0)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = if (isPassword && onTogglePassword != null) {
            {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = brand,
            focusedLabelColor = brand,
            cursorColor = brand,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val brand = Color(0xFF2D7FF0)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "press")
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) brand else brand.copy(alpha = 0.5f),
        contentColor = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

@Composable
private fun OrDivider(lineColor: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
        Text("  or  ", fontSize = 12.sp, color = textColor)
        HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
    }
}

// ── Brand logos (drawn from official vector paths) ───────────────────────────

private const val APPLE_PATH =
    "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.464 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"

private const val G_BLUE =
    "M47.532 24.552c0-1.566-.142-3.072-.405-4.518H24.48v8.547h12.93c-.557 2.946-2.246 5.443-4.788 7.114v5.92h7.737c4.527-4.168 7.173-10.31 7.173-17.063z"
private const val G_GREEN =
    "M24.48 48c6.48 0 11.916-2.148 15.888-5.815l-7.737-5.92c-2.146 1.44-4.892 2.29-8.151 2.29-6.27 0-11.576-4.234-13.47-9.926H2.964v6.114C6.914 42.62 14.97 48 24.48 48z"
private const val G_YELLOW =
    "M11.01 28.629c-.48-1.44-.756-2.976-.756-4.56s.276-3.12.756-4.56v-6.114H2.964A23.94 23.94 0 0 0 .48 24.069c0 3.874.93 7.536 2.484 10.674l8.046-6.114z"
private const val G_RED =
    "M24.48 9.584c3.537 0 6.71 1.218 9.207 3.607l6.84-6.84C36.39 2.39 30.954 0 24.48 0 14.97 0 6.914 5.38 2.964 13.396l8.046 6.114c1.894-5.692 7.2-9.926 13.47-9.926z"

@Composable
private fun AppleLogo(tint: Color, modifier: Modifier = Modifier) {
    val path = remember { PathParser().parsePathString(APPLE_PATH).toPath() }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, tint) }
    }
}

@Composable
private fun GoogleLogo(modifier: Modifier = Modifier) {
    val blue = remember { PathParser().parsePathString(G_BLUE).toPath() }
    val green = remember { PathParser().parsePathString(G_GREEN).toPath() }
    val yellow = remember { PathParser().parsePathString(G_YELLOW).toPath() }
    val red = remember { PathParser().parsePathString(G_RED).toPath() }
    Canvas(modifier) {
        val s = size.minDimension / 48f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(blue, Color(0xFF4285F4))
            drawPath(green, Color(0xFF34A853))
            drawPath(yellow, Color(0xFFFBBC05))
            drawPath(red, Color(0xFFEA4335))
        }
    }
}
