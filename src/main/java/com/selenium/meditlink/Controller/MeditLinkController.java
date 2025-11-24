package com.selenium.meditlink.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.selenium.meditlink.Entity.Commande;
import com.selenium.meditlink.Services.MeditLinkSeleniumService;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/meditlink")
@CrossOrigin(origins = "*")
public class MeditLinkController {

    @Autowired
    private MeditLinkSeleniumService meditLinkService;

    @PostMapping("/login")
    public ResponseEntity<String> login() {
        try {
            String result = meditLinkService.login();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur lors de la connexion: " + e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        try {
            String result = meditLinkService.logout();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur lors de la déconnexion: " + e.getMessage());
        }
    }

    @GetMapping("/commandes")
    public ResponseEntity<List<Commande>> fetchCommandes() {
        try {
            List<Commande> commandes = meditLinkService.fetchCommandes();
            return ResponseEntity.ok(commandes);
        } catch (Exception e) {
            // Retourne le cache même en cas d'erreur
            List<Commande> cachedCommandes = meditLinkService.getCommandesStorage()
                    .stream()
                    .limit(6)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(cachedCommandes);
        }
    }

    @GetMapping("/commandes/{caseNumber}")
    public ResponseEntity<Commande> getCommande(@PathVariable String caseNumber) {
        try {
            Optional<Commande> commande = meditLinkService.getCommandeByExternalId(caseNumber);
            return commande.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        try {
            boolean isLoggedIn = meditLinkService.isLoggedIn();
            int cacheSize = meditLinkService.getCacheSize();
            return ResponseEntity.ok("Connecté: " + isLoggedIn + " | Commandes en cache: " + cacheSize);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur lors de la vérification du statut");
        }
    }

    @PostMapping("/download/{externalId}")
    public ResponseEntity<String> download3dScan(@PathVariable String externalId) {
        try {
            boolean success = meditLinkService.download3dScan(externalId);
            if (success) {
                return ResponseEntity.ok("Téléchargement initié pour la commande: " + externalId);
            } else {
                return ResponseEntity.internalServerError()
                        .body("Échec du téléchargement pour la commande: " + externalId);
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur lors du téléchargement: " + e.getMessage());
        }
    }
}