
package com.aware.utils;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.aware.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * ContentProvider database helper<br/>
 * This class is responsible to make sure we have the most up-to-date database structures from plugins and sensors
 *
 * @author denzil
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private final boolean DEBUG = true;

    private String TAG = "AwareDBHelper";

    private String databaseName;
    private String[] databaseTables;
    private String[] tableFields;
    private int newVersion;
    private CursorFactory cursorFactory;
    private SQLiteDatabase database;
    private Context mContext;

    private HashMap<String, String> renamed_columns = new HashMap<>();

    public DatabaseHelper(Context context, String database_name, CursorFactory cursor_factory, int database_version, String[] database_tables, String[] table_fields) {
        super(context, database_name, cursor_factory, database_version);
        mContext = context;
        databaseName = database_name;
        databaseTables = database_tables;
        tableFields = table_fields;
        newVersion = database_version;
        cursorFactory = cursor_factory;
    }

    public void setRenamedColumns(HashMap<String, String> renamed) {
        renamed_columns = renamed;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        if (DEBUG) Log.w(TAG, "Creating database: " + db.getPath());
        for (int i = 0; i < databaseTables.length; i++) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + databaseTables[i] + " (" + tableFields[i] + ");");
            db.execSQL("CREATE INDEX IF NOT EXISTS time_device ON " + databaseTables[i] + " (timestamp, device_id);");
        }
        db.setVersion(newVersion);
    }

    /**
     * The column names declared by a table's field definition.
     *
     * Read from the definition rather than from the table it creates, because the carry-over below
     * needs the new column set before the new table holds any rows, and a table's shape is fully
     * described by the definition already in hand.
     *
     * Splits on the commas between column definitions, which means stepping over the commas inside a
     * trailing table constraint such as {@code UNIQUE(a, b)}; a constraint contributes no column and
     * is skipped.
     *
     * @param fields one entry of the table-fields array, as handed to the constructor
     * @return the column names, in declaration order
     */
    static List<String> declaredColumns(String fields) {
        List<String> columns = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i <= fields.length(); i++) {
            char c = i < fields.length() ? fields.charAt(i) : ',';
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                String definition = current.toString().trim();
                current.setLength(0);
                if (definition.isEmpty()) continue;
                String name = definition.split("\\s+")[0];
                // A table constraint (UNIQUE(...), PRIMARY KEY(...), FOREIGN KEY ...) names no column.
                if (name.indexOf('(') >= 0) continue;
                String upper = name.toUpperCase();
                if (upper.equals("UNIQUE") || upper.equals("PRIMARY") || upper.equals("FOREIGN")
                        || upper.equals("CHECK") || upper.equals("CONSTRAINT")) continue;
                columns.add(name);
            } else {
                current.append(c);
            }
        }
        return columns;
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (DEBUG) Log.w(TAG, "Upgrading database: " + db.getPath());

        for (int i = 0; i < databaseTables.length; i++) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + databaseTables[i] + " (" + tableFields[i] + ");");

            //Modify existing tables if there are changes, while retaining old data. This also works for brand new tables, where nothing is changed.
            List<String> columns = getColumns(db, databaseTables[i]);

            // An upgrade runs in a transaction, so an attempt that fails rolls back and leaves the
            // original table in place — but a temp_ table created outside that transaction's reach
            // would survive and collide with the rename below.
            db.execSQL("DROP TABLE IF EXISTS temp_" + databaseTables[i] + ";");
            db.execSQL("ALTER TABLE " + databaseTables[i] + " RENAME TO temp_" + databaseTables[i] + ";");

            db.execSQL("CREATE TABLE " + databaseTables[i] + " (" + tableFields[i] + ");");
            db.execSQL("CREATE INDEX IF NOT EXISTS time_device ON " + databaseTables[i] + " (timestamp, device_id);");

            // The new table is empty at this point, so its shape comes from the definition that
            // created it. Carrying over only the columns both shapes share is what lets a column be
            // dropped: it stays behind with the temp table.
            columns.retainAll(declaredColumns(tableFields[i]));

            String cols = TextUtils.join(",", columns);
            String new_cols = cols;

            if (renamed_columns.size() > 0) {
                for (String key : renamed_columns.keySet()) {
                    if (DEBUG) Log.d(TAG, "Renaming: " + key + " -> " + renamed_columns.get(key));
                    new_cols = new_cols.replace(key, renamed_columns.get(key));
                }
            }

            //restore old data back
            if (DEBUG)
                Log.d(TAG, String.format("INSERT INTO %s (%s) SELECT %s from temp_%s;", databaseTables[i], new_cols, cols, databaseTables[i]));

            db.execSQL(String.format("INSERT INTO %s (%s) SELECT %s from temp_%s;", databaseTables[i], new_cols, cols, databaseTables[i]));
            db.execSQL("DROP TABLE temp_" + databaseTables[i] + ";");
        }
        db.setVersion(newVersion);
    }

    /**
     * Creates a String of a JSONArray representation of a database cursor result
     *
     * @param cursor
     * @return String
     */
    public static String cursorToString(Cursor cursor) {
        JSONArray jsonArray = new JSONArray();
        if (cursor != null && cursor.moveToFirst()) {
            do {
                int nColumns = cursor.getColumnCount();
                JSONObject row = new JSONObject();
                for (int i = 0; i < nColumns; i++) {
                    String colName = cursor.getColumnName(i);
                    if (colName != null) {
                        try {
                            switch (cursor.getType(i)) {
                                case Cursor.FIELD_TYPE_BLOB:
                                    row.put(colName, cursor.getBlob(i).toString());
                                    break;
                                case Cursor.FIELD_TYPE_FLOAT:
                                    row.put(colName, cursor.getDouble(i));
                                    break;
                                case Cursor.FIELD_TYPE_INTEGER:
                                    row.put(colName, cursor.getLong(i));
                                    break;
                                case Cursor.FIELD_TYPE_NULL:
                                    row.put(colName, null);
                                    break;
                                case Cursor.FIELD_TYPE_STRING:
                                    row.put(colName, cursor.getString(i));
                                    break;
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
                jsonArray.put(row);
            } while (cursor.moveToNext());
        }
        if (cursor != null && !cursor.isClosed()) cursor.close();

        return jsonArray.toString();
    }

    private static List<String> getColumns(SQLiteDatabase db, String tableName) {
        List<String> columns = null;
        Cursor database_meta = db.rawQuery("SELECT * FROM " + tableName + " LIMIT 1", null);
        if (database_meta != null) {
            columns = new ArrayList<>(Arrays.asList(database_meta.getColumnNames()));
        }
        if (database_meta != null && !database_meta.isClosed()) database_meta.close();

        return columns;
    }

    @Override
    public synchronized SQLiteDatabase getWritableDatabase() {
        try {
            if (database != null) {
                if (!database.isOpen()) {
                    database = null;
                } else if (!database.isReadOnly()) {
                    return database;
                }
            }

            database = getDatabaseFile();
            if (database == null) return null;

            int current_version = database.getVersion();
            if (current_version != newVersion) {
                database.beginTransaction();
                try {
                    if (current_version == 0) {
                        onCreate(database);
                    } else {
                        onUpgrade(database, current_version, newVersion);
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            }
            return database;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public synchronized SQLiteDatabase getReadableDatabase() {
        try {
            if (database != null) {
                if (!database.isOpen()) {
                    database = null;
                }
            }
            database = getDatabaseFile();
            return database;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the SQLiteDatabase
     *
     * @return
     */
    private synchronized SQLiteDatabase getDatabaseFile() {
        try {
            File aware_folder;
            if (mContext.getResources().getBoolean(R.bool.internalstorage)) {
                // Internal storage.  This is not acceassible to any other apps and is removed once
                // app is uninstalled.  Plugins can't use it.  Hard-coded to off, only change if
                // you know what you are doing.  Beware!
                aware_folder = mContext.getFilesDir();
            } else if (!mContext.getResources().getBoolean(R.bool.standalone)) {
                // sdcard/AWARE/ (shareable, does not delete when uninstalling)
                aware_folder = new File(Environment.getExternalStoragePublicDirectory("AWARE").toString());
            } else {
                if (isEmulator()) {
                    aware_folder = mContext.getFilesDir();
                } else {
                    // sdcard/Android/<app_package_name>/AWARE/ (not shareable, deletes when uninstalling package)
                    aware_folder = new File(ContextCompat.getExternalFilesDirs(mContext, null)[0] + "/AWARE");
                }
            }

            if (!aware_folder.exists()) {
                aware_folder.mkdirs();
            }

            database = SQLiteDatabase.openOrCreateDatabase(new File(aware_folder, this.databaseName).getPath(), this.cursorFactory);
            return database;
        } catch (SQLiteException e) {
            return null;
        }
    }

    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }
}
