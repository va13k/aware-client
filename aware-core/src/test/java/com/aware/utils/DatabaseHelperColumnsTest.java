package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aware.providers.Aware_Provider;

import org.junit.Test;

import java.util.List;

/**
 * Unit tests for the column list an upgrade carries data across.
 *
 * This list decides which columns survive a schema change. Reading it from the table definition is
 * what allows a column to be dropped: anything the new definition omits stays behind with the old
 * table. Getting it wrong is quiet and expensive — naming a column the new table lacks aborts the
 * upgrade, which rolls back and leaves the database a version behind the code querying it.
 */
public class DatabaseHelperColumnsTest {

    @Test
    public void readsEveryColumnOfASimpleTable() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "_id integer primary key autoincrement,timestamp real default 0,device_id text default ''");
        assertEquals(3, columns.size());
        assertEquals("_id", columns.get(0));
        assertEquals("timestamp", columns.get(1));
        assertEquals("device_id", columns.get(2));
    }

    @Test
    public void aTrailingUniqueConstraintIsNotAColumn() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "_id integer primary key autoincrement,device_id text default '',UNIQUE(device_id)");
        assertEquals(2, columns.size());
        assertFalse(columns.contains("UNIQUE(device_id)"));
        assertFalse(columns.contains("UNIQUE"));
    }

    @Test
    public void commasInsideAConstraintDoNotSplitIt() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "a text default '',b text default '',UNIQUE(a, b)");
        assertEquals(2, columns.size());
        assertTrue(columns.contains("a"));
        assertTrue(columns.contains("b"));
    }

    @Test
    public void aColumnTheNewDefinitionOmitsIsNotCarriedOver() {
        // The intersection below is what an upgrade names in its carry-over INSERT. Naming a column
        // the new table lacks aborts that INSERT, which rolls back the upgrade and leaves the
        // database a version behind the code querying it.
        String before = "_id integer primary key autoincrement,device_id text default '',"
                + "brand text default '',model text default ''";
        String after = "_id integer primary key autoincrement,device_id text default '',"
                + "model text default ''";

        List<String> carried = DatabaseHelper.declaredColumns(before);
        carried.retainAll(DatabaseHelper.declaredColumns(after));

        assertEquals(3, carried.size());
        assertTrue(carried.contains("model"));
        assertFalse("a dropped column must not reach the carry-over", carried.contains("brand"));
    }

    @Test
    public void everyDeclaredTableYieldsColumns() {
        for (int i = 0; i < Aware_Provider.TABLES_FIELDS.length; i++) {
            assertTrue("table " + Aware_Provider.DATABASE_TABLES[i] + " declared no columns",
                    DatabaseHelper.declaredColumns(Aware_Provider.TABLES_FIELDS[i]).size() > 0);
        }
    }

    @Test
    public void everyColumnNameIsBareOfTypeAndDefault() {
        for (String column : DatabaseHelper.declaredColumns(Aware_Provider.TABLES_FIELDS[0])) {
            assertFalse(column + " carries more than a name", column.contains(" "));
        }
    }
}
