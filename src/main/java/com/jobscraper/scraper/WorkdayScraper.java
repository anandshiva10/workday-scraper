package com.jobscraper.scraper;

import com.jobscraper.db.DatabaseManager;
import com.jobscraper.model.Job;
import com.jobscraper.model.Portal;
import com.jobscraper.model.Subscriber;
import com.jobscraper.util.AppConfig;
import com.jobscraper.util.EmailService;
import com.jobscraper.util.RobotsChecker;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Scrapes Workday job portals using Selenium.
 *
 * Flow per portal:
 *  1. Check robots.txt
 *  2. Navigate to portal URL
 *  3. Iterate pages (max 10) extracting jobs from the jobResults section
 *  4. Stop early if a job's req_id matches portal.recentJobId
 *  5. Save jobs to DB; update portal.recentJobId with the first (newest) job found
 */
public class WorkdayScraper {

    private static final Logger log = LoggerFactory.getLogger(WorkdayScraper.class);

    // CSS / automation-id selectors derived from the page source
    private static final String SEL_JOB_RESULTS   = "[data-automation-id='jobResults']";
    private static final String SEL_JOB_LIST_ITEMS = "[data-automation-id='jobResults'] ul > li";
    private static final String SEL_JOB_TITLE_LINK = "h3 a[data-automation-id='jobTitle']";
    private static final String SEL_LOCATION_DD    = "[data-automation-id='locations'] dl dd";
    private static final String SEL_REQ_ID         = "ul[data-automation-id='subtitle'] li";
    private static final String SEL_NEXT_BTN       = "[data-uxi-widget-type='stepToNextButton']";
    private static final String SEL_PAGE_ARIA      = "[aria-current='page']";

    private final DatabaseManager db;
    private final AkkodisScraper akkodisScraper;
    private final int maxPages;
    private final int delayMin;
    private final int delayMax;
    private final boolean headless;
    private final Random random = new Random();
    private final EmailService emailService;

    private WebDriver driver;
    private WebDriverWait wait;

