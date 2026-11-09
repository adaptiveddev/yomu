package app.yomu.feature.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.yomu.core.common.ScreenErrors
import app.yomu.core.common.UiState
import app.yomu.core.datastore.AppSettings
import app.yomu.data.translation.TranslationService
import app.yomu.domain.translation.TranslationException
import app.yomu.domain.translation.TranslationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EngineOption(val id: String, val name: String, val offline: Boolean, val configured: Boolean)

data class TranslationUi(
    val source: String = "",
    /** null = detect automatically. */
    val from: String? = null,
    val to: String = "",
    /** null = automatic engine chain from the settings. */
    val engineId: String? = null,
    val engines: List<EngineOption> = emptyList(),
    val result: UiState<TranslationResult> = UiState.Empty,
    val missingModel: Boolean = false,
    val downloading: Boolean = false,
)

/** State of the translation bottom sheet: one source text, switchable engine and languages. */
class TranslationViewModel(
    private val service: TranslationService,
    private val settings: AppSettings,
    val speaker: Speaker,
) : ViewModel() {
    private val _state = MutableStateFlow(TranslationUi())
    val state: StateFlow<TranslationUi> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch(ScreenErrors) {
            val engines = service.engines.map { EngineOption(it.id, it.displayName, it.isOffline, it.isConfigured()) }
            _state.update { it.copy(engines = engines, to = it.to.ifBlank { service.targetLanguage() }) }
        }
    }

    fun start(text: String, from: String? = null) {
        _state.update { it.copy(source = text.trim(), from = from) }
        translate()
    }

    fun setEngine(id: String?) {
        _state.update { it.copy(engineId = id) }
        translate()
    }

    fun setFrom(lang: String?) {
        _state.update { it.copy(from = lang) }
        translate()
    }

    fun setTo(lang: String) {
        _state.update { it.copy(to = lang) }
        viewModelScope.launch(ScreenErrors) { settings.targetLang.set(lang) }
        translate()
    }

    fun swap() {
        val s = _state.value
        val detected = (s.result as? UiState.Data)?.value?.from ?: s.from ?: return
        val translated = (s.result as? UiState.Data)?.value?.translated
        _state.update { it.copy(from = s.to, to = detected, source = translated ?: s.source) }
        translate()
    }

    fun retry() = translate()

    fun downloadModels() {
        val s = _state.value
        viewModelScope.launch(ScreenErrors) {
            _state.update { it.copy(downloading = true) }
            app.yomu.core.common.runCatchingApp {
                val from = s.from ?: service.detect(s.source)
                val wifi = settings.modelsWifiOnly.get()
                listOfNotNull(from, s.to).distinct().filter { it != "en" }.forEach { service.mlKit.download(it, wifi) }
            }
            _state.update { it.copy(downloading = false) }
            translate()
        }
    }

    private fun translate() {
        val s = _state.value
        if (s.source.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch(ScreenErrors) {
            _state.update { it.copy(result = UiState.Loading, missingModel = false) }
            val to = s.to.ifBlank { service.targetLanguage() }
            runCatching { service.translate(s.source, s.from, to, s.engineId) }
                .onSuccess { r -> _state.update { it.copy(result = UiState.Data(r), to = to) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val missing = (e as? TranslationException)?.kind == TranslationException.Kind.MODEL_MISSING
                    _state.update { it.copy(result = UiState.Error(app.yomu.core.common.AppError.from(e)), missingModel = missing) }
                }
        }
    }

    override fun onCleared() {
        speaker.stop()
    }
}
