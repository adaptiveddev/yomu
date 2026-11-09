package app.yomu.feature.settings

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.common.Files
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.Share
import app.yomu.core.datastore.AppSettings
import app.yomu.core.datastore.AppSettings.Companion.defaultSelectionActions
import app.yomu.core.ui.components.navBarBottomPadding
import app.yomu.core.ui.components.ConfirmDialog
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.PreferenceHeader
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.CustomAction
import app.yomu.domain.model.SelectionAction
import app.yomu.feature.annotations.icon
import app.yomu.feature.annotations.label
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import app.yomu.core.ui.R as CoreR

// region Small controls

/** Slider that keeps its value locally while dragging and commits it when the drag ends. */
@Composable
internal fun SliderSettingRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    label: (Float) -> String,
    icon: ImageVector? = null,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(label(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = local, onValueChange = { local = it }, valueRange = range, steps = steps, onValueChangeFinished = { onCommit(local) })
        }
    }
}

@Composable
internal fun SecretInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (visible) R.string.set_secret_hide else R.string.set_secret_show)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.set_secret_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(CoreR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(hour, minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(CoreR.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

// endregion

// region Selection actions

@Composable
internal fun SelectionSection(vm: SettingsViewModel) {
    val s = vm.settings
    val actions by s.selectionActions.state()
    val custom by s.customActions.state()
    var editCustom by remember { mutableStateOf<CustomAction?>(null) }
    var deleteCustom by remember { mutableStateOf<CustomAction?>(null) }
    fun move(i: Int, d: Int) = vm.update(s.selectionActions) { list ->
        val j = i + d
        if (j !in list.indices) list else list.toMutableList().also { it.add(j, it.removeAt(i)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "hint") {
            Text(stringResource(R.string.set_selection_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l))
        }
        itemsIndexed(actions, key = { _, a -> a.action.name }) { i, a ->
            ListItem(
                headlineContent = { Text(stringResource(a.action.label())) },
                leadingContent = { Icon(a.action.icon(), null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.set_move_up)) }
                        IconButton(onClick = { move(i, 1) }, enabled = i < actions.lastIndex) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.set_move_down)) }
                        Switch(checked = a.enabled, onCheckedChange = { on -> vm.update(s.selectionActions) { l -> l.map { if (it.action == a.action) it.copy(enabled = on) else it } } })
                    }
                },
            )
        }
        item(key = "reset") { PreferenceItem(stringResource(R.string.set_selection_reset), onClick = { vm.set(s.selectionActions, defaultSelectionActions()) }, icon = settingIcon(R.string.set_selection_reset)) }
        item(key = "custom-h") { PreferenceHeader(stringResource(R.string.set_custom_actions)) }
        item(key = "custom-hint") {
            Text(stringResource(R.string.set_custom_actions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Dimens.l))
        }
        items(custom, key = { it.id }) { c ->
            ListItem(
                headlineContent = { Text(c.label) },
                supportingContent = { Text(c.urlTemplate.ifBlank { c.packageName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editCustom = c }) { Icon(Icons.Filled.Edit, stringResource(CoreR.string.edit)) }
                        IconButton(onClick = { deleteCustom = c }) { Icon(Icons.Filled.Delete, stringResource(CoreR.string.delete)) }
                    }
                },
            )
        }
        item(key = "custom-add") {
            PreferenceItem(stringResource(R.string.set_custom_add), icon = Icons.Filled.Add, onClick = { editCustom = CustomAction(id = "", label = "") })
        }
    }
    editCustom?.let { c ->
        CustomActionDialog(c, onDismiss = { editCustom = null }) { edited ->
            vm.update(s.customActions) { list ->
                if (c.id.isEmpty()) list + edited.copy(id = UUID.randomUUID().toString()) else list.map { if (it.id == c.id) edited else it }
            }
            vm.update(s.selectionActions) { l -> l.map { if (it.action == SelectionAction.CUSTOM) it.copy(enabled = true) else it } }
            editCustom = null
        }
    }
    deleteCustom?.let { c ->
        ConfirmDialog(stringResource(CoreR.string.delete), c.label, stringResource(CoreR.string.delete), onConfirm = { vm.update(s.customActions) { l -> l.filter { it.id != c.id } } }, onDismiss = { deleteCustom = null })
    }
}

