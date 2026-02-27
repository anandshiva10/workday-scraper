package com.jobscraper.util;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and provides application configuration from .env file or system environment.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final Dotenv dotenv;

    static {
        Dotenv loaded;
        try {
            loaded = Dotenv.configure()
                    .ignoreIfMissing()
                    .load();
            log.info("Loaded configuration from .env file");
        } catch (DotenvException e) {
            log.warn(".env file not found, falling back to system environment variables");
            loaded = Dotenv.configure().ignoreIfMissing().load();
        }
        dotenv = loaded;
    }

    // -------------------------------------------------------------------------
    // Database
    // -------------------------------------------------------------------------

    public static String getDbUrl() {
        // Allow full JDBC URL override
        String url = get("SUPABASE_DB_URL", null);
        if (url != null && !url.isBlank()) {
            return url;
        }
        // Build from parts
        String host = getRequired("SUPABASE_DB_HOST");
        String port = get("SUPABASE_DB_PORT", "5432");
        String db   = get("SUPABASE_DB_NAME", "postgres");
        return String.format("jdbc:postgresql://%s:%s/%s?sslmode=require", host, port, db);
    }

    public static String getDbUser() {
        return get("SUPABASE_DB_USER", "postgres");
    }

    public static String getDbPassword() {
        return getRequired("SUPABASE_DB_PASSWORD");
    }

    // -------------------------------------------------------------------------
    // Email (SMTP)
    // -------------------------------------------------------------------------

    public static String getSmtpHost() {
        return getRequired("SMTP_HOST");
    }

    public static int getSmtpPort() {
        return Integer.parseInt(get("SMTP_PORT", "587"));
    }

    public static String getSmtpUser() {
        return get("SMTP_USERNAME", null);
    }

    public static String getSmtpPassword() {
        return get("SMTP_PASSWORD", null);
    }

    public static String getSmtpFrom() {
        String from = get("SMTP_FROM", null);
        if (from != null && !from.isBlank()) {
            return from;
        }
        String user = getSmtpUser();
        if (user == null || user.isBlank()) {
            throw new IllegalStateException(
                    "SMTP_FROM or SMTP_USERNAME must be set for sending emails.");
        }
        return user;
    }

    public static boolean isSmtpUseTls() {
        return Boolean.parseBoolean(get("SMTP_USE_TLS", "true"));
    }

    // -------------------------------------------------------------------------
    // Scraper tuning
    // -------------------------------------------------------------------------

    public static int getMaxPages() {
        return Integer.parseInt(get("MAX_PAGES", "5"));
    }

    public static int getPageDelayMinMs() {
        return Integer.parseInt(get("PAGE_DELAY_MIN_MS", "2000"));
    }

    public static int getPageDelayMaxMs() {
        return Integer.parseInt(get("PAGE_DELAY_MAX_MS", "5000"));
    }

    public static boolean isHeadless() {
        return Boolean.parseBoolean(get("HEADLESS_BROWSER", "true"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String get(String key, String defaultValue) {
        String value = dotenv.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String getRequired(String key) {
        String value = dotenv.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required configuration key '" + key + "' is missing. " +
                    "Please set it in .env or as an environment variable.");
        }
        return value;
    }
}