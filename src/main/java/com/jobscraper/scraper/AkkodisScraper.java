package com.jobscraper.scraper;

import com.jobscraper.db.DatabaseManager;
import com.jobscraper.model.Job;
import com.jobscraper.model.Portal;
import com.jobscraper.util.AppConfig;
import com.jobscraper.util.RobotsChecker;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes job listings from the Akkodis career portal.
 * Uses ul[class*='JobSearchResults_filter-results-container'] as the job list container.
 * Handles pagination via pagination controls.
 */
public class AkkodisScraper {

    private static final Logger log = LoggerFactory.getLogger(AkkodisScraper.class);

    // Container: ul with class containing JobSearchResults_filter-results-container
    private static final String SEL_JOB_CONTAINER = "ul[class*='JobSearchResults_filter-results-container']";
    private static final String SEL_JOB_ITEMS = "ul[class*='JobSearchResults_filter-results-container'] > li";
    private static final String SEL_JOB_LINK = "a[href*='/job']";
    private static final String SEL_PAGINATION = "[class*='pagination_pagination-list']";
    private static final String SEL_NEXT_PAGE_ARROW = "span[class*='pagination_pagination-right-arrow']";
    private static final String SEL_NEXT_PAGE_ARROW_ALT = "span[class*='pagination-right-arrow']";

    private final DatabaseManager db;
    private final int maxPages;
    private final int delayMin;
    private final int delayMax;
    private final Random random = new Random();

    private WebDriver driver;
    private WebDriverWait wait;

    public AkkodisScraper(DatabaseManager db) {
        this.db = db;
        this.maxPages = AppConfig.getMaxPages();
        this.delayMin = AppConfig.getPageDelayMinMs();
        this.delayMax = AppConfig.getPageDelayMaxMs();
    }

