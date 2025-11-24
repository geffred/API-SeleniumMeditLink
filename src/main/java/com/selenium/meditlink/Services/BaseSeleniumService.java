package com.selenium.meditlink.Services;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * Service de base pour les interactions Selenium.
 * Gère la configuration, la connexion et la stabilité du WebDriver.
 * Inclut une protection mémoire pour éviter toute surcharge CPU/RAM.
 */
@Component
public abstract class BaseSeleniumService implements DentalPlatformService {

    private WebDriver driver;
    private WebDriverWait wait;
    protected boolean isLoggedIn = false;

    /**
     * Initialise Selenium WebDriver avec configuration de téléchargement
     * automatique.
     * Si un driver est déjà actif et fonctionnel, il sera réutilisé.
     */
    protected synchronized void initializeDriver() {
        if (driver != null && isDriverAlive()) {
            System.out.println("[SELENIUM] Réutilisation du driver existant.");
            return;
        }

        closeDriverQuietly();

        try {
            System.out.println("[SELENIUM] Initialisation du WebDriver Chrome...");

            WebDriverManager.chromedriver().setup();

            // Dossier de téléchargement automatique
            String downloadDir = System.getProperty("user.dir") + File.separator + "downloads";
            File folder = new File(downloadDir);
            if (!folder.exists())
                folder.mkdirs();

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("download.default_directory", downloadDir);
            prefs.put("download.prompt_for_download", false);
            prefs.put("download.directory_upgrade", true);
            prefs.put("safebrowsing.enabled", true);

            ChromeOptions options = new ChromeOptions();
            options.setExperimentalOption("prefs", prefs);
            options.addArguments(
                    "--headless=new",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "--window-size=1920,1080",
                    "--remote-allow-origins=*",
                    "--disable-extensions",
                    "--disable-popup-blocking",
                    "--disable-background-timer-throttling");

            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

            wait = new WebDriverWait(driver, Duration.ofSeconds(30));

            driver.get("about:blank");
            System.out.println("[SELENIUM] WebDriver prêt : " + driver.getTitle());

        } catch (Exception e) {
            System.err.println("[SELENIUM] Échec initialisation WebDriver : " + e.getMessage());
            closeDriverQuietly();
            throw new RuntimeException("Impossible d'initialiser WebDriver", e);
        }
    }

    /**
     * Vérifie si le driver est toujours actif et fonctionnel.
     */
    protected boolean isDriverAlive() {
        try {
            return driver != null && driver.getCurrentUrl() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Vérifie la connexion ou tente de se reconnecter si nécessaire.
     */
    protected boolean ensureConnection() {
        try {
            if (isLoggedIn && verifyLoggedIn()) {
                return true;
            }
        } catch (Exception e) {
            System.err.println("[SELENIUM] Session expirée : " + e.getMessage());
            isLoggedIn = false;
        }

        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        closeDriverQuietly();
        initializeDriver();

        String loginResult = login();
        boolean success = loginResult.contains("réussie") || loginResult.contains("Déjà connecté");

        if (!success) {
            System.err.println("[SELENIUM] Échec de la connexion : " + loginResult);
            return false;
        }

        return verifyLoggedIn();
    }

    /**
     * Tente de maintenir la session active.
     */
    protected boolean ensureLoggedIn() {
        try {
            if (isLoggedIn && verifyLoggedIn()) {
                return true;
            }
        } catch (Exception e) {
            System.err.println("[SELENIUM] Vérification de connexion échouée : " + e.getMessage());
            isLoggedIn = false;
        }

        String result = login();
        return result.startsWith("Connexion réussie") || result.equals("Déjà connecté.");
    }

    /**
     * Gère les erreurs Selenium et relance le driver si nécessaire.
     */
    protected void handleError(Exception e) {
        System.err.println("[SELENIUM] Erreur : " + e.getMessage());
        e.printStackTrace();
        isLoggedIn = false;

        if (e.getMessage() != null && (e.getMessage().contains("invalid session id") ||
                e.getMessage().contains("no such session") ||
                e.getMessage().contains("browser has closed") ||
                e.getMessage().contains("disconnected") ||
                e.getMessage().contains("chrome not reachable"))) {
            System.err.println("[SELENIUM] Recyclage du driver après erreur critique.");
            closeDriverQuietly();
        }
    }

    /**
     * Ferme silencieusement le driver Selenium.
     * Libère explicitement la mémoire JVM pour éviter la surcharge CPU/RAM.
     */
    protected synchronized void closeDriverQuietly() {
        System.out.println("[SELENIUM] Fermeture du driver...");

        try {
            if (driver != null) {
                try {
                    driver.quit();
                    System.out.println("[SELENIUM] Driver fermé correctement.");
                } catch (Exception quitException) {
                    System.out.println("[SELENIUM] Le driver était déjà fermé ou non accessible.");
                }
            }
        } catch (Exception e) {
            System.out.println("[SELENIUM] Erreur ignorée lors de la fermeture du driver.");
        } finally {
            driver = null;
            wait = null;
            isLoggedIn = false;

            // Nettoyage mémoire explicite
            try {
                System.gc();
                Thread.sleep(100); // Laisse un court délai au GC
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Attend qu'un fichier soit complètement téléchargé.
     * Retourne le fichier une fois le téléchargement terminé.
     */
    protected File waitForDownloadToFinish(File downloadDir, int timeoutSeconds) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeoutSeconds * 1000L;
        File latestFile = null;

        while (System.currentTimeMillis() < endTime) {
            File[] files = downloadDir.listFiles(
                    (dir, name) -> name.endsWith(".stl") || name.endsWith(".zip") || name.endsWith(".crdownload"));

            if (files != null && files.length > 0) {
                latestFile = Arrays.stream(files)
                        .max(Comparator.comparingLong(File::lastModified))
                        .orElse(null);

                if (latestFile != null && !latestFile.getName().endsWith(".crdownload")) {
                    return latestFile;
                }
            }

            Thread.sleep(1000);
        }

        return null;
    }

    /**
     * Ferme complètement le driver et libère la mémoire.
     */
    public synchronized void closeDriver() {
        closeDriverQuietly();
    }

    /**
     * Vérifie si la session Selenium est toujours valide.
     */
    @Override
    public boolean isLoggedIn() {
        try {
            return isLoggedIn && driver != null && isDriverAlive();
        } catch (Exception e) {
            return false;
        }
    }

    protected WebDriver getDriver() {
        return driver;
    }

    protected WebDriverWait getWait() {
        return wait;
    }

    /**
     * Méthode à implémenter dans les classes dérivées pour vérifier si la session
     * est valide.
     */
    protected abstract boolean verifyLoggedIn();
}