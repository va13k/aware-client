package com.aware.utils;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.aware.Aware;
import com.aware.Aware_Preferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * This class will encapsulate the processes between the client and a MySQL database via JDBC API.
 */
public class Jdbc {
    private final static String TAG = "JDBC";
    private static Connection connection;
    private static int transactionCount = 0;

    private static class JdbcConnectionException extends Exception {
        private JdbcConnectionException(String message) {
            super(message);
        }
    }

    /**
     * Outcome of a database connection attempt, distinguishing an authentication failure (the
     * stored password is wrong) from an unreachable server (transient / network). Callers need
     * this distinction so that "the password changed" is handled differently from "the database is
     * temporarily down": only the former should ask the participant to re-authenticate.
     */
    public enum ConnectionResult { OK, AUTH_FAILED, UNREACHABLE }

    /**
     * Classifies a {@link SQLException} from a connection attempt as an authentication failure or a
     * reachability failure. Pure and side-effect free so it can be unit-tested without a database.
     * <p>
     * MySQL signals access-denied with SQLState {@code 28000} and vendor error code {@code 1045};
     * anything else (connect/socket timeout, communications link failure, unknown host, driver
     * errors) is treated as {@link ConnectionResult#UNREACHABLE}. The safe default is
     * {@code UNREACHABLE} so an unrecognised error never prompts the participant for a password.
     *
     * @param e the exception thrown while connecting
     * @return {@link ConnectionResult#AUTH_FAILED} for access-denied, else {@link ConnectionResult#UNREACHABLE}
     */
    static ConnectionResult classify(SQLException e) {
        if (e == null) return ConnectionResult.UNREACHABLE;
        if ("28000".equals(e.getSQLState()) || e.getErrorCode() == 1045) {
            return ConnectionResult.AUTH_FAILED;
        }
        return ConnectionResult.UNREACHABLE;
    }

