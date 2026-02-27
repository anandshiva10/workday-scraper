package com.jobscraper.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches and parses robots.txt to verify a given path is allowed before scraping.
 * Respects the robots.txt standard as required.
 */
public class RobotsChecker {

    private static final Logger log = LoggerFactory.getLogger(RobotsChecker.class);
    private static final String USER_AGENT = "*";

    /**
     * Returns true if the given URL path is allowed to be scraped per robots.txt rules.
     */
    public static boolean isAllowed(String siteUrl) {
        try {
            URI uri = URI.create(siteUrl);
            String robotsUrl = uri.getScheme() + "://" + uri.getHost() + "/robots.txt";
            String path = uri.getPath();

            log.info("Checking robots.txt at: {}", robotsUrl);
            List<String> lines = fetchRobotsTxt(robotsUrl);

            boolean inRelevantBlock = false;
            List<String> disallowed = new ArrayList<>();
            List<String> allowed    = new ArrayList<>();

            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;

                if (line.toLowerCase().startsWith("user-agent:")) {
                    String agent = line.substring("user-agent:".length()).trim();
                    inRelevantBlock = agent.equals("*") || agent.equalsIgnoreCase("java");
                } else if (inRelevantBlock) {
                    if (line.toLowerCase().startsWith("disallow:")) {
                        String p = line.substring("disallow:".length()).trim();
                        if (!p.isEmpty()) disallowed.add(p);
                    } else if (line.toLowerCase().startsWith("allow:")) {
                        String p = line.substring("allow:".length()).trim();
                        if (!p.isEmpty()) allowed.add(p);
                    }
                }
            }

            // Check explicit Allow rules first (more specific wins)
            for (String a : allowed) {
                if (path.startsWith(a)) {
                    log.info("Path '{}' is ALLOWED by robots.txt (Allow: {})", path, a);
                    return true;
                }
            }

            // Check Disallow rules
            for (String d : disallowed) {
                if (path.startsWith(d)) {
                    log.warn("Path '{}' is DISALLOWED by robots.txt (Disallow: {})", path, d);
                    return false;
                }
            }

            log.info("Path '{}' is ALLOWED by robots.txt (no matching Disallow rule)", path);
            return true;

        } catch (Exception e) {
            // If we cannot read robots.txt, be conservative and allow
            log.warn("Could not read robots.txt for {}: {}. Proceeding with caution.", siteUrl, e.getMessage());
            return true;
        }
    }

    private static List<String> fetchRobotsTxt(String robotsUrl) throws IOException {
        List<String> lines = new ArrayList<>();
        URL url = URI.create(robotsUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "WorkdayJobScraper/1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() == 200) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
        } else {
            log.warn("robots.txt returned HTTP {}, assuming all allowed", conn.getResponseCode());
        }
        conn.disconnect();
        return lines;
    }
}