    /**
     * Scrapes the given Akkodis portal using the provided WebDriver.
     * Returns the list of newly detected jobs.
     */
    public List<Job> scrapePortal(WebDriver driver, WebDriverWait wait, Portal portal) {
        this.driver = driver;
        this.wait = wait;

        if (!RobotsChecker.isAllowed(portal.getLink())) {
            log.warn("robots.txt disallows scraping '{}'. Skipping.", portal.getLink());
            return List.of();
        }

        driver.get(portal.getLink());
        humanDelay(2500, 4500);

        List<Job> newJobsThisPortal = new ArrayList<>();
        String firstJobReqId = null;

        for (int page = 1; page <= maxPages; page++) {
            log.info("  Akkodis page {}/{} — portal: {}", page, maxPages, portal.getPortal());

            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(SEL_JOB_CONTAINER)));
            } catch (TimeoutException e) {
                log.warn("  Timed out waiting for job container on page {}. Stopping.", page);
                break;
            }

            List<Job> pageJobs = extractJobsFromCurrentPage(portal);

            if (pageJobs.isEmpty()) {
                log.info("  No jobs found on page {}. Stopping.", page);
                break;
            }

            if (page == 1 && !pageJobs.isEmpty()) {
                firstJobReqId = pageJobs.get(0).getReqId();
            }

            for (Job job : pageJobs) {
                if (job.getReqId() != null && job.getReqId().equals(portal.getRecentJobId())) {
                    log.info("  Reached known job req_id='{}'. Stopping early.", job.getReqId());
                    return persistAndReturn(newJobsThisPortal, portal, firstJobReqId);
                }
                if (!db.jobExists(job.getReqId(), portal.getId())) {
                    newJobsThisPortal.add(job);
                }
            }

            if (!navigateToNextPage()) {
                log.info("  No more Akkodis pages.");
                break;
            }

            humanDelay(delayMin, delayMax);
        }

        return persistAndReturn(newJobsThisPortal, portal, firstJobReqId);
    }

    private List<Job> persistAndReturn(List<Job> newJobs, Portal portal, String firstJobReqId) {
        if (!newJobs.isEmpty()) {
            int inserted = db.insertJobs(newJobs);
            log.info("  Saved {}/{} new Akkodis job(s) for portal '{}'",
                    inserted, newJobs.size(), portal.getPortal());
        } else {
            log.info("  No new Akkodis jobs to save for portal '{}'.", portal.getPortal());
        }
        if (firstJobReqId != null) {
            db.updateRecentJobId(portal.getId(), firstJobReqId);
            portal.setRecentJobId(firstJobReqId);
        }
        return newJobs;
    }

    private List<Job> extractJobsFromCurrentPage(Portal portal) {
        List<Job> jobs = new ArrayList<>();
        List<WebElement> items;

        try {
            items = driver.findElements(By.cssSelector(SEL_JOB_ITEMS));
        } catch (Exception e) {
            log.warn("  Could not find Akkodis job items: {}", e.getMessage());
            return jobs;
        }

        for (WebElement li : items) {
            try {
                Job job = extractJobFromItem(li, portal);
                if (job != null) {
                    jobs.add(job);
                }
            } catch (StaleElementReferenceException e) {
                log.warn("  Stale element in Akkodis listing, skipping.");
            } catch (Exception e) {
                log.warn("  Error parsing Akkodis job: {}", e.getMessage());
            }
        }

        log.info("  Extracted {} job(s) from current Akkodis page", jobs.size());
        return jobs;
    }

    /**
     * Extracts job details from a single list item.
     * Looks for: link (position + url), reference/req id, location.
     */
    private Job extractJobFromItem(WebElement item, Portal portal) {
        Job job = new Job();
        job.setPortalId(portal.getId());
        job.setPortalName(portal.getPortal());

        // --- Position & URL from main job link ---
        try {
            WebElement link = item.findElement(By.cssSelector(SEL_JOB_LINK));
            String linkText = safeText(link.getText());
            String itemText = safeText(item.getText());
            job.setPosition(extractPositionText(linkText, itemText));
            String href = link.getAttribute("href");
            job.setJobUrl(href);
            job.setReqId(parseReqIdFromUrl(href));
        } catch (NoSuchElementException e) {
            log.debug("  No job link in Akkodis item, skipping.");
            return null;
        }

        // --- Req ID: try dedicated element, else from URL ---
        if (job.getReqId() == null || job.getReqId().isBlank()) {
            job.setReqId(findReqIdInItem(item, job.getJobUrl()));
        }

        // --- Location ---
        String location = findLocationInItem(item);
        if (location == null || location.isBlank()) {
            location = extractLocationText(safeText(item.getText()));
        }
        job.setLocation(location);

        if (job.getReqId() == null || job.getReqId().isBlank()) {
            log.warn("  Could not determine req_id for job '{}', skipping.", job.getPosition());
            return null;
        }

        return job;
    }

    private String findReqIdInItem(WebElement item, String jobUrl) {
        // Try elements that might contain reference/req id
        String[] refSelectors = {
                "[class*='reference']", "[class*='ref']", "[class*='req']",
                "[class*='job-id']", "[data-automation-id*='req']", "[data-automation-id*='reference']"
        };
        for (String sel : refSelectors) {
            try {
                List<WebElement> els = item.findElements(By.cssSelector(sel));
                for (WebElement el : els) {
                    String text = el.getText().trim();
                    if (text != null && !text.isEmpty() && text.matches(".*\\d{5,}.*")) {
                        Matcher m = Pattern.compile("\\d{5,}").matcher(text);
                        if (m.find()) return m.group();
                    }
                }
            } catch (Exception ignored) {}
        }
        return parseReqIdFromUrl(jobUrl);
    }

    private String findLocationInItem(WebElement item) {
        String[] locSelectors = {
                "[class*='location']", "[class*='Location']",
                "[data-automation-id*='location']", "[class*='address']"
        };
        for (String sel : locSelectors) {
            try {
                WebElement el = item.findElement(By.cssSelector(sel));
                String text = extractLocationText(safeText(el.getText()));
                if (text != null && !text.isEmpty()) return text;
            } catch (NoSuchElementException ignored) {}
        }
        return null;
    }

    private String extractPositionText(String linkText, String itemText) {
        String candidate = firstNonBlank(linkText, itemText);
        if (candidate == null) return null;

        int refIdx = indexOfIgnoreCase(candidate, "Reference Number");
        if (refIdx > 0) {
            String title = candidate.substring(0, refIdx).trim();
            if (!title.isBlank()) return title;
        }

        int workIdx = indexOfIgnoreCase(candidate, "work_outline");
        if (workIdx > 0) {
            String title = candidate.substring(0, workIdx).trim();
            if (!title.isBlank()) return title;
        }

        int lineBreak = candidate.indexOf('\n');
        if (lineBreak > 0) {
            String title = candidate.substring(0, lineBreak).trim();
            if (!title.isBlank()) return title;
        }

        return candidate;
    }

    private String extractLocationText(String text) {
        if (text == null || text.isBlank()) return null;

        String normalized = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();

        Matcher placeMatcher = Pattern.compile("(?i)\\bplace\\s+(.+?)(?:\\s+calendar_today\\b|$)").matcher(normalized);
        if (placeMatcher.find()) {
            String location = placeMatcher.group(1).trim();
            if (!location.isBlank()) return location;
        }

        Matcher cityStateMatcher = Pattern.compile("\\b([A-Za-z][A-Za-z .'-]+,\\s*[A-Za-z][A-Za-z .'-]+)\\b").matcher(normalized);
        if (cityStateMatcher.find()) {
            return cityStateMatcher.group(1).trim();
        }

        return null;
    }

    private String safeText(String text) {
        if (text == null) return null;
        return text.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private int indexOfIgnoreCase(String text, String needle) {
        if (text == null || needle == null) return -1;
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private String parseReqIdFromUrl(String url) {
        if (url == null) return null;
        // Adecco/Akkodis URLs often end with /123456789 (numeric id)
        Matcher m = Pattern.compile("/(\\d{8,})\\b").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("/(\\d{5,})\\b").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private boolean navigateToNextPage() {
        try {
            String markerBefore = getFirstJobMarker();
            List<WebElement> nextArrows = findNextArrowCandidates();

            for (WebElement arrow : nextArrows) {
                try {
                    WebElement clickable = findClickableParent(arrow);
                    if (!isElementEnabled(clickable)) continue;

                    ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].scrollIntoView({block:'center'});", clickable);
                    humanDelay(200, 450);

                    try {
                        wait.until(ExpectedConditions.elementToBeClickable(clickable)).click();
                    } catch (Exception ignored) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", clickable);
                    }

                    if (waitForPageAdvance(markerBefore)) {
                        return true;
                    }
                    log.debug("  Next control clicked but page marker did not change.");
                } catch (StaleElementReferenceException e) {
                    log.debug("  Next button became stale while clicking.");
                } catch (Exception e) {
                    log.debug("  Next button click failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("  No next page control: {}", e.getMessage());
        }
        return false;
    }

    private List<WebElement> findNextArrowCandidates() {
        List<WebElement> combined = new ArrayList<>(driver.findElements(By.cssSelector(SEL_NEXT_PAGE_ARROW)));
        combined.addAll(driver.findElements(By.cssSelector(SEL_NEXT_PAGE_ARROW_ALT)));
        combined.addAll(driver.findElements(By.xpath("//span[contains(@class, 'pagination_pagination-right-arrow')]")));
        return combined;
    }

    private boolean isElementEnabled(WebElement element) {
        String disabled = element.getAttribute("disabled");
        String ariaDisabled = element.getAttribute("aria-disabled");
        String classes = element.getAttribute("class");

        if ("true".equalsIgnoreCase(disabled) || "disabled".equalsIgnoreCase(disabled)) return false;
        if ("true".equalsIgnoreCase(ariaDisabled)) return false;
        if (classes != null && classes.toLowerCase().contains("disabled")) return false;
        return true;
    }

    private String getFirstJobMarker() {
        try {
            List<WebElement> items = driver.findElements(By.cssSelector(SEL_JOB_ITEMS));
            if (items.isEmpty()) return null;
            WebElement link = items.get(0).findElement(By.cssSelector(SEL_JOB_LINK));
            String href = link.getAttribute("href");
            if (href != null && !href.isBlank()) return href;
            return link.getText();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean waitForPageAdvance(String markerBefore) {
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(SEL_JOB_CONTAINER)));
            wait.until(d -> {
                String markerAfter = getFirstJobMarker();
                if (markerBefore == null) return markerAfter != null;
                return markerAfter != null && !markerBefore.equals(markerAfter);
            });
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private WebElement findClickableParent(WebElement element) {
        try {
            return element.findElement(By.xpath("./ancestor-or-self::a[1]"));
        } catch (NoSuchElementException ignored) {}

        try {
            return element.findElement(By.xpath("./ancestor-or-self::button[1]"));
        } catch (NoSuchElementException ignored) {}

        return element;
    }

    private void humanDelay(int minMs, int maxMs) {
        try {
            int delay = minMs + random.nextInt(Math.max(1, maxMs - minMs));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