    /**
     * Probes whether the given credentials can authenticate against the database, on a short-lived
     * connection that fails fast when the host is unreachable.
     * <p>
     * Returns a three-state {@link ConnectionResult} so callers can tell a rejected password
     * ({@link ConnectionResult#AUTH_FAILED}) from a down/unreachable server
     * ({@link ConnectionResult#UNREACHABLE}). Connects on its own short-lived connection with
     * bounded {@code connectTimeout}/{@code socketTimeout}, leaving the shared {@link #connection}
     * used by uploads untouched, so a probe can run during a sync without disturbing it. The
     * password and connection string are never logged.
     *
     * @param timeoutSeconds maximum time to spend connecting
     * @return {@link ConnectionResult#OK} if the credentials authenticate, otherwise the classified failure
     */
    public static ConnectionResult probeConnection(String host, String port, String name,
                                                   String username, String password, int timeoutSeconds) {
        int timeoutMs = timeoutSeconds * 1000;
        String connectionUrl = String.format(
                "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d",
                host, port, name, timeoutMs, timeoutMs);

        Connection localConnection = null;
        try {
            Class.forName("com.mysql.jdbc.Driver");
            localConnection = DriverManager.getConnection(connectionUrl, username, password);
            return ConnectionResult.OK;
        } catch (SQLException e) {
            ConnectionResult result = classify(e);
            Log.i(TAG, "Database probe result: " + result);
            return result;
        } catch (Exception e) {
            // Driver load or other unexpected failure: treat as unreachable so we never prompt.
            Log.i(TAG, "Database probe result: " + ConnectionResult.UNREACHABLE);
            return ConnectionResult.UNREACHABLE;
        } finally {
            try {
                if (localConnection != null && !localConnection.isClosed()) localConnection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Inserts data into a remote database table.
     *
     * @param context application context
     * @param table name of table to insert data into
     * @param rows list of the rows of data to insert
     * @return true if the data is inserted successfully, false otherwise
     */
    public static boolean insertData(Context context, String table, JSONArray rows) {
        if (rows.length() == 0) return true;

        try {
            Jdbc.transactionCount++;
            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }
            Jdbc.insertBatch(context, table, fields, rows);
        } catch (JSONException | SQLException | JdbcConnectionException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Best-effort, single-shot insert on a short-lived connection that fails fast when the host is
     * unreachable.
     * <p>
     * Unlike {@link #insertData}, this does NOT reuse the shared sync connection and adds bounded
     * {@code connectTimeout}/{@code socketTimeout} query parameters, so an unreachable database
     * fails within roughly {@code timeoutSeconds} instead of hanging on the default TCP timeout.
     * It is used by the study-exit notification: leaving a study must stay responsive and must
     * never be blocked by an unreachable research database.
     *
     * @param context        application context
     * @param table          name of the remote table to insert into
     * @param rows           rows to insert
     * @param timeoutSeconds maximum time to spend connecting/talking to the database
     * @return true if the database acknowledged the insert; false if it could not be reached or the
     *         insert failed. Callers must treat false as "not notified", never as "leave failed".
     */
    public static boolean insertDataFastFail(Context context, String table, JSONArray rows, int timeoutSeconds) {
        if (rows.length() == 0) return true;

        int timeoutMs = timeoutSeconds * 1000;
        String connectionUrl = String.format(
                "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d",
                Aware.getSetting(context, Aware_Preferences.DB_HOST),
                Aware.getSetting(context, Aware_Preferences.DB_PORT),
                Aware.getSetting(context, Aware_Preferences.DB_NAME),
                timeoutMs, timeoutMs);

        Connection localConnection = null;
        try {
            Class.forName("com.mysql.jdbc.Driver");
            localConnection = DriverManager.getConnection(connectionUrl,
                    Aware.getSetting(context, Aware_Preferences.DB_USERNAME),
                    Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));

            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }

            List<String> fieldsWithBacktick = new ArrayList<>();  // in case of reserved keywords
            List<Character> sqlParamPlaceholder = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                fieldsWithBacktick.add("`" + fields.get(i) + "`");
                sqlParamPlaceholder.add('?');
            }

            String sqlStatement = String.format("INSERT INTO %s (%s) VALUES (%s)", table,
                    TextUtils.join(",", fieldsWithBacktick),
                    TextUtils.join(",", sqlParamPlaceholder));
            PreparedStatement ps = localConnection.prepareStatement(sqlStatement);

            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                int paramIndex = 1;
                for (String field : fields) {
                    ps.setString(paramIndex, row.getString(field));
                    paramIndex++;
                }
                ps.addBatch();
            }

            ps.executeBatch();
            Log.i(TAG, "Study-exit notification acknowledged by remote table '" + table + "'");
            return true;
        } catch (Exception e) {
            // Do not log the connection string/credentials; the host is enough to diagnose.
            Log.w(TAG, "Study-exit notification could not reach the research database: " + e.getMessage());
            return false;
        } finally {
            try {
                if (localConnection != null && !localConnection.isClosed()) localConnection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Establish a connection to the database of the currently joined study.
     * @param context application context
     */
    private static void connect(Context context) throws JdbcConnectionException {
        String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?rewriteBatchedStatements=true",
                Aware.getSetting(context, Aware_Preferences.DB_HOST),
                Aware.getSetting(context, Aware_Preferences.DB_PORT),
                Aware.getSetting(context, Aware_Preferences.DB_NAME));
        Log.i(TAG, "Establishing connection to remote database...");

        try {
            Class.forName("com.mysql.jdbc.Driver");

            connection = DriverManager.getConnection(connectionUrl,
                    Aware.getSetting(context, Aware_Preferences.DB_USERNAME),
                    Aware.getSetting(context, Aware_Preferences.DB_PASSWORD));
            Log.i(TAG, "Connected to remote database...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish connection to database, reason: " + e.getMessage());
            e.printStackTrace();
            throw new JdbcConnectionException(e.getMessage());
        }
    }

    /**
     * Closes the current database connection.
     */
    private static void disconnect() {
        try {
            Log.i(TAG, "Closing connection to remote database...");
            if (connection != null && !connection.isClosed()) Jdbc.connection.close();
            Log.i(TAG, "Closed connection to remote database.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Batch inserts data into a remote database table.
     *
     * @param context application context
     * @param table name of table to batch insert data into
     * @param fields list of the table fields
     * @param rows list of the rows of data to insert
     * @throws JdbcConnectionException
     * @throws JSONException
     */
    private static synchronized void insertBatch(
        Context context,
        String table,
        List<String> fields,
        JSONArray rows
    ) throws JdbcConnectionException, JSONException, SQLException {
        try {
            if (Jdbc.connection == null || Jdbc.connection.isClosed()) {
                Jdbc.transactionCount = 1; // reset transaction count if this is the first INSERT
                connect(context);
            }
            Log.i(TAG, "# " + Jdbc.transactionCount + " Inserting " + rows.length() +
                    " row(s) of data into remote table '" + table + "'...");

            List<String> fieldsWithBacktick = new ArrayList<>();  // in case of reserved keywords
            List<Character> sqlParamPlaceholder = new ArrayList<>();
            for (int i = 0; i < fields.size(); i ++) {
                fieldsWithBacktick.add("`" + fields.get(i) + "`");
                sqlParamPlaceholder.add('?');
            }

            String sqlStatement = String.format("INSERT INTO %s (%s) VALUES (%s)", table,
                    TextUtils.join(",", fieldsWithBacktick),
                    TextUtils.join(",", sqlParamPlaceholder));
            PreparedStatement ps = Jdbc.connection.prepareStatement(sqlStatement);

            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                int paramIndex = 1;

                for (String field: fields) {
                    ps.setString(paramIndex, row.getString(field));
                    paramIndex++;
                }
                ps.addBatch();
            }

            ps.executeBatch();
            Log.i(TAG, "Inserted " + rows.length() + " row(s) of data into remote table '" + table);
        } finally {
            Jdbc.transactionCount--;
            if (Jdbc.transactionCount == 0) disconnect();
        }
    }
}
