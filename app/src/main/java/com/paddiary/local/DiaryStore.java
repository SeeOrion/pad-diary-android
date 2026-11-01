package com.paddiary.local;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DiaryStore extends SQLiteOpenHelper {
    DiaryStore(Context context) { super(context, "diary.db", null, 2); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE notebooks (id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL, name TEXT NOT NULL, created_at INTEGER NOT NULL, cover_style INTEGER NOT NULL DEFAULT 0, cover_file TEXT)");
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', body_styles TEXT NOT NULL DEFAULT '[]', line_spacing REAL NOT NULL DEFAULT 1.3, entry_time INTEGER NOT NULL, place TEXT NOT NULL DEFAULT '', latitude REAL, longitude REAL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_id INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE, file_name TEXT NOT NULL, sort_order INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX entries_notebook_time ON entries(notebook_id, entry_time DESC)");
        db.execSQL("CREATE INDEX photos_entry ON photos(entry_id, sort_order)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notebooks ADD COLUMN cover_style INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notebooks ADD COLUMN cover_file TEXT");
            db.execSQL("ALTER TABLE entries ADD COLUMN body_styles TEXT NOT NULL DEFAULT '[]'");
            db.execSQL("ALTER TABLE entries ADD COLUMN line_spacing REAL NOT NULL DEFAULT 1.3");
        }
    }

    private static JSONObject row(Cursor c) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < c.getColumnCount(); i++) {
                if (c.isNull(i)) o.put(c.getColumnName(i), JSONObject.NULL);
                else if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) o.put(c.getColumnName(i), c.getLong(i));
                else if (c.getType(i) == Cursor.FIELD_TYPE_FLOAT) o.put(c.getColumnName(i), c.getDouble(i));
                else o.put(c.getColumnName(i), c.getString(i));
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return o;
    }

    synchronized JSONArray query(String sql, String... args) {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) rows.put(row(c));
        }
        return rows;
    }

    synchronized JSONObject one(String sql, String... args) {
        JSONArray a = query(sql, args);
        return a.length() == 0 ? null : a.optJSONObject(0);
    }

    synchronized long addCategory(String name) {
        ContentValues v = new ContentValues(); v.put("name", name.trim());
        return getWritableDatabase().insertOrThrow("categories", null, v);
    }

    synchronized long addNotebook(String name, Long categoryId) {
        ContentValues v = new ContentValues(); v.put("name", name.trim()); v.put("created_at", System.currentTimeMillis());
        if (categoryId == null) v.putNull("category_id"); else v.put("category_id", categoryId);
        return getWritableDatabase().insertOrThrow("notebooks", null, v);
    }

    synchronized long addEntry(long notebookId) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues(); v.put("notebook_id", notebookId); v.put("entry_time", now);
        v.put("created_at", now); v.put("updated_at", now);
        return getWritableDatabase().insertOrThrow("entries", null, v);
    }

    synchronized void saveEntry(long id, String title, String body, String styles, float spacing, long time, String place, Double lat, Double lon) {
        ContentValues v = new ContentValues(); v.put("title", title); v.put("body", body);
        v.put("body_styles", styles); v.put("line_spacing", spacing);
        v.put("entry_time", time); v.put("place", place); v.put("updated_at", System.currentTimeMillis());
        if (lat == null) v.putNull("latitude"); else v.put("latitude", lat);
        if (lon == null) v.putNull("longitude"); else v.put("longitude", lon);
        getWritableDatabase().update("entries", v, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void setCover(long id, int style, String fileName) {
        ContentValues v = new ContentValues(); v.put("cover_style", style);
        if (fileName == null) v.putNull("cover_file"); else v.put("cover_file", fileName);
        getWritableDatabase().update("notebooks", v, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized long addPhoto(long entryId, String fileName) {
        ContentValues v = new ContentValues(); v.put("entry_id", entryId); v.put("file_name", fileName);
        JSONObject last = one("SELECT COALESCE(MAX(sort_order),-1)+1 AS n FROM photos WHERE entry_id=?", String.valueOf(entryId));
        v.put("sort_order", last == null ? 0 : last.optInt("n"));
        return getWritableDatabase().insertOrThrow("photos", null, v);
    }

    synchronized void rename(String table, long id, String name) {
        if (!table.equals("categories") && !table.equals("notebooks")) throw new IllegalArgumentException("table");
        ContentValues v = new ContentValues(); v.put("name", name.trim());
        getWritableDatabase().update(table, v, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void moveNotebook(long id, Long categoryId) {
        ContentValues v = new ContentValues();
        if (categoryId == null) v.putNull("category_id"); else v.put("category_id", categoryId);
        getWritableDatabase().update("notebooks", v, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized void delete(String table, long id) {
        if (!table.equals("categories") && !table.equals("notebooks") && !table.equals("entries") && !table.equals("photos")) throw new IllegalArgumentException("table");
        getWritableDatabase().delete(table, "id=?", new String[]{String.valueOf(id)});
    }

    synchronized JSONObject snapshot() throws JSONException {
        JSONObject o = new JSONObject(); o.put("format", 2);
        o.put("categories", query("SELECT * FROM categories ORDER BY id"));
        o.put("notebooks", query("SELECT * FROM notebooks ORDER BY id"));
        o.put("entries", query("SELECT * FROM entries ORDER BY id"));
        o.put("photos", query("SELECT * FROM photos ORDER BY id"));
        return o;
    }

    synchronized void replace(JSONObject data) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("photos", null, null); db.delete("entries", null, null);
            db.delete("notebooks", null, null); db.delete("categories", null, null);
            insertRows(db, "categories", data.getJSONArray("categories"), new String[]{"id","name"});
            insertRows(db, "notebooks", data.getJSONArray("notebooks"), new String[]{"id","category_id","name","created_at","cover_style","cover_file"});
            insertRows(db, "entries", data.getJSONArray("entries"), new String[]{"id","notebook_id","title","body","body_styles","line_spacing","entry_time","place","latitude","longitude","created_at","updated_at"});
            insertRows(db, "photos", data.getJSONArray("photos"), new String[]{"id","entry_id","file_name","sort_order"});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void insertRows(SQLiteDatabase db, String table, JSONArray rows, String[] columns) throws JSONException {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); ContentValues v = new ContentValues();
            for (String column : columns) {
                if (!row.has(column)) continue;
                Object value = row.get(column);
                if (value == JSONObject.NULL) v.putNull(column);
                else if (value instanceof Integer) v.put(column, (Integer)value);
                else if (value instanceof Long) v.put(column, (Long)value);
                else if (value instanceof Double) v.put(column, (Double)value);
                else v.put(column, String.valueOf(value));
            }
            db.insertOrThrow(table, null, v);
        }
    }
}
