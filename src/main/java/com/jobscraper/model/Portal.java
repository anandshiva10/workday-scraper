package com.jobscraper.model;

/**
 * Represents a row in the portals table.
 */
public class Portal {

    private final int id;
    private final Boolean workday;
    private final String portal;
    private final String link;
    private String recentJobId;

    public Portal(int id, boolean workday, String portal, String link, String recentJobId) {
        this.id = id;
        this.workday=workday;
        this.portal = portal;
        this.link = link;
        this.recentJobId = recentJobId;
        
    }

    public int getId() {
        return id;
    }

    public boolean getWorkday() {
    	return workday;
    }
    
    public String getPortal() {
        return portal;
    }

    public String getLink() {
        return link;
    }

    public String getRecentJobId() {
        return recentJobId;
    }

    public void setRecentJobId(String recentJobId) {
        this.recentJobId = recentJobId;
    }

    @Override
    public String toString() {
        return String.format("Portal{id=%d, portal='%s', link='%s', recentJobId='%s'}",
                id, portal, link, recentJobId);
    }
}