package com.jobscraper.db;

import com.jobscraper.model.Job;
import com.jobscraper.model.Portal;
import com.jobscraper.model.Subscriber;
import com.jobscraper.util.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages all database interactions with Supabase (PostgreSQL).
 * Creates tables if they do not exist, and provides full CRUD for portals/jobs.
 */
public class DatabaseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;

    // ------------------------------------------------------------------
    // DDL — table definitions
    // ------------------------------------------------------------------
    private static final String CREATE_PORTALS_TABLE = """
            CREATE TABLE IF NOT EXISTS portals (
                id             SERIAL PRIMARY KEY,
                portal         VARCHAR(100) NOT NULL UNIQUE,
                link           TEXT         NOT NULL,
                recent_job_id  VARCHAR(50),
                workday        BOOLEAN DEFAULT true
            );
            """;

    private static final String ALTER_PORTALS_ADD_WORKDAY = """
            ALTER TABLE portals ADD COLUMN IF NOT EXISTS workday BOOLEAN DEFAULT true;
            """;

    private static final String CREATE_JOBS_TABLE = """
            CREATE TABLE IF NOT EXISTS jobs (
                id         SERIAL PRIMARY KEY,
                req_id     VARCHAR(50)  NOT NULL,
                portal_id  INTEGER      NOT NULL REFERENCES portals(id) ON DELETE CASCADE,
                position   TEXT,
                location   TEXT,
                job_url    TEXT,
                scraped_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                UNIQUE(req_id, portal_id)
            );
            """;

    private static final String CREATE_SUBSCRIBERS_TABLE = """
            CREATE TABLE IF NOT EXISTS subscribers (
                id    SERIAL PRIMARY KEY,
                name  TEXT,
                email TEXT NOT NULL UNIQUE
            );
            """;

    private static final String CREATE_IDX_JOBS_REQ = """
            CREATE INDEX IF NOT EXISTS idx_jobs_req_id ON jobs(req_id);
            """;

    private static final String CREATE_IDX_JOBS_PORTAL = """
            CREATE INDEX IF NOT EXISTS idx_jobs_portal_id ON jobs(portal_id);
            """;

    // ------------------------------------------------------------------
    // Constructor — establish connection pool
    // ------------------------------------------------------------------

    public DatabaseManager() {
        log.info("Initialising database connection pool...");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(AppConfig.getDbUrl());
        config.setUsername(AppConfig.getDbUser());
        config.setPassword(AppConfig.getDbPassword());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setPoolName("ScraperPool");

        // Supabase / PgBouncer friendly settings
        config.addDataSourceProperty("prepareThreshold", "0");
        config.addDataSourceProperty("ApplicationName", "WorkdayJobScraper");

        dataSource = new HikariDataSource(config);
        log.info("Database connection pool established successfully.");
    }

    // ------------------------------------------------------------------
    // Schema initialisation
    // ------------------------------------------------------------------

    /**
     * Creates all required tables and indexes if they do not already exist.
     * Safe to call on every application start.
     */
    public void initSchema() {
        log.info("Initialising database schema...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(CREATE_PORTALS_TABLE);
            stmt.execute(ALTER_PORTALS_ADD_WORKDAY);
            log.info("  ✔ portals table ready");

            stmt.execute(CREATE_JOBS_TABLE);
            log.info("  ✔ jobs table ready");

            stmt.execute(CREATE_SUBSCRIBERS_TABLE);
            log.info("  ✔ subscribers table ready");

            stmt.execute(CREATE_IDX_JOBS_REQ);
            stmt.execute(CREATE_IDX_JOBS_PORTAL);
            log.info("  ✔ indexes ready");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise database schema", e);
        }
        log.info("Schema initialisation complete.");
    }

    // ------------------------------------------------------------------
    // Portals
    // ------------------------------------------------------------------

    /**
     * Fetches all rows from the portals table.
     */
    public List<Portal> fetchAllPortals() {
        String sql = "SELECT id, portal, link, recent_job_id, workday FROM portals ORDER BY id";
        List<Portal> portals = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Boolean w = (Boolean) rs.getObject("workday");
                boolean workday = w == null || w;
                portals.add(new Portal(
                        rs.getInt("id"),
                        workday,
                        rs.getString("portal"),
                        rs.getString("link"),
                        rs.getString("recent_job_id")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch portals", e);
        }

        log.info("Fetched {} portal(s) from database", portals.size());
        return portals;
    }

    /**
     * Updates recent_job_id for the given portal row.
     */
    public void updateRecentJobId(int portalId, String reqId) {
        String sql = "UPDATE portals SET recent_job_id = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, reqId);
            ps.setInt(2, portalId);
            int rows = ps.executeUpdate();
            log.info("Updated recent_job_id='{}' for portal id={} ({} row(s) affected)",
                    reqId, portalId, rows);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to update recent_job_id for portal " + portalId, e);
        }
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    /**
     * Inserts a job, ignoring duplicates (same req_id + portal_id).
     * Returns true if a new row was inserted, false if it already existed.
     */
    public boolean insertJob(Job job) {
        String sql = """
                INSERT INTO jobs (req_id, portal_id, position, location, job_url)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (req_id, portal_id) DO NOTHING
                """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, job.getReqId());
            ps.setInt(2, job.getPortalId());
            ps.setString(3, job.getPosition());
            ps.setString(4, job.getLocation());
            ps.setString(5, job.getJobUrl());

            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.debug("Inserted job: req_id={}, position='{}'", job.getReqId(), job.getPosition());
                return true;
            } else {
                log.debug("Job already exists (skipped): req_id={}", job.getReqId());
                return false;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert job " + job.getReqId(), e);
        }
    }

    // ------------------------------------------------------------------
    // Subscribers
    // ------------------------------------------------------------------

    /**
     * Fetches all subscribers (name + email) from the subscribers table.
     */
    public List<Subscriber> fetchAllSubscribers() {
        String sql = "SELECT name, email FROM subscribers ORDER BY id";
        List<Subscriber> subscribers = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                subscribers.add(new Subscriber(
                        rs.getString("name"),
                        rs.getString("email")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to fetch subscribers", e);
        }

        log.info("Fetched {} subscriber(s) from database", subscribers.size());
        return subscribers;
    }

    /**
     * Batch-inserts a list of jobs. Returns the count of newly inserted rows.
     */
    public int insertJobs(List<Job> jobs) {
        if (jobs == null || jobs.isEmpty()) return 0;

        String sql = """
                INSERT INTO jobs (req_id, portal_id, position, location, job_url)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (req_id, portal_id) DO NOTHING
                """;

        int inserted = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (Job job : jobs) {
                ps.setString(1, job.getReqId());
                ps.setInt(2, job.getPortalId());
                ps.setString(3, job.getPosition());
                ps.setString(4, job.getLocation());
                ps.setString(5, job.getJobUrl());
                ps.addBatch();
            }

            int[] results = ps.executeBatch();
            conn.commit();

            for (int r : results) {
                if (r > 0) inserted++;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Batch insert failed", e);
        }

        log.info("Batch inserted {}/{} jobs", inserted, jobs.size());
        return inserted;
    }

    /**
     * Checks whether a job with the given req_id already exists for this portal.
     */
    public boolean jobExists(String reqId, int portalId) {
        String sql = "SELECT 1 FROM jobs WHERE req_id = ? AND portal_id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, reqId);
            ps.setInt(2, portalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check job existence for req_id=" + reqId, e);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database connection pool closed.");
        }
    }
}