package app.yomu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.yomu.R
import app.yomu.YomuActivity
import app.yomu.data.catalog.CatalogRepository
import app.yomu.data.repository.BookRepositoryImpl
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import kotlin.math.roundToInt

/** A book or an online title on the "Continue reading" widget. */
data class WidgetEntry(
    val id: Long,
    val online: Boolean,
    val title: String,
    val author: String?,
    val progress: Float,
    val unread: Int,
    val cover: String?,
    val time: Long,
)

/**
 * The "Continue reading" home-screen widget (2.0.0): the books and online titles in progress, the most recent first.
 * A tap opens the book where it was left (an online title at the chapter to continue with).
 */
class ContinueReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    override fun onEnabled(context: Context) {
        ContinueWidgets.start(context.applicationContext)
    }

    override fun onDisabled(context: Context) = ContinueWidgets.stop()

    private fun refreshAsync(context: Context) {
        val app = context.applicationContext
        ContinueWidgets.start(app)
        // Null when the call does not come from a broadcast (a launcher or a test calling the provider directly).
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        ContinueWidgets.scope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { ContinueWidgets.refresh(app) }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        /** goAsync() leaves a receiver about ten seconds. */
        const val BROADCAST_BUDGET_MS = 8_000L
    }
}

object ContinueWidgets : KoinComponent {
    const val ACTION_OPEN_BOOK = "app.yomu.action.WIDGET_OPEN_BOOK"
    const val ACTION_OPEN_ONLINE = "app.yomu.action.WIDGET_OPEN_ONLINE"
    const val EXTRA_ID = "app.yomu.extra.ID"
    private const val MAX_ROWS = 3
    private const val COVER_W = 108
    private const val COVER_H = 150

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val rowIds = intArrayOf(R.id.widget_row_0, R.id.widget_row_1, R.id.widget_row_2)
    private val coverIds = intArrayOf(R.id.widget_cover_0, R.id.widget_cover_1, R.id.widget_cover_2)
    private val titleIds = intArrayOf(R.id.widget_title_0, R.id.widget_title_1, R.id.widget_title_2)
    private val subtitleIds = intArrayOf(R.id.widget_subtitle_0, R.id.widget_subtitle_1, R.id.widget_subtitle_2)
    private val progressIds = intArrayOf(R.id.widget_progress_0, R.id.widget_progress_1, R.id.widget_progress_2)

