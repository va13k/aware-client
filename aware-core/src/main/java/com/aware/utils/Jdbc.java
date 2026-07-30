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
import java.sql.SQLWarning;
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
     *
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
     * Summarises a chain of {@link SQLWarning}s into one log-safe line, or null when the chain is
     * empty.
     *
     * The count matters as much as the text: MySQL reports one warning per offending row, so "1"
     * and "4000" distinguish a single odd value from a column whose type no longer matches what the
     * client sends. Pure and side-effect free so it can be unit-tested without a database.
     *
     * @param warning head of the warning chain, or null
     * @return a summary such as {@code 2 warning(s); first: [01000/1265] Data truncated}, or null
     */
    static String describeWarnings(SQLWarning warning) {
        if (warning == null) return null;

        int count = 0;
        SQLWarning current = warning;
        // A driver that chains a warning to itself would otherwise spin here forever.
        while (current != null && count < 1000) {
            count++;
            SQLWarning next = current.getNextWarning();
            if (next == current) break;
            current = next;
        }

        return count + " warning(s); first: [" + warning.getSQLState() + "/"
                + warning.getErrorCode() + "] " + warning.getMessage();
    }

    /**
     * Probes whether the given credentials can authenticate against the database, on a short-lived
     * connection that fails fast when the host is unreachable.
     *
     * Returns a three-state {@link ConnectionResult} so callers can tell a rejected password
     * ({@link ConnectionResult#AUTH_FAILED}) from a down/unreachable server
     * ({@link ConnectionResult#UNREACHABLE}). Connects on its own short-lived connection with
     * bounded {@code connectTimeout}/{@code socketTimeout}, leaving the shared {@link #connection}
     * used by uploads untouched, so a probe can run during a sync without disturbing it. The
     * password and connection string are never logged.
     *
     * @param context        application context, for the trust store behind {@link MysqlTls}
     * @param timeoutSeconds maximum time to spend connecting
     * @return {@link ConnectionResult#OK} if the credentials authenticate, otherwise the classified failure
     */
    public static ConnectionResult probeConnection(Context context, String host, String port,
                                                   String name, String username, String password,
                                                   int timeoutSeconds) {
        int timeoutMs = timeoutSeconds * 1000;

        Connection localConnection = null;
        try {
            String connectionUrl = String.format(
                    "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d%s",
                    host, port, name, timeoutMs, timeoutMs,
                    MysqlTls.connectionParameters(context));
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
            List<String> fields = new ArrayList<>();
            Iterator<String> fieldIterator = rows.getJSONObject(0).keys();
            while (fieldIterator.hasNext()) {
                fields.add(fieldIterator.next());
            }
            // Claim a reference only once nothing else here can throw: insertBatch's finally is what
            // releases it, so anything that fails between the two would leave the shared connection
            // referenced by a caller that has gone away, and never closed.
            Jdbc.transactionCount++;
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
     *
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

        Connection localConnection = null;
        try {
            String connectionUrl = String.format(
                    "jdbc:mysql://%s:%s/%s?connectTimeout=%d&socketTimeout=%d%s",
                    Aware.getSetting(context, Aware_Preferences.DB_HOST),
                    Aware.getSetting(context, Aware_Preferences.DB_PORT),
                    Aware.getSetting(context, Aware_Preferences.DB_NAME),
                    timeoutMs, timeoutMs,
                    MysqlTls.connectionParameters(context));
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
        Log.i(TAG, "Establishing connection to remote database...");

        try {
            String connectionUrl = String.format(
                    "jdbc:mysql://%s:%s/%s?rewriteBatchedStatements=true%s",
                    Aware.getSetting(context, Aware_Preferences.DB_HOST),
                    Aware.getSetting(context, Aware_Preferences.DB_PORT),
                    Aware.getSetting(context, Aware_Preferences.DB_NAME),
                    MysqlTls.connectionParameters(context));
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

            // The batch lands all-or-nothing. rewriteBatchedStatements=true has the driver merge the
            // batch into several multi-row INSERTs; under autocommit each of those commits on its
            // own, so a failure part-way leaves the earlier chunks stored server-side while the
            // phone — which only learns "the batch failed" — keeps every local row and retries the
            // whole batch, duplicating them. The client's MySQL user has INSERT only, so such
            // duplicates can never be removed afterwards. Requires the target table to be InnoDB;
            // MyISAM silently ignores transactions.
            boolean autoCommitWas = Jdbc.connection.getAutoCommit();
            Jdbc.connection.setAutoCommit(false);
            try (PreparedStatement ps = Jdbc.connection.prepareStatement(sqlStatement)) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    int paramIndex = 1;

                    for (String field: fields) {
                        ps.setString(paramIndex, row.getString(field));
                        paramIndex++;
                    }
                    ps.addBatch();
                }

                // The connection is shared across batches and accumulates warnings, so clear it
                // first: what is read below has to belong to this batch and no earlier one.
                Jdbc.connection.clearWarnings();
                ps.executeBatch();

                // Every value is bound with setString(), including numeric and double_* columns, so
                // MySQL implicitly converts each one. A server in strict mode raises an error on a
                // bad conversion, but a non-strict server (or a MyISAM table, where strict mode
                // degrades to warnings for multi-row inserts) accepts it as a warning and stores
                // something other than what was sent. Report that rather than call it a clean
                // upload; the batch is still committed, because refusing to advance the sync marker
                // over a warning would wedge the table into retrying the same rows forever.
                String warnings = describeWarnings(ps.getWarnings());
                if (warnings == null) warnings = describeWarnings(Jdbc.connection.getWarnings());
                if (warnings != null) {
                    Log.w(TAG, "Remote table '" + table + "' accepted the insert with " + warnings);
                }

                Jdbc.connection.commit();
            } catch (SQLException e) {
                try {
                    Jdbc.connection.rollback();
                } catch (SQLException rollbackFailed) {
                    Log.e(TAG, "Rollback of the failed batch for '" + table + "' did not complete: "
                            + rollbackFailed.getMessage());
                }
                throw e;
            } finally {
                try {
                    Jdbc.connection.setAutoCommit(autoCommitWas);
                } catch (SQLException ignored) {
                }
            }
            Log.i(TAG, "Inserted " + rows.length() + " row(s) of data into remote table '" + table);
        } finally {
            Jdbc.transactionCount--;
            if (Jdbc.transactionCount == 0) disconnect();
        }
    }
}
