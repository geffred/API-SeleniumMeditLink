package com.selenium.meditlink.Services;

import org.openqa.selenium.*;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.*;
import org.springframework.stereotype.Service;

import com.selenium.meditlink.Entity.Commande;
import com.selenium.meditlink.Entity.Plateforme;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class MeditLinkSeleniumService extends BaseSeleniumService {

    private static final String BASE_URL = "https://www.meditlink.com";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int WAIT_SHORT = 5;
    private static final int WAIT_MEDIUM = 10;
    private static final int MAX_COMMANDES = 6; // Toujours 6 dernières commandes

    // Selecteurs CSS
    private static final String LOGIN_INPUT_CSS = "input#input-login-id.text-box-input";
    private static final String PASSWORD_INPUT_CSS = "input#input-login-password.text-box-input";
    private static final String LOGIN_BUTTON_CSS = "button#btn-login";
    private static final String POPUP_CLOSE_CSS = "div.icon-wrapper.md-icon.xxs[rounded='false']";
    private static final String INBOX_TABLE_ROW_CSS = "tr.main-body-tr";
    private static final String COMMENT_TEXTAREA_CSS = "textarea[data-v-8a2006a2][data-v-2adbe6cd-s].show-scrollbar[disabled]";
    private static final String DOWNLOAD_BUTTON_CSS = "div.bg-button";

    private final List<Commande> commandesStorage = Collections.synchronizedList(new ArrayList<>());
    private LocalDate lastFetchTime = null;

    // Credentials
    private final String email = "digilab@thesmilespace.be";
    private final String password = "!Pass*1234";

    @Override
    public String login() {
        System.out.println("=== [MEDITLINK LOGIN] Début de la connexion ===");
        initializeDriver();

        if (isLoggedIn && verifyLoggedIn()) {
            System.out.println("[MEDITLINK] Déjà connecté, vérification réussie");
            return "Déjà connecté.";
        }

        try {
            WebDriver driver = getDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_MEDIUM));

            driver.get(BASE_URL + "/login");
            System.out.println("[MEDITLINK] Page de login chargée");

            // Saisie des identifiants
            WebElement emailField = wait
                    .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(LOGIN_INPUT_CSS)));
            emailField.clear();
            emailField.sendKeys(email);
            System.out.println("[MEDITLINK] Email saisi");

            WebElement passwordField = wait
                    .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(PASSWORD_INPUT_CSS)));
            passwordField.clear();
            passwordField.sendKeys(password);
            System.out.println("[MEDITLINK] Mot de passe saisi");

            // Connexion
            WebElement loginButton = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector(LOGIN_BUTTON_CSS)));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", loginButton);
            System.out.println("[MEDITLINK] Clic sur bouton login");

            // Attente redirection
            wait.until(ExpectedConditions.urlContains("inbox"));
            System.out.println("[MEDITLINK] Redirection vers inbox réussie");

            // Fermeture popup
            try {
                WebElement closePopupButton = new WebDriverWait(driver, Duration.ofSeconds(WAIT_SHORT))
                        .until(ExpectedConditions.elementToBeClickable(By.cssSelector(POPUP_CLOSE_CSS)));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", closePopupButton);
                System.out.println("[MEDITLINK] Popup fermé");
            } catch (TimeoutException e) {
                System.out.println("[MEDITLINK] Aucun popup détecté");
            }

            isLoggedIn = true;
            System.out.println("[MEDITLINK] Connexion réussie");
            return "Connexion réussie.";

        } catch (Exception e) {
            handleError(e);
            return "Échec de la connexion : " + e.getMessage();
        }
    }

    @Override
    protected boolean verifyLoggedIn() {
        try {
            WebDriver driver = getDriver();
            if (driver == null) {
                System.out.println("[MEDITLINK] Driver null pour vérification connexion");
                return false;
            }

            driver.get(BASE_URL + "/dashboard");
            new WebDriverWait(driver, Duration.ofSeconds(WAIT_SHORT))
                    .until(ExpectedConditions.urlContains("dashboard"));
            System.out.println("[MEDITLINK] Vérification connexion réussie");
            return true;
        } catch (Exception e) {
            System.out.println("[MEDITLINK] Échec vérification connexion: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<Commande> fetchCommandes() {
        System.out.println("\n=== [MEDITLINK FETCH] Début récupération des 6 dernières commandes ===");

        if (!ensureConnection()) {
            System.err.println("[MEDITLINK] Impossible de se connecter, retour cache");
            return getSixDernieresCommandes();
        }

        try {
            WebDriver driver = getDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_MEDIUM));

            System.out.println("[MEDITLINK] Chargement page inbox...");
            driver.get(BASE_URL + "/inbox");

            // Attente chargement tableau
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(INBOX_TABLE_ROW_CSS)));
            List<WebElement> rows = driver.findElements(By.cssSelector(INBOX_TABLE_ROW_CSS));
            System.out.println("[MEDITLINK] " + rows.size() + " lignes trouvées dans l'inbox");

            // Vérification rapide des nouvelles commandes
            boolean nouvellesCommandes = detecterNouvellesCommandes(rows);

            if (!nouvellesCommandes && !commandesStorage.isEmpty()) {
                System.out.println("[MEDITLINK] Aucune nouvelle commande, retour des 6 dernières du cache");
                return getSixDernieresCommandes();
            }

            System.out.println("[MEDITLINK] Nouvelles commandes détectées, extraction complète...");
            List<Commande> toutesCommandes = extraireToutesCommandesAvecCommentaires(rows);

            // Mise à jour du cache avec tri par date
            commandesStorage.clear();
            commandesStorage.addAll(toutesCommandes);
            trierCacheParDate();

            lastFetchTime = LocalDate.now();
            System.out.println("[MEDITLINK] Cache mis à jour avec " + commandesStorage.size() + " commandes");

            return getSixDernieresCommandes();

        } catch (Exception e) {
            handleError(e);
            System.err.println("[MEDITLINK] Erreur lors du fetch, retour cache existant");
            return getSixDernieresCommandes();
        }
    }

    /**
     * Détecte s'il y a de nouvelles commandes par rapport au cache
     */
    private boolean detecterNouvellesCommandes(List<WebElement> rows) {
        System.out.println("[MEDITLINK] Détection des nouvelles commandes...");

        if (commandesStorage.isEmpty()) {
            System.out.println("[MEDITLINK] Cache vide, toutes les commandes sont nouvelles");
            return true;
        }

        Set<String> idsCache = commandesStorage.stream()
                .map(Commande::getExternalId)
                .collect(Collectors.toSet());

        for (WebElement row : rows) {
            try {
                String externalId = extractText(row, "td:nth-child(7) span");
                if (!externalId.isEmpty() && !idsCache.contains(externalId)) {
                    System.out.println("[MEDITLINK] Nouvelle commande détectée: " + externalId);
                    return true;
                }
            } catch (Exception e) {
                // Continue avec la ligne suivante
            }
        }

        System.out.println("[MEDITLINK] Aucune nouvelle commande détectée");
        return false;
    }

    /**
     * Extrait toutes les commandes avec leurs commentaires
     */
    private List<Commande> extraireToutesCommandesAvecCommentaires(List<WebElement> rows) {
        System.out.println("[MEDITLINK] Extraction de toutes les commandes avec commentaires...");
        List<Commande> commandes = new ArrayList<>();

        for (int i = 0; i < Math.min(rows.size(), MAX_COMMANDES * 2); i++) {
            WebElement row = rows.get(i);
            System.out.println(
                    "[MEDITLINK] Traitement ligne " + (i + 1) + "/" + Math.min(rows.size(), MAX_COMMANDES * 2));

            Commande cmd = extractCommandeFromRow(row);
            if (cmd != null) {
                commandes.add(cmd);
            }
        }

        System.out.println(
                "[MEDITLINK] " + commandes.size() + " commandes extraites, récupération des commentaires...");

        // Récupération des commentaires en parallèle
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();

        for (Commande commande : commandes) {
            futures.add(executor.submit(() -> {
                String commentaire = extractComments(commande.getExternalId());
                commande.setCommentaire(commentaire);
                System.out.println("[MEDITLINK] Commentaire récupéré pour " + commande.getExternalId());
            }));
        }

        // Attente fin de tous les threads
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                System.err.println("[MEDITLINK] Erreur lors de la récupération des commentaires: " + e.getMessage());
            }
        }

        executor.shutdown();
        System.out.println("[MEDITLINK] Tous les commentaires récupérés");

        return commandes;
    }

    /**
     * Retourne toujours les 6 dernières commandes du cache
     */
    private List<Commande> getSixDernieresCommandes() {
        if (commandesStorage.isEmpty()) {
            System.out.println("[MEDITLINK] Cache vide, aucune commande à retourner");
            return new ArrayList<>();
        }

        List<Commande> sixDernieres = commandesStorage.stream()
                .limit(MAX_COMMANDES)
                .collect(Collectors.toList());

        System.out.println("[MEDITLINK] Retour des " + sixDernieres.size() + " dernières commandes");
        return new ArrayList<>(sixDernieres);
    }

    /**
     * Trie le cache par date de réception (plus récent en premier)
     */
    private void trierCacheParDate() {
        commandesStorage.sort(Comparator.comparing(
                Commande::getDateReception,
                Comparator.nullsLast(Comparator.reverseOrder())));
        System.out.println("[MEDITLINK] Cache trié par date (plus récent en premier)");
    }

    private Commande extractCommandeFromRow(WebElement row) {
        try {
            String patientName = extractText(row, "td:nth-child(3) span");
            String externalId = extractText(row, "td:nth-child(7) span");

            if (patientName.isEmpty() || externalId.isEmpty()) {
                System.out.println("[MEDITLINK] Données manquantes dans la ligne, commande ignorée");
                return null;
            }

            Commande commande = new Commande();
            commande.setExternalId(externalId);
            commande.setRefPatient(patientName);
            commande.setPlateforme(Plateforme.MEDITLINK);
            commande.setCabinet(extractText(row, "td:nth-child(6) span"));
            commande.setVu(false);

            // Gestion des dates
            String creationDateStr = extractText(row, "td:nth-child(4) span");
            String dueDateStr = extractText(row, "td:nth-child(5) span");

            try {
                LocalDate creationDate = LocalDate.parse(creationDateStr, DATE_FORMATTER);
                commande.setDateReception(creationDate);
                System.out.println("[MEDITLINK] Date réception: " + creationDate + " pour " + externalId);
            } catch (Exception e) {
                commande.setDateReception(LocalDate.now());
                System.out.println("[MEDITLINK] Date réception par défaut pour " + externalId);
            }

            try {
                LocalDate dueDate = LocalDate.parse(dueDateStr, DATE_FORMATTER);
                commande.setDateEcheance(dueDate);
            } catch (Exception e) {
                // Date d'échéance non définie si parsing échoue
            }

            System.out.println("[MEDITLINK] Commande extraite: " + externalId + " - " + patientName);
            return commande;

        } catch (Exception e) {
            System.err.println("[MEDITLINK] Erreur extraction commande: " + e.getMessage());
            return null;
        }
    }

    private String extractText(WebElement row, String css) {
        try {
            return row.findElement(By.cssSelector(css)).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String extractComments(String externalId) {
        System.out.println("[MEDITLINK] Début extraction commentaires pour " + externalId);

        try {
            WebDriver driver = getDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_MEDIUM));

            driver.get(BASE_URL + "/inbox/detail/" + externalId);
            wait.until(ExpectedConditions.urlContains("/inbox/detail/"));

            WebElement commentaireTextarea = wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector(COMMENT_TEXTAREA_CSS)));

            String commentaire = commentaireTextarea.getAttribute("value");
            if (commentaire == null || commentaire.trim().isEmpty()) {
                commentaire = commentaireTextarea.getText();
            }

            String result = commentaire != null && !commentaire.trim().isEmpty() ? commentaire.trim()
                    : "Aucun commentaire";
            System.out.println("[MEDITLINK] Commentaires récupérés pour " + externalId + ": " +
                    (result.length() > 50 ? result.substring(0, 50) + "..." : result));

            return result;

        } catch (Exception e) {
            System.err.println("[MEDITLINK] Impossible de récupérer les commentaires pour " + externalId);
            return "Aucun commentaire";
        }
    }

    public boolean download3dScan(String externalId) {
        System.out.println("[MEDITLINK] Début téléchargement scan 3D pour " + externalId);

        if (!ensureConnection()) {
            System.err.println("[MEDITLINK] Impossible de se connecter pour téléchargement");
            return false;
        }

        try {
            WebDriver driver = getDriver();
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_MEDIUM));

            driver.get(BASE_URL + "/workbox/detail/" + externalId);
            wait.until(ExpectedConditions.urlContains("/workbox/detail/"));

            WebElement downloadButton = wait.until(
                    ExpectedConditions.elementToBeClickable(By.cssSelector(DOWNLOAD_BUTTON_CSS)));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", downloadButton);

            Thread.sleep(Duration.ofSeconds(WAIT_SHORT).toMillis());

            System.out.println("[MEDITLINK] Téléchargement initié pour " + externalId);
            return true;

        } catch (Exception e) {
            System.err.println("[MEDITLINK] Erreur téléchargement pour " + externalId + ": " + e.getMessage());
            handleError(e);
            return false;
        }
    }

    @Override
    public String logout() {
        System.out.println("[MEDITLINK] Début déconnexion...");
        closeDriver();
        isLoggedIn = false;
        commandesStorage.clear();
        lastFetchTime = null;
        System.out.println("[MEDITLINK] Déconnexion réussie, cache vidé");
        return "Déconnexion réussie.";
    }

    // Méthodes utilitaires
    public List<Commande> getCommandesStorage() {
        return new ArrayList<>(commandesStorage);
    }

    public Optional<Commande> getCommandeByExternalId(String id) {
        return commandesStorage.stream()
                .filter(c -> id.equals(c.getExternalId()))
                .findFirst();
    }

    public void clearCommandesStorage() {
        commandesStorage.clear();
        lastFetchTime = null;
        System.out.println("[MEDITLINK] Cache vidé manuellement");
    }

    public int getCacheSize() {
        return commandesStorage.size();
    }

    public LocalDate getLastFetchTime() {
        return lastFetchTime;
    }
}