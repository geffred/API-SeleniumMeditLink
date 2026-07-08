package com.selenium.meditlink.Services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Surveille la mémoire RÉELLE du conteneur (cgroup), pas seulement le tas JVM.
 *
 * <p>
 * Les crashs silencieux sur Railway (« Crashed » sans rien de rouge dans les
 * logs) sont des OOM kills : le noyau envoie SIGKILL quand le conteneur entier
 * dépasse sa limite RAM. Or Chrome/Chromedriver sont des processus natifs hors
 * JVM : le tas peut afficher 40 % « sain » pendant que le conteneur sature.
 * Ce monitor lit donc la consommation cgroup (JVM + Chrome + tout le reste)
 * et déclenche un recyclage propre et journalisé AVANT le SIGKILL invisible.
 *
 * <p>
 * Le tas JVM reste surveillé en second filet. Le recyclage périodique par
 * uptime est désactivé par défaut (max-uptime-hours=0) : il comptait comme un
 * « crash » côté Railway et épuisait le budget restartPolicyMaxRetries.
 */
@Service
public class MemoryMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitorService.class);

    // cgroup v2
    private static final Path CGROUP_V2_USAGE = Path.of("/sys/fs/cgroup/memory.current");
    private static final Path CGROUP_V2_LIMIT = Path.of("/sys/fs/cgroup/memory.max");
    private static final Path CGROUP_V2_STAT = Path.of("/sys/fs/cgroup/memory.stat");
    // cgroup v1
    private static final Path CGROUP_V1_USAGE = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes");
    private static final Path CGROUP_V1_LIMIT = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");

    /** Seuil de saturation de la mémoire du conteneur (RSS). */
    @Value("${memory-monitor.rss-threshold:0.90}")
    private double rssThreshold;

    /** Seuil de saturation du tas JVM (second filet). */
    @Value("${memory-monitor.heap-threshold:0.85}")
    private double heapThreshold;

    @Value("${memory-monitor.consecutive-checks:3}")
    private int consecutiveChecksRequired;

    /** 0 = recyclage par uptime désactivé (défaut). */
    @Value("${memory-monitor.max-uptime-hours:0}")
    private long maxUptimeHours;

    @Value("${memory-monitor.enabled:true}")
    private boolean enabled;

    private final long startTimeMillis = System.currentTimeMillis();
    private int consecutiveHighChecks = 0;

    public double getHeapThreshold() {
        return heapThreshold;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void check() {
        if (!enabled) {
            return;
        }

        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        double heapRatio = (double) heapUsed / heapMax;

        long uptimeMs = System.currentTimeMillis() - startTimeMillis;
        long uptimeHours = uptimeMs / 3_600_000L;

        Optional<long[]> container = readContainerMemory();
        if (container.isPresent()) {
            long usage = container.get()[0];
            long limit = container.get()[1];
            double containerRatio = (double) usage / limit;

            log.info("[MemoryMonitor] Conteneur: {} / {} Mo ({}%) — Heap JVM: {} / {} Mo ({}%) — Uptime: {} h",
                    usage / 1_048_576L, limit / 1_048_576L, Math.round(containerRatio * 100),
                    heapUsed / 1_048_576L, heapMax / 1_048_576L, Math.round(heapRatio * 100),
                    uptimeHours);

            if (evaluateThreshold(containerRatio, rssThreshold, "mémoire conteneur")) {
                triggerRestart("rss-saturation-conteneur");
                return;
            }
        } else {
            // cgroup illisible (exécution locale, cgroup non monté…) → second filet heap.
            log.info("[MemoryMonitor] Heap JVM: {} / {} Mo ({}%) — Uptime: {} h (cgroup indisponible)",
                    heapUsed / 1_048_576L, heapMax / 1_048_576L, Math.round(heapRatio * 100), uptimeHours);

            if (evaluateThreshold(heapRatio, heapThreshold, "tas JVM")) {
                triggerRestart("heap-saturation");
                return;
            }
        }

        if (maxUptimeHours > 0 && uptimeHours >= maxUptimeHours) {
            log.warn("[MemoryMonitor] ⚠ Uptime {} h ≥ {} h → recyclage préventif.", uptimeHours, maxUptimeHours);
            triggerRestart("uptime");
        }
    }

    /**
     * Incrémente/réinitialise le compteur de mesures consécutives au-dessus du
     * seuil ; retourne true quand le recyclage doit être déclenché.
     */
    private boolean evaluateThreshold(double ratio, double threshold, String label) {
        if (ratio >= threshold) {
            consecutiveHighChecks++;
            log.warn("[MemoryMonitor] ⚠ {} au-dessus du seuil de {} % ({}/{} mesures consécutives).",
                    label, Math.round(threshold * 100), consecutiveHighChecks, consecutiveChecksRequired);
            return consecutiveHighChecks >= consecutiveChecksRequired;
        }
        if (consecutiveHighChecks > 0) {
            log.info("[MemoryMonitor] {} redescendue sous le seuil → compteur remis à zéro.", label);
        }
        consecutiveHighChecks = 0;
        return false;
    }

    /**
     * Lit [usage, limite] mémoire du conteneur via cgroup v2 puis v1.
     * L'usage exclut le cache de fichiers inactif (récupérable par le noyau),
     * pour coller au critère réel de l'OOM killer.
     */
    private Optional<long[]> readContainerMemory() {
        try {
            if (Files.isReadable(CGROUP_V2_USAGE) && Files.isReadable(CGROUP_V2_LIMIT)) {
                String limitRaw = Files.readString(CGROUP_V2_LIMIT).trim();
                if ("max".equals(limitRaw)) {
                    return Optional.empty(); // pas de limite → rien à surveiller
                }
                long limit = Long.parseLong(limitRaw);
                long usage = Long.parseLong(Files.readString(CGROUP_V2_USAGE).trim());
                usage = Math.max(0, usage - readV2InactiveFile());
                return checkedResult(usage, limit);
            }
            if (Files.isReadable(CGROUP_V1_USAGE) && Files.isReadable(CGROUP_V1_LIMIT)) {
                long limit = Long.parseLong(Files.readString(CGROUP_V1_LIMIT).trim());
                long usage = Long.parseLong(Files.readString(CGROUP_V1_USAGE).trim());
                return checkedResult(usage, limit);
            }
        } catch (Exception e) {
            log.debug("[MemoryMonitor] Lecture cgroup impossible : {}", e.getMessage());
        }
        return Optional.empty();
    }

    private long readV2InactiveFile() {
        try {
            for (String line : Files.readAllLines(CGROUP_V2_STAT)) {
                if (line.startsWith("inactive_file ")) {
                    return Long.parseLong(line.substring("inactive_file ".length()).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private Optional<long[]> checkedResult(long usage, long limit) {
        // limites fantaisistes (v1 renvoie ~Long.MAX quand illimité)
        if (limit <= 0 || limit > (1L << 44)) {
            return Optional.empty();
        }
        return Optional.of(new long[] { usage, limit });
    }

    private void triggerRestart(String reason) {
        log.error("[MemoryMonitor] === REDÉMARRAGE DÉCLENCHÉ (raison: {}) === "
                + "Recyclage propre avant OOM kill ; Railway va relancer le conteneur.", reason);
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
