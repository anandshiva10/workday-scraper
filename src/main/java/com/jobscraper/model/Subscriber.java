package com.jobscraper.model;

/**
 * Represents a row in the subscribers table.
 */
public class Subscriber {

    private String name;
    private String email;

    public Subscriber() {
    }

    public Subscriber(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}