    fun widgetIds(context: Context): IntArray = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ContinueReadingWidget::class.java))
    }.getOrDefault(IntArray(0))

    /** Keeps the widgets up to date while the app process lives (only when there is a widget on the home screen). */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true || widgetIds(context).isEmpty()) return
        job = scope.launch {
            entries().debounce(DEBOUNCE_MS).distinctUntilChanged().collectLatest { list ->
                runCatching { render(context, list) }.onFailure { Timber.w(it, "Widget update failed") }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Redraws the widgets right away (a widget was added, resized or the system asked for it). */
    suspend fun refresh(context: Context) {
        runCatching { render(context, entries().first()) }.onFailure { Timber.w(it, "Widget update failed") }
    }

    /** Books and online titles in progress, the most recent first. */
    private fun entries(): Flow<List<WidgetEntry>> {
        val books = get<BookRepositoryImpl>()
        val catalog = get<CatalogRepository>()
        return combine(books.observeContinueReading(MAX_ROWS), catalog.observeFavorites(), get<app.yomu.core.datastore.AppSettings>().hideCovers.flow) { b, o, hide ->
            val local = b.map { WidgetEntry(it.id, false, it.title, it.authors.firstOrNull(), it.progress, 0, it.coverPath.takeUnless { hide }, it.lastReadAt ?: 0) }
            val remote = o.filter { it.lastReadAt > 0 && it.unread > 0 }.map { e ->
                val fraction = if (e.chapterCount <= 0) 0f else (e.chapterCount - e.unread).coerceAtLeast(0).toFloat() / e.chapterCount
                WidgetEntry(e.id, true, e.title, e.author, fraction, e.unread, e.coverUrl.takeUnless { hide }, e.lastReadAt)
            }
            (local + remote).sortedByDescending { it.time }.take(MAX_ROWS)
        }
    }

    private suspend fun render(context: Context, list: List<WidgetEntry>) {
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val covers = list.associate { e ->
            val cacheKey = e.key + "|" + e.cover
            val cached = synchronized(coverCache) { coverCache[cacheKey] }
            e.key to (cached ?: loadCover(context, e)?.also { b -> synchronized(coverCache) { coverCache[cacheKey] = b } })
        }
        ids.forEach { id -> manager.updateAppWidget(id, views(context, list, covers, rowsFor(manager.getAppWidgetOptions(id)), id)) }
    }

    private val WidgetEntry.key: String get() = (if (online) "o" else "b") + id

    /** Covers already drawn (the widget is redrawn on every page turn while the reader is open). */
    private val coverCache = object : LinkedHashMap<String, Bitmap>(COVER_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > COVER_CACHE
    }

    /** How many rows fit the height of the widget. */
    private fun rowsFor(options: Bundle?): Int {
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 }
            ?: options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)?.takeIf { it > 0 }
            ?: return MAX_ROWS
        return ((h - HEADER_DP) / ROW_DP).coerceIn(1, MAX_ROWS)
    }

    private fun views(context: Context, list: List<WidgetEntry>, covers: Map<String, Bitmap?>, rows: Int, widgetId: Int): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_continue)
        val openApp = PendingIntent.getActivity(
            context,
            widgetId * REQUESTS_PER_WIDGET,
            Intent(context, YomuActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.widget_header, openApp)
        v.setOnClickPendingIntent(R.id.widget_empty, openApp)
        v.setViewVisibility(R.id.widget_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
        for (i in 0 until MAX_ROWS) {
            val e = list.getOrNull(i)
            if (e == null || i >= rows) {
                v.setViewVisibility(rowIds[i], View.GONE)
                continue
            }
            v.setViewVisibility(rowIds[i], View.VISIBLE)
            v.setTextViewText(titleIds[i], e.title)
            val percent = (e.progress.coerceIn(0f, 1f) * 100).roundToInt()
            val subtitle = if (e.online) {
                context.resources.getQuantityString(R.plurals.widget_unread_chapters, e.unread, e.unread)
            } else {
                listOfNotNull(e.author?.takeIf { it.isNotBlank() }, "$percent%").joinToString(" · ")
            }
            v.setTextViewText(subtitleIds[i], subtitle)
            v.setProgressBar(progressIds[i], PROGRESS_MAX, (e.progress.coerceIn(0f, 1f) * PROGRESS_MAX).roundToInt(), false)
            val cover = covers[e.key]
            if (cover != null) v.setImageViewBitmap(coverIds[i], cover) else v.setImageViewResource(coverIds[i], R.drawable.widget_cover_placeholder)
            val intent = Intent(context, YomuActivity::class.java)
                .setAction(if (e.online) ACTION_OPEN_ONLINE else ACTION_OPEN_BOOK)
                .putExtra(EXTRA_ID, e.id)
                // Keeps the intents of the rows apart (extras do not count when the system compares them).
                .setData(Uri.parse("yomu-widget://${e.key}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                rowIds[i],
                PendingIntent.getActivity(context, widgetId * REQUESTS_PER_WIDGET + i + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return v
    }

    /** A small cover with rounded corners: the local file of a book, the (cached) picture of an online title. */
    private suspend fun loadCover(context: Context, e: WidgetEntry): Bitmap? = runCatching {
        val src = e.cover ?: return null
        val raw = if (e.online) {
            val request = ImageRequest.Builder(context).data(src).size(COVER_W, COVER_H).allowHardware(false).build()
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
        } else {
            decodeSampled(src)
        } ?: return null
        rounded(raw)
    }.onFailure { Timber.w(it, "Widget cover") }.getOrNull()

    private fun decodeSampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_W && bounds.outHeight / (sample * 2) >= COVER_H) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Center-cropped to the cover size with rounded corners (RemoteViews cannot clip). */
    private fun rounded(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
        val scale = maxOf(COVER_W / src.width.toFloat(), COVER_H / src.height.toFloat())
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((COVER_W - src.width * scale) / 2f, (COVER_H - src.height * scale) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        Canvas(out).drawRoundRect(RectF(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat()), CORNER_PX, CORNER_PX, paint)
        return out
    }

    private const val DEBOUNCE_MS = 1_500L
    private const val PROGRESS_MAX = 1000
    private const val HEADER_DP = 52
    private const val ROW_DP = 62
    private const val REQUESTS_PER_WIDGET = 8
    private const val CORNER_PX = 10f
    private const val COVER_CACHE = 6
}
