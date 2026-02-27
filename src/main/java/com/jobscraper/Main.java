package com.jobscraper;

import com.jobscraper.db.DatabaseManager;
import com.jobscraper.scraper.WorkdayScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application entry point.
 *
 * Start-up sequence:
 *   1. Connect to Supabase
 *   2. Create tables if they don't exist
 *   3. Fetch portals from DB
 *   4. Scrape each portal (up to MAX_PAGES pages, stopping at known req_id)
 *   5. Persist new jobs; update recent_job_id per portal
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║      Workday Job Scraper  v1.0.0         ║");
        log.info("╚══════════════════════════════════════════╝");

        // Use try-with-resources so the connection pool is always closed
        try (DatabaseManager db = new DatabaseManager()) {

            // Step 1: Ensure schema exists
            db.initSchema();

            // Step 2: Run the scraper
            WorkdayScraper scraper = new WorkdayScraper(db);
            scraper.run();

            log.info("Application finished successfully.");

        } catch (IllegalStateException e) {
            // Configuration errors (missing env vars etc.)
            log.error("Configuration error: {}", e.getMessage());
            log.error("Please ensure your .env file is correctly configured. " +
                      "See .env.example for required keys.");
            System.exit(1);

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}