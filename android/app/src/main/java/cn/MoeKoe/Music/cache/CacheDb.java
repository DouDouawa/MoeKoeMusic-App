package cn.MoeKoe.Music.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite index of complete offline files.
 *
 * <p>Files live in app-private storage next to this database; a row only exists for a file that
 * passed verification, so "listed" and "playable offline" mean the same thing.
 */
public final class CacheDb extends SQLiteOpenHelper {

    private static final String NAME = "moe_audio_cache.db";
    private static final int VERSION = 1;
    private static final String TABLE = "entries";

    public CacheDb(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "key TEXT PRIMARY KEY,"
                + "source TEXT NOT NULL,"
                + "account_id TEXT NOT NULL,"
                + "hash TEXT NOT NULL,"
                + "play_hash TEXT,"
                + "quality TEXT,"
                + "is_preview INTEGER NOT NULL DEFAULT 0,"
                + "name TEXT,"
                + "author TEXT,"
                + "album TEXT,"
                + "img TEXT,"
                + "time_length INTEGER NOT NULL DEFAULT 0,"
                + "preview_duration REAL NOT NULL DEFAULT 0,"
                + "mime TEXT,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "path TEXT NOT NULL,"
                + "complete INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL,"
                + "last_access_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_entries_hash ON " + TABLE + " (hash, account_id)");
        db.execSQL("CREATE INDEX idx_entries_access ON " + TABLE + " (last_access_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first native schema; nothing to migrate yet.
    }

    public void upsert(CacheEntry entry) {
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict(TABLE, null, toValues(entry), SQLiteDatabase.CONFLICT_REPLACE);
    }

    public CacheEntry get(String key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query(TABLE, null, "key = ?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        }
    }

    public List<CacheEntry> byHash(String hash, String accountId, boolean cloud) {
        SQLiteDatabase db = getReadableDatabase();
        String where = cloud ? "hash = ? AND source = 'cloud' AND account_id = ?" : "hash = ? AND source = 'online'";
        String[] args = cloud ? new String[]{hash, accountId} : new String[]{hash};
        List<CacheEntry> entries = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, null, where, args, null, null, "last_access_at DESC")) {
            while (cursor.moveToNext()) entries.add(fromCursor(cursor));
        }
        return entries;
    }

    public List<CacheEntry> all(String accountId) {
        SQLiteDatabase db = getReadableDatabase();
        List<CacheEntry> entries = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, null, "source = 'online' OR account_id = ?",
                new String[]{accountId == null ? "" : accountId}, null, null, "last_access_at DESC")) {
            while (cursor.moveToNext()) entries.add(fromCursor(cursor));
        }
        return entries;
    }

    public List<CacheEntry> lru(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<CacheEntry> entries = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, null, null, null, null, null, "last_access_at ASC",
                String.valueOf(Math.max(1, limit)))) {
            while (cursor.moveToNext()) entries.add(fromCursor(cursor));
        }
        return entries;
    }

    public long totalSize() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(size), 0) FROM " + TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    public long sizeOf(String key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(size), 0) FROM " + TABLE + " WHERE key = ?",
                new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void touch(String key, long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("last_access_at", timestamp);
        db.update(TABLE, values, "key = ?", new String[]{key});
    }

    public void delete(String key) {
        getWritableDatabase().delete(TABLE, "key = ?", new String[]{key});
    }

    public List<String> allKeys() {
        SQLiteDatabase db = getReadableDatabase();
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, new String[]{"key"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) keys.add(cursor.getString(0));
        }
        return keys;
    }

    private static ContentValues toValues(CacheEntry entry) {
        ContentValues values = new ContentValues();
        values.put("key", entry.key);
        values.put("source", entry.source);
        values.put("account_id", entry.accountId);
        values.put("hash", entry.hash);
        values.put("play_hash", entry.playHash);
        values.put("quality", entry.quality);
        values.put("is_preview", entry.isPreview ? 1 : 0);
        values.put("name", entry.name);
        values.put("author", entry.author);
        values.put("album", entry.album);
        values.put("img", entry.img);
        values.put("time_length", entry.timeLength);
        values.put("preview_duration", entry.previewDuration);
        values.put("mime", entry.mime);
        values.put("size", entry.size);
        values.put("path", entry.path);
        values.put("complete", entry.complete ? 1 : 0);
        values.put("created_at", entry.createdAt);
        values.put("last_access_at", entry.lastAccessAt);
        return values;
    }

    private static CacheEntry fromCursor(Cursor cursor) {
        CacheEntry entry = new CacheEntry();
        entry.key = string(cursor, "key");
        entry.source = string(cursor, "source");
        entry.accountId = string(cursor, "account_id");
        entry.hash = string(cursor, "hash");
        entry.playHash = string(cursor, "play_hash");
        entry.quality = string(cursor, "quality");
        entry.isPreview = cursor.getInt(cursor.getColumnIndexOrThrow("is_preview")) != 0;
        entry.name = string(cursor, "name");
        entry.author = string(cursor, "author");
        entry.album = string(cursor, "album");
        entry.img = string(cursor, "img");
        entry.timeLength = cursor.getLong(cursor.getColumnIndexOrThrow("time_length"));
        entry.previewDuration = cursor.getDouble(cursor.getColumnIndexOrThrow("preview_duration"));
        entry.mime = string(cursor, "mime");
        entry.size = cursor.getLong(cursor.getColumnIndexOrThrow("size"));
        entry.path = string(cursor, "path");
        entry.complete = cursor.getInt(cursor.getColumnIndexOrThrow("complete")) != 0;
        entry.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        entry.lastAccessAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_access_at"));
        return entry;
    }

    private static String string(Cursor cursor, String column) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }
}
