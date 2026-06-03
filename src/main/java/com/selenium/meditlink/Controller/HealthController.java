package com.selenium.meditlink.Controller;

import com.selenium.meditlink.Services.CommentExtractionStatsService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint de santé exposé pour le healthcheck Railway et pour le monitor
 * externe d'oneScan-API (qui ping ce endpoint toutes les 5 min).
 *
 * <p>
 * Renvoie 503 quand la mémoire est saturée OU quand l'extraction des
 * commentaires MeditLink est dégradée — sélecteur Vue cassé, hash data-v
 * changé, page détail introuvable, etc.
 */
@RestController
public class HealthController {

    @Value("${memory-monitor.heap-threshold:0.85}")
    private double heapThreshold;

    @Autowired
    private CommentExtractionStatsService commentStats;

    private final long startTimeMillis = System.currentTimeMillis();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long maxBytes = rt.maxMemory();
        double usedRatio = (double) usedBytes / maxBytes;
        boolean memoryDegraded = usedRatio >= heapThreshold;
        boolean commentsDegraded = commentStats.isDegraded();
        // 503 UNIQUEMENT pour la mémoire — redémarrer ne corrige pas un
        // sélecteur Vue cassé. L'état "comments degraded" est exposé dans le
        // body et alerté par oneScan-API via Brevo.
        boolean returnHttp503 = memoryDegraded;
        long uptimeMs = System.currentTimeMillis() - startTimeMillis;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", memoryDegraded ? "DEGRADED"
                : commentsDegraded ? "COMMENTS_DEGRADED" : "UP");
        if (memoryDegraded || commentsDegraded) {
            body.put("degradedReason",
                    memoryDegraded && commentsDegraded ? "memory+comments"
                            : memoryDegraded ? "memory" : "comments");
        }
        body.put("heapUsedMb", usedBytes / 1_048_576L);
        body.put("heapMaxMb", maxBytes / 1_048_576L);
        body.put("heapUsedPercent", Math.round(usedRatio * 100));
        body.put("uptimeMs", uptimeMs);
        body.put("startTimestamp", startTimeMillis);
        body.put("commentExtraction", commentStats.snapshot());

        return returnHttp503
                ? ResponseEntity.status(503).body(body)
                : ResponseEntity.ok(body);
    }
}
