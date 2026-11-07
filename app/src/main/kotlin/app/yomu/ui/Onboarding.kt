package app.yomu.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.yomu.R
import app.yomu.core.common.ScreenErrors
import app.yomu.core.datastore.AppSettings
import app.yomu.data.work.LibraryScanWorker
import kotlinx.coroutines.launch
import java.util.Locale

private val LANGUAGES = listOf("en", "ru", "uk", "de", "fr", "es", "it", "pt", "pl", "tr", "ja", "zh", "ko")

/** First start: what the app does, the books folder and the translation language. Every step can be skipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(settings: AppSettings, onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var folderName by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf(Locale.getDefault().language.takeIf { it in LANGUAGES } ?: "en") }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment
        scope.launch(ScreenErrors) {
            settings.scanFolders.update { it + uri.toString() }
            LibraryScanWorker.runNow(context)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    fun finish() {
        scope.launch(ScreenErrors) {
            settings.targetLang.set(language)
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else onDone()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (page) {
                        0 -> {
                            PageHeader(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.onboarding_welcome_title), stringResource(R.string.onboarding_welcome_text))
                            Feature(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.onboarding_feature_read))
                            Feature(Icons.Outlined.Translate, stringResource(R.string.onboarding_feature_words))
                            Feature(Icons.Outlined.Explore, stringResource(R.string.onboarding_feature_catalog))
                            Feature(Icons.Outlined.Edit, stringResource(R.string.onboarding_feature_editor))
                        }
                        1 -> {
                            PageHeader(Icons.Outlined.Folder, stringResource(R.string.onboarding_folder_title), stringResource(R.string.onboarding_folder_text))
                            val name = folderName
                            if (name == null) {
                                Button(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.onboarding_folder_pick)) }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(R.string.onboarding_folder_chosen, name), style = MaterialTheme.typography.bodyLarge)
                                }
                                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.onboarding_folder_another)) }
                            }
                            Text(
                                stringResource(R.string.onboarding_folder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> {
                            PageHeader(Icons.Outlined.Translate, stringResource(R.string.onboarding_language_title), stringResource(R.string.onboarding_language_text))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LANGUAGES.forEach { code ->
                                    val locale = Locale.forLanguageTag(code)
                                    FilterChip(
                                        selected = language == code,
                                        onClick = { language = code },
                                        label = { Text(locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            if (pager.currentPage > 0) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text(stringResource(R.string.onboarding_back)) }
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = { if (pager.currentPage < 2) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else finish() }) {
                Text(stringResource(if (pager.currentPage < 2) R.string.onboarding_next else R.string.onboarding_start))
            }
        }
    }
}

@Composable
private fun PageHeader(icon: ImageVector, title: String, text: String) {
    Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