@Composable
private fun CustomActionDialog(initial: CustomAction, onDismiss: () -> Unit, onSave: (CustomAction) -> Unit) {
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var url by rememberSaveable { mutableStateOf(initial.urlTemplate.ifBlank { if (initial.packageName.isBlank()) "https://" else "" }) }
    var pkg by rememberSaveable { mutableStateOf(initial.packageName) }
    val valid = label.isNotBlank() && ((url.startsWith("http") && "{text}" in url) || pkg.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_custom_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.set_custom_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.set_custom_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pkg, { pkg = it }, label = { Text(stringResource(R.string.set_custom_package)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.set_custom_example), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(label = label.trim(), urlTemplate = url.trim().takeIf { it.startsWith("http") && "{text}" in it }.orEmpty(), packageName = pkg.trim()))
            }) { Text(stringResource(CoreR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

// endregion

// region Fonts

@Composable
internal fun FontsSection(vm: SettingsViewModel) {
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<File?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFont) }
    LaunchedEffect(Unit) { vm.loadFonts() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "hint") {
            Text(stringResource(R.string.set_fonts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l))
        }
        item(key = "add") { PreferenceItem(stringResource(R.string.set_font_import), stringResource(R.string.set_font_import_sum), Icons.Filled.Add, onClick = { pick.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) }
        if (fonts.isEmpty()) item(key = "empty") { EmptyState(title = stringResource(R.string.set_fonts_empty), icon = Icons.Outlined.FontDownload, modifier = Modifier.fillMaxWidth()) }
        items(fonts, key = { it.name }) { f ->
            val family = remember(f) { runCatching { FontFamily(Typeface.createFromFile(f)) }.getOrDefault(FontFamily.Default) }
            ListItem(
                headlineContent = { Text(f.nameWithoutExtension) },
                supportingContent = { Text(stringResource(R.string.set_font_preview), fontFamily = family, fontSize = 18.sp) },
                trailingContent = { IconButton(onClick = { delete = f }) { Icon(Icons.Filled.Delete, stringResource(CoreR.string.delete)) } },
            )
        }
    }
    delete?.let { f ->
        ConfirmDialog(stringResource(R.string.set_font_delete), f.name, stringResource(CoreR.string.delete), onConfirm = { vm.deleteFont(f) }, onDismiss = { delete = null })
    }
}

// endregion

// region Error log

@Composable
internal fun ErrorLogSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val logs by vm.logs.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Pair<File, String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadLogs() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarBottomPadding(Dimens.xl))) {
        item(key = "hint") {
            Text(stringResource(R.string.set_error_log_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l))
        }
        if (logs.isEmpty()) {
            item(key = "empty") { EmptyState(title = stringResource(R.string.set_error_log_empty), icon = Icons.Outlined.BugReport, modifier = Modifier.fillMaxWidth()) }
        } else {
            item(key = "actions") {
                Row(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    FilledTonalButton(onClick = { shareLogs(context, logs) }) { Icon(Icons.Filled.Share, null); Text(stringResource(R.string.set_share_all), Modifier.padding(start = Dimens.s)) }
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(CoreR.string.clear)) }
                }
            }
        }
        items(logs, key = { it.name }) { f ->
            ListItem(
                headlineContent = { Text(f.name.substringBefore('_').replaceFirstChar { it.titlecase() }) },
                supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(f.lastModified())) + " · " + Files.humanSize(f.length())) },
                modifier = Modifier.clickable { scope.launch(ScreenErrors) { open = f to vm.readLog(f) } },
            )
        }
    }
    open?.let { (f, text) ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { shareLogs(context, listOf(f)) }) { Text(stringResource(CoreR.string.share)) } },
            dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(CoreR.string.close)) } },
        )
    }
    if (confirmClear) {
        ConfirmDialog(stringResource(R.string.set_error_log), stringResource(R.string.set_error_log_clear), stringResource(CoreR.string.clear), onConfirm = vm::clearLogs, onDismiss = { confirmClear = false })
    }
}

private fun shareLogs(context: Context, files: List<File>) {
    runCatching {
        val out = File(Share.shareDir(context), "yomu-log.txt")
        out.bufferedWriter().use { w -> files.forEach { f -> w.write("===== ${f.name} =====\n"); w.write(f.readText()); w.write("\n\n") } }
        Share.shareFile(context, out, "text/plain", "Yomu log")
    }
}

// endregion

// region Security

/** Salted PBKDF2 hashing of the app lock PIN. Stored as "salt:hash" (Base64). */
object PinHasher {
    private const val ITERATIONS = 20_000

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b64(salt) + ":" + b64(derive(pin, salt))
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 2) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