    public WorkdayScraper(DatabaseManager db) {
        this.db = db;
        this.akkodisScraper = new AkkodisScraper(db);
        this.maxPages = AppConfig.getMaxPages();
        this.delayMin = AppConfig.getPageDelayMinMs();
        this.delayMax = AppConfig.getPageDelayMaxMs();
        this.headless = AppConfig.isHeadless();
        this.emailService = new EmailService();
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Runs the full scrape cycle for all portals fetched from the database.
     */
    public void run() {
        List<Portal> portals = db.fetchAllPortals();

        if (portals.isEmpty()) {
            log.warn("No portals found in the database. Add rows to the portals table and re-run.");
            return;
        }

        log.info("Starting scrape cycle for {} portal(s)", portals.size());
        initDriver();

        List<Job> allNewJobs = new ArrayList<>();

        try {
            for (Portal portal : portals) {
                log.info("=".repeat(60));
                log.info("Processing portal: {} → {}", portal.getPortal(), portal.getLink());
                try {
                    List<Job> portalNewJobs;
                    if (isAkkodisPortal(portal)) {
                        if (portal.getWorkday()) {
                            log.warn("  Portal '{}' looks like Akkodis but workday=true. Routing to AkkodisScraper.",
                                    portal.getPortal());
                        }
                        portalNewJobs = akkodisScraper.scrapePortal(driver, wait, portal);
                    } else if (portal.getWorkday()) {
                        portalNewJobs = scrapePortal(portal);
                    } else {
                        log.info("  Skipping portal '{}': workday=false and no non-Workday scraper available.", portal.getPortal());
                        portalNewJobs = List.of();
                    }
                    allNewJobs.addAll(portalNewJobs);
                } catch (Exception e) {
                    log.error("Error scraping portal '{}': {}", portal.getPortal(), e.getMessage(), e);
                }
                // Polite gap between portals
                humanDelay(3000, 6000);
            }
        } finally {
            quitDriver();
        }

        if (!allNewJobs.isEmpty()) {
            List<Subscriber> subscribers = db.fetchAllSubscribers();
            emailService.sendNewJobsEmail(subscribers, allNewJobs);
        } else {
            log.info("No new jobs detected across all portals; no email sent.");
        }

        log.info("=".repeat(60));
        log.info("Scrape cycle complete.");
    }

    // ------------------------------------------------------------------
    // Per-portal scraping
    // ------------------------------------------------------------------

    private List<Job> scrapePortal(Portal portal) {
        // 1. Robots check
        if (!RobotsChecker.isAllowed(portal.getLink())) {
            log.warn("robots.txt disallows scraping '{}'. Skipping.", portal.getLink());
            return List.of();
        }

        // 2. Navigate to portal
        driver.get(portal.getLink());
        humanDelay(2500, 4500);

        // 3. Wait for job results section to appear
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(SEL_JOB_RESULTS)));
        } catch (TimeoutException e) {
            log.error("Timed out waiting for job results on '{}'. Page may not have loaded.", portal.getLink());
            return List.of();
        }

        List<Job> newJobsThisPortal = new ArrayList<>();
        String    firstJobReqId     = null;   // tracks the newest job (first on first page)
        boolean   stopEarly         = false;

        for (int page = 1; page <= maxPages; page++) {
            log.info("  Page {}/{} — portal: {}", page, maxPages, portal.getPortal());

            List<Job> pageJobs = scrapeCurrentPage(portal);

            if (pageJobs.isEmpty()) {
                log.info("  No jobs found on page {}. Stopping.", page);
                break;
            }

            // Capture the first job on the very first page as the new "recent" marker
            if (page == 1 && !pageJobs.isEmpty()) {
                firstJobReqId = pageJobs.get(0).getReqId();
                log.info("  First job on page 1: req_id={}", firstJobReqId);
            }

            // Check each job against the stored recent_job_id
            for (Job job : pageJobs) {
                if (job.getReqId() != null
                        && job.getReqId().equals(portal.getRecentJobId())) {
                    log.info("  Reached known job req_id='{}'. Stopping early.", job.getReqId());
                    stopEarly = true;
                    break;
                }
                // Only treat as new if it does not already exist in the DB
                if (!db.jobExists(job.getReqId(), portal.getId())) {
                    newJobsThisPortal.add(job);
                }
            }

            if (stopEarly) break;

            // Try navigating to next page
            if (!navigateToNextPage()) {
                log.info("  No more pages available.");
                break;
            }

            humanDelay(delayMin, delayMax);
        }

        // 4. Persist newly detected jobs
        if (!newJobsThisPortal.isEmpty()) {
            int inserted = db.insertJobs(newJobsThisPortal);
            log.info("  Saved {}/{} new job(s) for portal '{}'",
                    inserted, newJobsThisPortal.size(), portal.getPortal());
        } else {
            log.info("  No new jobs to save for portal '{}'.", portal.getPortal());
        }

        // 5. Update recent_job_id with the first (newest) job from this run
        if (firstJobReqId != null) {
            db.updateRecentJobId(portal.getId(), firstJobReqId);
            portal.setRecentJobId(firstJobReqId);
        }

        return newJobsThisPortal;
    }

    // ------------------------------------------------------------------
    // Page-level job extraction
    // ------------------------------------------------------------------

    private List<Job> scrapeCurrentPage(Portal portal) {
        List<Job> jobs = new ArrayList<>();

        List<WebElement> listItems;
        try {
            listItems = driver.findElements(By.cssSelector(SEL_JOB_LIST_ITEMS));
        } catch (Exception e) {
            log.warn("  Could not find job list items: {}", e.getMessage());
            return jobs;
        }

        log.debug("  Found {} <li> items on current page", listItems.size());

        for (WebElement li : listItems) {
            try {
                Job job = extractJobFromListItem(li, portal);
                if (job != null) {
                    jobs.add(job);
                }
            } catch (StaleElementReferenceException e) {
                log.warn("  Stale element encountered while parsing a job listing, skipping.");
            } catch (Exception e) {
                log.warn("  Error parsing job listing: {}", e.getMessage());
            }
        }

        log.info("  Extracted {} job(s) from current page", jobs.size());
        return jobs;
    }

    /**
     * Parses a single <li> element into a Job object.
     *
     * HTML structure (from source analysis):
     *
     *   <li>
     *     <div>                              ← wrapper div [0]
     *       <div><div><h3><a ...>            ← job link & title   (SEL_JOB_TITLE_LINK)
     *     <div>                              ← wrapper div [1]
     *       <div><div>
     *         <dl><dd>                       ← location           (SEL_LOCATION_DD)
     *     <ul data-automation-id="subtitle">
     *       <li>                             ← req id             (SEL_REQ_ID)
     */
    private Job extractJobFromListItem(WebElement li, Portal portal) {
        Job job = new Job();
        job.setPortalId(portal.getId());
        job.setPortalName(portal.getPortal());

        // --- Title & URL ---
        try {
            WebElement anchor = li.findElement(By.cssSelector(SEL_JOB_TITLE_LINK));
            job.setPosition(anchor.getText().trim());
            job.setJobUrl(anchor.getAttribute("href"));
        } catch (NoSuchElementException e) {
            log.debug("  No title/link found in list item, skipping.");
            return null;
        }

        // --- Location ---
        try {
            WebElement locationEl = li.findElement(By.cssSelector(SEL_LOCATION_DD));
            job.setLocation(locationEl.getText().trim());
        } catch (NoSuchElementException e) {
            log.debug("  No location element found for job '{}'", job.getPosition());
            job.setLocation(null);
        }

        // --- Req ID ---
        try {
            WebElement reqEl = li.findElement(By.cssSelector(SEL_REQ_ID));
            // The text is the raw req id, e.g. "202603061"
            job.setReqId(reqEl.getText().trim());
        } catch (NoSuchElementException e) {
            log.debug("  No req_id element found for job '{}'", job.getPosition());
            // Try to parse from URL as fallback
            job.setReqId(parseReqIdFromUrl(job.getJobUrl()));
        }

        if (job.getReqId() == null || job.getReqId().isBlank()) {
            log.warn("  Could not determine req_id for job '{}', skipping.", job.getPosition());
            return null;
        }

        return job;
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    /**
     * Clicks the "Next" button and waits for the new page to load.
     * Returns false if the button is absent or disabled.
     */
    private boolean navigateToNextPage() {
        try {
            WebElement nextBtn = driver.findElement(By.cssSelector(SEL_NEXT_BTN));

            // Check disabled attribute or aria-disabled
            String disabled = nextBtn.getAttribute("disabled");
            String ariaDisabled = nextBtn.getAttribute("aria-disabled");
            if ("true".equals(disabled) || "true".equals(ariaDisabled)) {
                return false;
            }

            // Capture current page indicator text to detect change
            String currentPageText = getCurrentPageText();

            // Scroll button into view and click
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", nextBtn);
            humanDelay(300, 700);
            nextBtn.click();

            // Wait until the page indicator changes (DOM update signal)
            waitForPageChange(currentPageText);
            return true;

        } catch (NoSuchElementException e) {
            return false;
        } catch (TimeoutException e) {
            log.warn("  Timeout waiting for page change after clicking Next.");
            return false;
        }
    }

    private String getCurrentPageText() {
        try {
            return driver.findElement(By.cssSelector(SEL_PAGE_ARIA)).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private void waitForPageChange(String previousPageText) {
        try {
            // Wait until job results section goes stale OR page indicator changes
            WebElement oldResults = driver.findElement(By.cssSelector(SEL_JOB_RESULTS));
            wait.until(ExpectedConditions.stalenessOf(oldResults));
        } catch (Exception e) {
            // Fallback: wait for page aria-current to differ
            try {
                wait.until(driver -> {
                    String current = getCurrentPageText();
                    return !current.equals(previousPageText) && !current.isEmpty();
                });
            } catch (TimeoutException te) {
                // Accept current state after timeout
            }
        }

        // Always wait for new job results to appear
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector(SEL_JOB_LIST_ITEMS)));
        } catch (TimeoutException e) {
            log.warn("  New page job items did not appear within timeout.");
        }
    }

    // ------------------------------------------------------------------
    // WebDriver setup / teardown
    // ------------------------------------------------------------------

    private void initDriver() {
        log.info("Initialising Chrome WebDriver (headless={})...", headless);
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--window-size=1920,1080",
                "--disable-extensions",
                "--disable-gpu"
        );
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        driver = new ChromeDriver(options);
        wait   = new WebDriverWait(driver, Duration.ofSeconds(20));

        // Remove navigator.webdriver flag
        ((JavascriptExecutor) driver).executeScript(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

        log.info("WebDriver ready.");
    }

    private void quitDriver() {
        if (driver != null) {
            try {
                driver.quit();
                log.info("WebDriver closed.");
            } catch (Exception e) {
                log.warn("Error closing WebDriver: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Sleeps for a random duration between min and max milliseconds.
     */
    private void humanDelay(int minMs, int maxMs) {
        try {
            int delay = minMs + random.nextInt(Math.max(1, maxMs - minMs));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Attempts to parse the req_id from the job URL as a fallback.
     * Workday URLs often end with _<reqId> e.g. /job/Store1046/Title_202603061
     */
    private String parseReqIdFromUrl(String url) {
        if (url == null) return null;
        // Match trailing numeric segment after last underscore
        int lastUnderscore = url.lastIndexOf('_');
        if (lastUnderscore >= 0 && lastUnderscore < url.length() - 1) {
            String candidate = url.substring(lastUnderscore + 1)
                    .replaceAll("[^0-9]", "");
            if (!candidate.isEmpty()) return candidate;
        }
        return null;
    }

    private boolean isAkkodisPortal(Portal portal) {
        String portalName = portal.getPortal() == null ? "" : portal.getPortal().toLowerCase();
        String link = portal.getLink() == null ? "" : portal.getLink().toLowerCase();
        return portalName.contains("akkodis") || link.contains("akkodis.com");
    }
}
