package app.yomu.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Explicit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Margin
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** Leading icon of a settings row, by the row's title (null for rows without one). */
internal fun settingIcon(title: Int): ImageVector? = when (title) {
    // Appearance
    R.string.set_theme -> Icons.Outlined.DarkMode
    R.string.set_dynamic_color -> Icons.Outlined.Wallpaper
    R.string.set_amoled -> Icons.Outlined.Contrast
    R.string.set_language -> Icons.Outlined.Language
    R.string.set_haptics -> Icons.Outlined.Vibration
    R.string.set_reduce_motion -> Icons.Outlined.Animation
    R.string.set_nav_labels -> Icons.AutoMirrored.Outlined.Label
    // Library
    R.string.set_grid -> Icons.Outlined.GridView
    R.string.set_columns -> Icons.Outlined.ViewColumn
    R.string.set_show_continue -> Icons.Outlined.History
    R.string.set_hide_covers -> Icons.Outlined.HideImage
    R.string.set_import_mode -> Icons.Outlined.FileCopy
    R.string.set_ask_delete -> Icons.Outlined.DeleteOutline
    R.string.set_add_folder -> Icons.Outlined.CreateNewFolder
    R.string.set_auto_scan -> Icons.Outlined.Autorenew
    R.string.set_scan_interval -> Icons.Outlined.Schedule
    R.string.set_scan_now -> Icons.Outlined.Refresh
    // Reader
    R.string.set_fullscreen -> Icons.Outlined.Fullscreen
    R.string.set_cutout -> Icons.Outlined.Smartphone
    R.string.set_tap_turn -> Icons.Outlined.TouchApp
    R.string.set_volume_keys -> Icons.AutoMirrored.Outlined.VolumeUp
    R.string.set_autohide -> Icons.Outlined.VisibilityOff
    R.string.set_reading_stats -> Icons.Outlined.Insights
    R.string.set_open_stats -> Icons.Outlined.BarChart
    R.string.set_font_size -> Icons.Outlined.FormatSize
    R.string.set_line_height -> Icons.Outlined.FormatLineSpacing
    R.string.set_margins -> Icons.Outlined.Margin
    R.string.set_paged -> Icons.Outlined.AutoStories
    R.string.set_hyphenation -> Icons.AutoMirrored.Outlined.WrapText
    R.string.set_publisher_styles -> Icons.Outlined.Style
    R.string.set_highlight_words -> Icons.Outlined.Highlight
    R.string.set_word_tap -> Icons.Outlined.Gesture
    R.string.set_web_search -> Icons.Outlined.TravelExplore
    R.string.set_custom_search_url -> Icons.Outlined.Link
    R.string.set_tts_speed -> Icons.Outlined.Speed
    R.string.set_tts_pitch -> Icons.Outlined.GraphicEq
    // Manga and PDF
    R.string.set_pdf_night -> Icons.Outlined.Bedtime
    R.string.set_pdf_autocrop -> Icons.Outlined.Crop
    R.string.set_pdf_write_annotations -> Icons.Outlined.EditNote
    R.string.set_ocr_language -> Icons.Outlined.DocumentScanner
    // Translation, dictionaries, vocabulary
    R.string.set_translation_mode -> Icons.Outlined.Translate
    R.string.set_engine -> Icons.Outlined.Cloud
    R.string.set_target_lang -> Icons.Outlined.GTranslate
    R.string.set_source_lang -> Icons.Outlined.Language
    R.string.set_deepl_key, R.string.set_libre_key, R.string.set_llm_key -> Icons.Outlined.Key
    R.string.set_libre_url, R.string.set_llm_url -> Icons.Outlined.Link
    R.string.set_llm_model -> Icons.Outlined.SmartToy
    R.string.set_llm_prompt -> Icons.Outlined.EditNote
    R.string.set_translation_cache -> Icons.Outlined.Cached
    R.string.set_models_wifi -> Icons.Outlined.Wifi
    R.string.set_language_packs -> Icons.Outlined.CloudDownload
    R.string.set_online_dictionary -> Icons.Outlined.Public
    R.string.set_dictionary_lang -> Icons.Outlined.Translate
    R.string.set_dictionaries -> Icons.AutoMirrored.Outlined.MenuBook
    R.string.set_daily_goal -> Icons.Outlined.Flag
    R.string.set_reminder -> Icons.Outlined.Notifications
    R.string.set_reminder_time -> Icons.Outlined.Alarm
    // Catalog
    R.string.set_update_interval -> Icons.Outlined.Update
    R.string.set_update_wifi -> Icons.Outlined.Wifi
    R.string.set_net_update_wifi -> Icons.Outlined.Update
    R.string.set_show_nsfw -> Icons.Outlined.Explicit
    R.string.set_incognito -> Icons.Outlined.VisibilityOff
    R.string.set_hidden_sources -> Icons.Outlined.VisibilityOff
    R.string.set_extensions -> Icons.Outlined.Extension
    R.string.set_revoke_trust -> Icons.Outlined.RemoveModerator
    // Downloads and storage
    R.string.set_download_wifi -> Icons.Outlined.Wifi
    R.string.set_parallel -> Icons.Outlined.Layers
    R.string.set_page_threads -> Icons.Outlined.Bolt
    R.string.set_auto_download_new -> Icons.Outlined.NewReleases
    R.string.set_download_ahead -> Icons.Outlined.FastForward
    R.string.set_download_retries -> Icons.Outlined.Replay
    R.string.set_auto_delete -> Icons.Outlined.AutoDelete
    R.string.set_pack_cbz -> Icons.Outlined.FolderZip
    R.string.set_open_downloads -> Icons.Outlined.DownloadForOffline
    R.string.set_storage -> Icons.Outlined.Storage
    R.string.set_calculating -> Icons.Outlined.HourglassEmpty
    R.string.set_screen_timeout -> Icons.Outlined.LightMode
    R.string.set_orientation -> Icons.Outlined.ScreenRotation
    R.string.set_reader_theme -> Icons.Outlined.Palette
    R.string.set_pdf_mode -> Icons.Outlined.PictureAsPdf
    R.string.set_storage_total -> Icons.Outlined.PieChart
    R.string.set_storage_books -> Icons.AutoMirrored.Outlined.LibraryBooks
    R.string.set_storage_chapters -> Icons.Outlined.Collections
    R.string.set_storage_converted -> Icons.Outlined.Transform
    R.string.set_storage_covers -> Icons.Outlined.Image
    R.string.set_clear_cache -> Icons.Outlined.CleaningServices
    // Network and traffic
    R.string.set_data_saver -> Icons.Outlined.DataSaverOn
    R.string.set_covers_mobile -> Icons.Outlined.Image
    R.string.set_warn_mobile -> Icons.Outlined.SignalCellularAlt
    R.string.set_traffic_reset -> Icons.Outlined.RestartAlt
    // Backup and sync
    R.string.set_backup_create -> Icons.Outlined.Backup
    R.string.set_backup_files -> Icons.Outlined.AttachFile
    R.string.set_backup_restore -> Icons.Outlined.SettingsBackupRestore
    R.string.set_auto_backup -> Icons.Outlined.Schedule
    R.string.set_backup_folder -> Icons.Outlined.Folder
    R.string.set_backup_keep -> Icons.Outlined.Inventory2
    R.string.set_backup_now -> Icons.Outlined.CloudUpload
    R.string.set_sync -> Icons.Outlined.Sync
    R.string.set_sync_folder -> Icons.Outlined.FolderShared
    R.string.set_sync_now -> Icons.Outlined.CloudSync
    R.string.set_mihon_import -> Icons.Outlined.ImportExport
    // Sync (2.0.0)
    R.string.set_sync_target, R.string.set_sync_method -> Icons.Outlined.Dns
    R.string.set_webdav_url -> Icons.Outlined.Cloud
    R.string.set_sync_check -> Icons.Outlined.NetworkCheck
    R.string.set_sync_on_start -> Icons.Outlined.PlayCircle
    R.string.set_sync_on_exit -> Icons.AutoMirrored.Outlined.ExitToApp
    R.string.set_sync_interval -> Icons.Outlined.Schedule
    R.string.set_sync_catalog -> Icons.Outlined.Public
    R.string.set_sync_progress -> Icons.Outlined.Bookmarks
    R.string.set_sync_annotations -> Icons.Outlined.BorderColor
    R.string.set_sync_bookmarks -> Icons.Outlined.BookmarkBorder
    R.string.set_sync_words -> Icons.Outlined.Translate
    R.string.set_sync_track_links -> Icons.Outlined.TrackChanges
    R.string.set_sync_wifi_only -> Icons.Outlined.Wifi
    R.string.set_sync_deletions -> Icons.Outlined.DeleteSweep
    // Tracking, long-press menus (2.0.0)
    R.string.set_track_auto -> Icons.Outlined.TrackChanges
    R.string.set_track_push -> Icons.Outlined.Publish
    R.string.set_track_keys -> Icons.Outlined.Key
    R.string.set_page_menu_style -> Icons.Outlined.TouchApp
    R.string.set_selection_menu_style -> Icons.Outlined.TextFields
    R.string.set_page_actions -> Icons.Outlined.Checklist
    R.string.set_selection_actions -> Icons.Outlined.Reorder
    R.string.set_reset_all -> Icons.Outlined.RestartAlt
    // Security
    R.string.set_app_lock -> Icons.Outlined.Lock
    R.string.set_change_pin -> Icons.Outlined.Pin
    R.string.set_biometric -> Icons.Outlined.Fingerprint
    R.string.set_lock_timeout -> Icons.Outlined.Timer
    R.string.set_secure_screen -> Icons.Outlined.ScreenLockPortrait
    // Fonts, selection, advanced, about
    R.string.set_font_import -> Icons.Outlined.FontDownload
    R.string.set_selection_reset -> Icons.Outlined.RestartAlt
    R.string.set_custom_add -> Icons.Outlined.Add
    R.string.set_verbose_logging -> Icons.Outlined.Terminal
    R.string.set_error_log -> Icons.Outlined.BugReport
    R.string.set_reset_onboarding -> Icons.Outlined.Replay
    R.string.set_reset_hints -> Icons.Outlined.TipsAndUpdates
    R.string.set_version -> Icons.Outlined.Info
    R.string.set_update_auto -> Icons.Outlined.Update
    R.string.set_license -> Icons.Outlined.Gavel
    R.string.set_privacy -> Icons.Outlined.PrivacyTip
    R.string.set_libraries -> Icons.Outlined.Code
    else -> null
}
