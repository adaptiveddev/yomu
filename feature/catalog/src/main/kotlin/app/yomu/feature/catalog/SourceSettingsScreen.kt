package app.yomu.feature.catalog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.fragment.compose.AndroidFragment
import androidx.preference.DialogPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import app.yomu.data.source.SourceManager
import app.yomu.data.source.tachiyomi.TachiyomiSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import timber.log.Timber
import app.yomu.core.ui.R as CoreR

/** Settings of a Mihon/Tachiyomi source (its own androidx.preference screen, as in Mihon). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsScreen(sourceId: Long, onBack: () -> Unit) {
    val sources = koinInject<SourceManager>()
    val source = sources.get(sourceId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(source?.name ?: stringResource(R.string.ext_source_settings))
                        if (source != null) Text(langLabel(source.lang, single = true), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreR.string.back)) } },
            )
        },
    ) { padding ->
        AndroidFragment<SourcePreferencesFragment>(
            modifier = Modifier.padding(padding).fillMaxSize(),
            arguments = bundleOf(SourcePreferencesFragment.ARG_SOURCE to sourceId),
        )
    }
}

/** Lets the extension build its preference screen into the source's own SharedPreferences ("source_<id>"). */
class SourcePreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val id = requireArguments().getLong(ARG_SOURCE)
        val source = GlobalContext.getOrNull()?.get<SourceManager>()?.get(id) as? TachiyomiSource
        val configurable = source?.delegate as? ConfigurableSource
        preferenceManager.sharedPreferencesName = configurable?.preferenceKey() ?: "source_$id"
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        if (configurable == null) return
        runCatching { configurable.setupPreferenceScreen(screen) }.onFailure { e ->
            Timber.e(e, "Source $id preferences")
            screen.addPreference(Preference(requireContext()).apply {
                title = getString(R.string.ext_broken_failed, e.message ?: e.javaClass.simpleName)
                isSelectable = false
            })
        }
        tidy(screen)
    }

    /** Same clean-ups Mihon applies: no reserved icon space, multi-line titles, dialog titles. */
    private fun tidy(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val p = group.getPreference(i)
            p.isIconSpaceReserved = false
            p.isSingleLineTitle = false
            if (p is DialogPreference && p.dialogTitle.isNullOrEmpty()) p.dialogTitle = p.title
            if (p is PreferenceGroup) tidy(p)
        }
    }

    companion object {
        const val ARG_SOURCE = "source_id"
    }
}
