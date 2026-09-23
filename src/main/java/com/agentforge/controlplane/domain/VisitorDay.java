package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** 一次进入概览。同一人同一天多次打开各记一次。 */
@Entity
@Table(name = "visitor_days")
public class VisitorDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "visitor_key", length = 120, nullable = false)
    private String visitorKey;

    @Column(name = "visited_on", nullable = false)
    private LocalDate visitedOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getVisitorKey() { return visitorKey; }
    public void setVisitorKey(String visitorKey) { this.visitorKey = visitorKey; }
    public LocalDate getVisitedOn() { return visitedOn; }
    public void setVisitedOn(LocalDate visitedOn) { this.visitedOn = visitedOn; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
