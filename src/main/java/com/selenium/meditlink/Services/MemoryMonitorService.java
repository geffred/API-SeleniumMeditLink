package com.selenium.meditlink.Services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Surveille la mémoire JVM et l'uptime du service. Déclenche un redémarrage
 * propre (System.exit(1)) lorsque les conditions de saturation sont atteintes,
 * laissant Railway relancer le conteneur avec un état neuf.
 */
@Service
public class MemoryMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitorService.class);

    @Value("${memory-monitor.heap-threshold:0.85}")
    private double heapThreshold;

    @Value("${memory-monitor.consecutive-checks:3}")
    private int consecutiveChecksRequired;

    @Value("${memory-monitor.max-uptime-hours:6}")
    private long maxUptimeHours;

    @Value("${memory-monitor.enabled:true}")
    private boolean enabled;

    private final long startTimeMillis = System.currentTimeMillis();
    private int consecutiveHighChecks = 0;

    @Scheduled(fixedDelay = 120_000L, initialDelay = 60_000L)
    public void check() {
        if (!enabled) {
            return;
        }

        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long maxBytes = rt.maxMemory();
        double usedRatio = (double) usedBytes / maxBytes;

        long uptimeMs = System.currentTimeMillis() - startTimeMillis;
        long uptimeHours = uptimeMs / 3_600_000L;

        log.info("[MemoryMonitor] Heap: {} / {} Mo ({}%) — Uptime: {} h",
                usedBytes / 1_048_576L,
                maxBytes / 1_048_576L,
                Math.round(usedRatio * 100),
                uptimeHours);

        if (maxUptimeHours > 0 && uptimeHours >= maxUptimeHours) {
            log.warn("[MemoryMonitor] ⚠ Uptime {} h ≥ {} h → recyclage préventif.",
                    uptimeHours, maxUptimeHours);
            triggerRestart("uptime");
            return;
        }

        if (usedRatio >= heapThreshold) {
            consecutiveHighChecks++;
            log.warn("[MemoryMonitor] ⚠ Tas au-dessus du seuil ({}/{} mesures consécutives).",
                    consecutiveHighChecks, consecutiveChecksRequired);
            if (consecutiveHighChecks >= consecutiveChecksRequired) {
                triggerRestart("heap-saturation");
            }
        } else {
            if (consecutiveHighChecks > 0) {
                log.info("[MemoryMonitor] Tas redescendu sous le seuil → compteur remis à zéro.");
            }
            consecutiveHighChecks = 0;
        }
    }

    private void triggerRestart(String reason) {
        log.error("[MemoryMonitor] === REDÉMARRAGE DÉCLENCHÉ (raison: {}) ===", reason);
        new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(1);
        }, "memory-monitor-exit").start();
    }
}