fun biometricAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
internal fun ColumnScope.SecuritySection(vm: SettingsViewModel) {
    val s = vm.settings
    val context = LocalContext.current
    val lock by s.appLock.state()
    val pin by s.pinHash.state()
    val bio by s.lockBiometric.state()
    val timeout by s.lockTimeoutSeconds.state()
    var setPin by remember { mutableStateOf(false) }
    val canBio = remember { biometricAvailable(context) }
    PreferenceSwitch(stringResource(R.string.set_app_lock), lock, { on ->
        if (on && pin.isBlank()) setPin = true else vm.set(s.appLock, on)
    }, stringResource(R.string.set_app_lock_sum), icon = settingIcon(R.string.set_app_lock))
    PreferenceItem(stringResource(R.string.set_change_pin), enabled = lock, onClick = { setPin = true }, icon = settingIcon(R.string.set_change_pin))
    PreferenceSwitch(
        stringResource(R.string.set_biometric), bio && canBio, { vm.set(s.lockBiometric, it) },
        if (canBio) null else stringResource(R.string.set_biometric_unavailable), enabled = lock && canBio,
        icon = settingIcon(R.string.set_biometric),
    )
    PreferenceChoice(
        stringResource(R.string.set_lock_timeout),
        listOf(0 to stringResource(R.string.set_lock_immediately), 30 to "30 s", 60 to stringResource(R.string.set_minutes, 1), 300 to stringResource(R.string.set_minutes, 5), 900 to stringResource(R.string.set_minutes, 15)),
        timeout,
        { vm.set(s.lockTimeoutSeconds, it) },
        icon = settingIcon(R.string.set_lock_timeout),
    )
    SwitchPref(vm, s.secureScreen, R.string.set_secure_screen, R.string.set_secure_screen_sum)
    if (setPin) {
        PinSetupDialog(onDismiss = { setPin = false }) { p ->
            vm.set(s.pinHash, PinHasher.hash(p)) { s.appLock.set(true) }
            setPin = false
        }
    }
}

@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = first.length >= 4 && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedTextField(first, { v -> first = v.filter { it.isDigit() }.take(12) }, label = { Text(stringResource(R.string.set_pin_new)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(second, { v -> second = v.filter { it.isDigit() }.take(12) }, label = { Text(stringResource(R.string.set_pin_repeat)) }, singleLine = true,
                    isError = second.isNotEmpty() && second != first.take(second.length),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.set_pin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(first) }) { Text(stringResource(CoreR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) } },
    )
}

/** Full-screen lock shown on start and after the lock timeout: PIN pad plus optional biometric prompt. */
@Composable
fun AppLockScreen(settings: AppSettings, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bio by settings.lockBiometric.state()
    val canBio = remember { biometricAvailable(context) }
    val title = stringResource(R.string.lock_title)
    val usePin = stringResource(R.string.lock_use_pin)

    fun promptBiometric() {
        val activity = context.findFragmentActivity() ?: return
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(usePin)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        runCatching { prompt.authenticate(info) }
    }

    LaunchedEffect(bio, canBio) { if (bio && canBio) promptBiometric() }

    fun submit() = scope.launch(ScreenErrors) {
        if (PinHasher.verify(pin, settings.pinHash.get())) onUnlocked() else { error = true; pin = "" }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(Dimens.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(Dimens.l))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (error) stringResource(R.string.lock_wrong_pin) else stringResource(R.string.lock_enter_pin),
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.s),
            )
            Row(Modifier.padding(vertical = Dimens.xl), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(Modifier.size(14.dp).background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.l), modifier = Modifier.padding(vertical = Dimens.xs)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable(enabled = k != "bio" || (bio && canBio)) {
                                error = false
                                when (k) {
                                    "del" -> pin = pin.dropLast(1)
                                    "bio" -> promptBiometric()
                                    else -> if (pin.length < 12) {
                                        pin += k
                                        if (pin.length >= 4) scope.launch(ScreenErrors) {
                                            if (PinHasher.verify(pin, settings.pinHash.get())) onUnlocked()
                                        }
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "del" -> Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.lock_delete))
                                "bio" -> if (bio && canBio) Icon(Icons.Filled.Fingerprint, stringResource(R.string.set_biometric))
                                else -> Text(k, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { submit() }, enabled = pin.length >= 4, modifier = Modifier.padding(top = Dimens.l)) { Text(stringResource(CoreR.string.ok)) }
        }
    }
}

// endregion

// region About (the screen itself is in SettingsAbout.kt)

internal val LIBRARIES = listOf(
    "AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Paging" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache 2.0",
    "Koin" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "Telephoto" to "Apache 2.0",
    "Jsoup" to "MIT",
    "PdfiumAndroid (io.legere)" to "Apache 2.0 / BSD (PDFium)",
    "junrar" to "UnRAR license (restricted)",
    "Apache Commons Compress, XZ for Java" to "Apache 2.0 / Public domain",
    "Google ML Kit (Translate, Language ID, Text Recognition)" to "ML Kit Terms of Service",
    "Timber" to "Apache 2.0",
)

// endregion
