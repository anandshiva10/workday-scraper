package com.jobscraper.util;

import com.jobscraper.model.Job;
import com.jobscraper.model.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Sends email notifications for newly detected jobs.
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final Session session;
    private final String fromAddress;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final boolean useTls;

    public EmailService() {
        this.smtpHost = AppConfig.getSmtpHost();
        this.smtpPort = AppConfig.getSmtpPort();
        this.smtpUser = AppConfig.getSmtpUser();
        this.smtpPassword = AppConfig.getSmtpPassword();
        this.fromAddress = AppConfig.getSmtpFrom();
        this.useTls = AppConfig.isSmtpUseTls();

        Properties props = new Properties();
        props.put("mail.smtp.auth", smtpUser != null && !smtpUser.isBlank());
        props.put("mail.smtp.starttls.enable", useTls);
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));

        this.session = Session.getInstance(props);
    }

    /**
     * Sends a single email containing all newly detected jobs to all subscribers.
     * Body is an HTML table with columns: portal, job req id, position, location, job link.
     */
    public void sendNewJobsEmail(List<Subscriber> subscribers, List<Job> newJobs) {
        if (subscribers == null || subscribers.isEmpty()) {
            log.info("No subscribers configured; skipping email notification.");
            return;
        }
        if (newJobs == null || newJobs.isEmpty()) {
            return;
        }

        List<String> toAddresses = subscribers.stream()
                .map(Subscriber::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (toAddresses.isEmpty()) {
            log.info("Subscribers exist but none have valid email addresses; skipping notification.");
            return;
        }

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            for (String addr : toAddresses) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
            }

            message.setSubject("New job postings detected");
            message.setContent(buildHtmlBody(newJobs), "text/html; charset=UTF-8");

            if (smtpUser != null && !smtpUser.isBlank()) {
                Transport transport = session.getTransport("smtp");
                try {
                    transport.connect(smtpHost, smtpPort, smtpUser, smtpPassword);
                    transport.sendMessage(message, message.getAllRecipients());
                } finally {
                    transport.close();
                }
            } else {
                Transport.send(message);
            }

            log.info("Sent new job notification email to {} subscriber(s)", toAddresses.size());
        } catch (MessagingException e) {
            log.error("Failed to send new job notification email: {}", e.getMessage(), e);
        }
    }

    private String buildHtmlBody(List<Job> newJobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<p>The following new job(s) were detected:</p>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;\">");
        sb.append("<thead><tr>")
                .append("<th>Portal</th>")
                .append("<th>Job Req ID</th>")
                .append("<th>Position</th>")
                .append("<th>Location</th>")
                .append("<th>Job Link</th>")
                .append("</tr></thead>");
        sb.append("<tbody>");

        for (Job job : newJobs) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(job.getPortalName())).append("</td>");
            sb.append("<td>").append(escape(job.getReqId())).append("</td>");
            sb.append("<td>").append(escape(job.getPosition())).append("</td>");
            sb.append("<td>").append(escape(job.getLocation())).append("</td>");
            sb.append("<td>").append(buildLinkCell(job.getJobUrl())).append("</td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }

    private String buildLinkCell(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String safeUrl = escapeAttribute(url.trim());
        return "<a href=\"" + safeUrl + "\">Open Job</a>";
    }
}

