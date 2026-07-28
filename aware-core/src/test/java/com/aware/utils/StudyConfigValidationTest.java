package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the pure half of study-config validation: the schema and password-presence checks
 * that run before any database connection is attempted.
 *
 * These exist because the join screen's participant-facing message depends on telling the failures
 * apart — "the researcher's config is broken" (nothing the participant can do), "you still need to
 * type the study password", "the password is wrong", and "the database is unreachable" used to
 * collapse into one boolean, so a down server was reported as "Password not correct". The
 * connection-dependent half (AUTH_FAILED vs UNREACHABLE) is covered by {@link JdbcClassifyTest}.
 */
public class StudyConfigValidationTest {

    /** A config that passes every check made without touching the network. */
    private static JSONObject validConfig() throws JSONException {
        JSONObject config = new JSONObject();
        for (String key : new String[]{"questions", "schedules", "sensors", "study_info"}) {
            config.put(key, new JSONObject());
        }
        config.put("database", database());
        return config;
    }

    private static JSONObject database() throws JSONException {
        JSONObject db = new JSONObject();
        db.put("database_host", "db.example.org");
        db.put("database_port", "3306");
        db.put("database_name", "study");
        db.put("database_username", "participant");
        db.put("database_password", "from-config");
        return db;
    }

    @Test
    public void completeConfigIsMissingNothing() throws JSONException {
        assertNull(StudyUtils.firstMissingRequirement(validConfig()));
    }

    @Test
    public void nullConfigReportsTheConfigItself() {
        assertEquals("study configuration", StudyUtils.firstMissingRequirement(null));
    }

    @Test
    public void emptyConfigReportsAMissingTopLevelKey() {
        // Which key is named first is REQUIRED_STUDY_CONFIG_KEYS' business; what matters is that an
        // empty config is rejected by name rather than reaching a connection attempt.
        assertNotNull(StudyUtils.firstMissingRequirement(new JSONObject()));
    }

    @Test
    public void missingTopLevelKeyIsReportedByName() throws JSONException {
        JSONObject config = validConfig();
        config.remove("sensors");
        assertEquals("sensors", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void databaseThatIsNotAnObjectIsRejected() throws JSONException {
        JSONObject config = validConfig();
        config.put("database", "jdbc:mysql://db.example.org:3306/study");
        assertEquals("database", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void missingDatabaseFieldIsReportedByName() throws JSONException {
        // Caught here rather than left to fail as a connection error, so "config is broken" stays
        // distinguishable from "server is down".
        for (String field : new String[]{"database_host", "database_port", "database_name",
                "database_username"}) {
            JSONObject config = validConfig();
            config.getJSONObject("database").remove(field);
            assertEquals(field, StudyUtils.firstMissingRequirement(config));
        }
    }

    @Test
    public void emptyDatabaseFieldCountsAsMissing() throws JSONException {
        JSONObject config = validConfig();
        config.getJSONObject("database").put("database_host", "");
        assertEquals("database_host", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void databasePasswordIsNotARequiredField() throws JSONException {
        // A password-join study ships no database_password at all; requiring it here would reject
        // every config_without_password=true study before the participant could type anything.
        JSONObject config = validConfig();
        config.getJSONObject("database").remove("database_password");
        assertNull(StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void configPasswordIsNotRequiredFromTheParticipant() throws JSONException {
        // config_without_password absent → the config carries its own password.
        assertFalse(StudyUtils.requiresParticipantPassword(validConfig()));
    }

    @Test
    public void explicitFalseDoesNotRequireAParticipantPassword() throws JSONException {
        JSONObject config = validConfig();
        config.getJSONObject("database").put("config_without_password", false);
        assertFalse(StudyUtils.requiresParticipantPassword(config));
    }

    @Test
    public void passwordJoinStudyRequiresAParticipantPassword() throws JSONException {
        JSONObject config = validConfig();
        config.getJSONObject("database").put("config_without_password", true);
        assertTrue(StudyUtils.requiresParticipantPassword(config));
    }

    @Test
    public void nullConfigNeverRequiresAPassword() {
        // Guards the re-auth path: an unfetched config must not be read as "ask the participant".
        assertFalse(StudyUtils.requiresParticipantPassword(null));
    }

    @Test
    public void configWithoutDatabaseNeverRequiresAPassword() {
        assertFalse(StudyUtils.requiresParticipantPassword(new JSONObject()));
    }
}
