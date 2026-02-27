package com.jobscraper.model;

/**
 * Represents a row in the jobs table.
 */
public class Job {

    private String reqId;
    private int portalId;
    private String portalName;
    private String position;
    private String location;
    private String jobUrl;

    public Job() {}

    public Job(String reqId, int portalId, String portalName, String position,
               String location, String jobUrl) {
        this.reqId = reqId;
        this.portalId = portalId;
        this.portalName = portalName;
        this.position = position;
        this.location = location;
        this.jobUrl = jobUrl;
    }

    public String getReqId() {
        return reqId;
    }

    public void setReqId(String reqId) {
        this.reqId = reqId;
    }

    public int getPortalId() {
        return portalId;
    }

    public void setPortalId(int portalId) {
        this.portalId = portalId;
    }

    public String getPortalName() {
        return portalName;
    }

    public void setPortalName(String portalName) {
        this.portalName = portalName;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getJobUrl() {
        return jobUrl;
    }

    public void setJobUrl(String jobUrl) {
        this.jobUrl = jobUrl;
    }

    @Override
    public String toString() {
        return String.format("Job{reqId='%s', portal='%s', position='%s', location='%s'}",
                reqId, portalName, position, location);
    }
}