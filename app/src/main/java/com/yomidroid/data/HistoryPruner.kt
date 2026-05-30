package com.yomidroid.data

import android.content.Context
import android.util.Log
import com.yomidroid.config.HistoryConfig
import com.yomidroid.config.HistoryRetentionMode
import java.io.File

/**
 * Drops history rows (and their on-disk screenshot files) that exceed the user's
 * retention policy. Invoked after every history insert and from the "Prune now"
 * action in settings.
 */
object HistoryPruner {

    private const val TAG = "HistoryPruner"

    suspend fun pruneIfNeeded(context: Context, cfg: HistoryConfig): Int {
        val dao = AppDatabase.getInstance(context).historyDao()
        val victims: List<LookupHistoryEntity> = when (cfg.mode) {
            HistoryRetentionMode.UNLIMITED -> return 0
            HistoryRetentionMode.BY_COUNT -> {
                val total = dao.count()
                if (total <= cfg.maxEntries) return 0
                dao.getOldest(total - cfg.maxEntries)
            }
            HistoryRetentionMode.BY_AGE -> {
                val cutoff = System.currentTimeMillis() - cfg.maxAgeDays * 86_400_000L
                dao.getOlderThan(cutoff)
            }
        }
        if (victims.isEmpty()) return 0

        victims.forEach { row ->
            row.screenshotPath?.let { path ->
                runCatching { File(path).delete() }
                    .onFailure { Log.w(TAG, "Failed to delete screenshot $path: ${it.message}") }
            }
        }
        dao.deleteByIds(victims.map { it.id })
        Log.d(TAG, "Pruned ${victims.size} history rows (mode=${cfg.mode})")
        return victims.size
    }

    /**
     * Sweep `history_screenshots/` for files that no longer have a corresponding
     * row in the database. Cheap insurance against past crashes that left orphan
     * files (matches the user's manual cleanup behavior).
     */
    suspend fun deleteOrphanScreenshots(context: Context): Int {
        val dir = File(context.filesDir, "history_screenshots")
        if (!dir.isDirectory) return 0
        val files = dir.listFiles() ?: return 0
        if (files.isEmpty()) return 0

        val dao = AppDatabase.getInstance(context).historyDao()
        val referenced = dao.getAll().mapNotNull { it.screenshotPath }.toHashSet()

        var deleted = 0
        files.forEach { f ->
            if (f.isFile && f.absolutePath !in referenced) {
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) Log.d(TAG, "Deleted $deleted orphan screenshot files")
        return deleted
    }
}
