package com.selenium.meditlink.Services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracker des extractions de commentaires MeditLink.
 * Une seule REGRESSION (commentaire connu qui disparaît) suffit pour passer
 * en degraded → alerte Brevo immédiate.
 */
@Service
public class CommentExtractionStatsService {

    public enum Outcome {
        SUCCESS, EMPTY, FAILURE
    }

    @Value("${comment-extraction.failure-rate-window:20}")
    private int windowSize;

    @Value("${comment-extraction.failure-rate-threshold:0.5}")
    private double failureRateThreshold;

    @Value("${comment-extraction.consecutive-non-success-threshold:15}")
    private int consecutiveNonSuccessThreshold;

    private static final String SENTINEL_EMPTY = "Aucun commentaire";

    private final AtomicLong totalAttempts = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong emptyCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong regressionCount = new AtomicLong();
    private final AtomicLong consecutiveNonSuccess = new AtomicLong();

    private volatile long lastSuccessTimestamp = 0L;
    private volatile long lastFailureTimestamp = 0L;
    private volatile String lastFailureReason = null;
    private volatile long lastAttemptTimestamp = 0L;
    private volatile String lastSubject = null;

    private volatile long lastRegressionTimestamp = 0L;
    private volatile String lastRegressionSubject = null;
    private volatile String lastRegressionPreviousValue = null;
    private volatile String lastRegressionReason = null;

    private final Deque<Outcome> recentOutcomes = new ArrayDeque<>();
    private final Map<String, String> lastKnownComment = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeRegressions = new ConcurrentHashMap<>();

    public synchronized void recordSuccess(String subject) {
        recordSuccessInternal(subject, null);
    }

    public synchronized void recordSuccess(String subject, String commentValue) {
        recordSuccessInternal(subject, commentValue);
    }

    private void recordSuccessInternal(String subject, String commentValue) {
        totalAttempts.incrementAndGet();
        successCount.incrementAndGet();
        consecutiveNonSuccess.set(0);
        lastSuccessTimestamp = System.currentTimeMillis();
        lastAttemptTimestamp = lastSuccessTimestamp;
        lastSubject = subject;
        pushOutcome(Outcome.SUCCESS);
        if (subject != null && commentValue != null
                && !commentValue.trim().isEmpty()
                && !SENTINEL_EMPTY.equals(commentValue.trim())) {
            lastKnownComment.put(subject, commentValue);
        }
        if (subject != null) {
            activeRegressions.remove(subject);
        }
    }

    public synchronized void recordEmpty(String subject) {
        totalAttempts.incrementAndGet();
        emptyCount.incrementAndGet();
        consecutiveNonSuccess.incrementAndGet();
        lastAttemptTimestamp = System.currentTimeMillis();
        lastSubject = subject;
        pushOutcome(Outcome.EMPTY);
    }

    public synchronized void recordFailure(String subject, String reason) {
        totalAttempts.incrementAndGet();
        failureCount.incrementAndGet();
        consecutiveNonSuccess.incrementAndGet();
        lastFailureTimestamp = System.currentTimeMillis();
        lastFailureReason = reason;
        lastAttemptTimestamp = lastFailureTimestamp;
        lastSubject = subject;
        pushOutcome(Outcome.FAILURE);
    }

    public synchronized void recordRegression(String subject, String previousValue, String reason) {
        totalAttempts.incrementAndGet();
        failureCount.incrementAndGet();
        regressionCount.incrementAndGet();
        consecutiveNonSuccess.incrementAndGet();
        lastFailureTimestamp = System.currentTimeMillis();
        lastFailureReason = reason;
        lastAttemptTimestamp = lastFailureTimestamp;
        lastSubject = subject;
        lastRegressionTimestamp = lastFailureTimestamp;
        lastRegressionSubject = subject;
        lastRegressionPreviousValue = truncate(previousValue, 120);
        lastRegressionReason = reason;
        if (subject != null) {
            activeRegressions.put(subject, Boolean.TRUE);
        }
        pushOutcome(Outcome.FAILURE);
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void pushOutcome(Outcome o) {
        recentOutcomes.addLast(o);
        while (recentOutcomes.size() > windowSize) {
            recentOutcomes.removeFirst();
        }
    }

    public String getLastKnownComment(String subject) {
        return subject == null ? null : lastKnownComment.get(subject);
    }

    public boolean hadKnownComment(String subject) {
        return getLastKnownComment(subject) != null;
    }

    public synchronized boolean isDegraded() {
        if (!activeRegressions.isEmpty()) {
            return true;
        }
        if (recentOutcomes.size() >= Math.max(5, windowSize / 2)) {
            long failures = recentOutcomes.stream().filter(o -> o == Outcome.FAILURE).count();
            double failureRate = (double) failures / recentOutcomes.size();
            if (failureRate >= failureRateThreshold) {
                return true;
            }
        }
        return consecutiveNonSuccess.get() >= consecutiveNonSuccessThreshold && successCount.get() > 0;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("totalAttempts", totalAttempts.get());
        snap.put("successCount", successCount.get());
        snap.put("emptyCount", emptyCount.get());
        snap.put("failureCount", failureCount.get());
        snap.put("regressionCount", regressionCount.get());
        snap.put("activeRegressionsCount", activeRegressions.size());
        snap.put("consecutiveNonSuccess", consecutiveNonSuccess.get());
        snap.put("recentWindowSize", recentOutcomes.size());
        long failuresInWindow = recentOutcomes.stream().filter(o -> o == Outcome.FAILURE).count();
        long emptiesInWindow = recentOutcomes.stream().filter(o -> o == Outcome.EMPTY).count();
        snap.put("recentFailures", failuresInWindow);
        snap.put("recentEmpties", emptiesInWindow);
        snap.put("lastSuccessTimestamp", lastSuccessTimestamp);
        snap.put("lastFailureTimestamp", lastFailureTimestamp);
        snap.put("lastFailureReason", lastFailureReason);
        snap.put("lastAttemptTimestamp", lastAttemptTimestamp);
        snap.put("lastSubject", lastSubject);
        snap.put("lastRegressionTimestamp", lastRegressionTimestamp);
        snap.put("lastRegressionSubject", lastRegressionSubject);
        snap.put("lastRegressionPreviousValue", lastRegressionPreviousValue);
        snap.put("lastRegressionReason", lastRegressionReason);
        snap.put("knownCommentsCount", lastKnownComment.size());
        snap.put("degraded", isDegraded());
        return snap;
    }
}
