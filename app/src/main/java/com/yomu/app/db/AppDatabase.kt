package com.yomu.app.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yomu.core.Constants
import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.TranslationCacheEntity
import com.yomu.app.db.entities.TranslationEntity
import com.yomu.app.db.entities.TranslationSessionEntity

// TranslationCacheEntity is retained after the cache deletion (#125, ADR-0002): dropping an entity
// forces version = 4, and this database builds with fallbackToDestructiveMigration(), which would
// wipe real translation history to remove a table that has never held a row.
@Database(
    entities = [
        ModelEntity::class,
        TranslationEntity::class,
        TranslationSessionEntity::class,
        TranslationCacheEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun historyDao(): HistoryDao
    abstract fun translationSessionDao(): TranslationSessionDao

    companion object {
        /**
         * #223 split the VISION model type into DETECTION and OCR. Room reads the type column by enum
         * name and throws on an unknown one, so stored rows are retyped in place: an existing install
         * keeps its downloaded models instead of losing them to a destructive migration. Rows of a
         * removed type (VISION or TRANSLATION with another id) are not in the registry and are dropped.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The detector's id at version 3; refreshModelList drops that row and its file as no longer curated.
                db.execSQL("UPDATE models SET type = 'DETECTION' WHERE id = 'bubble_detection_v1'")
                db.execSQL("UPDATE models SET type = 'OCR' WHERE id = '${Constants.MANGA_OCR_MODEL_ID}'")
                db.execSQL("DELETE FROM models WHERE type NOT IN ('DETECTION', 'OCR', 'LLM')")
            }
        }
    }
}
