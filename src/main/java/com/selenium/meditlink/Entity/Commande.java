package com.selenium.meditlink.Entity;

import java.time.LocalDate;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Commande {
    private Long id;
    private String externalId; // Changé de Long à String pour correspondre à Playwright
    private Long cabinetId;
    private String refPatient;
    private LocalDate dateReception;
    private String details;
    private Plateforme plateforme;
    private String cabinet;
    private String commentaire;
    private LocalDate dateEcheance;
    private Boolean vu;
    private String typeAppareil;
    private String numeroSuivi;
    private String adresseDeLivraison;
    private String adresseDeFacturation;
    private String caseMasterId;
    private StatutCommande statut;
    private String telephone;
    private String typeTraitement;

    // Constructeurs
    public Commande() {
        this.vu = false;
        this.statut = StatutCommande.EN_ATTENTE;
    }

    public Commande(String externalId, String refPatient, LocalDate dateReception, String details,
            Plateforme plateforme, String cabinet, String commentaire, LocalDate dateEcheance,
            Boolean vu, Long cabinetId, String hash_lower, String hash_upper) {
        this.externalId = externalId;
        this.refPatient = refPatient;
        this.dateReception = dateReception;
        this.details = details;
        this.plateforme = plateforme;
        this.cabinet = cabinet;
        this.commentaire = commentaire;
        this.dateEcheance = dateEcheance;
        this.vu = vu != null ? vu : false;
        this.statut = StatutCommande.EN_ATTENTE;
        this.cabinetId = cabinetId;
    }

    // Constructeur avec tous les champs
    public Commande(String externalId, String refPatient, LocalDate dateReception, String cabinet,
            String commentaire, LocalDate dateEcheance, String adresseDeLivraison,
            String telephone, String typeTraitement) {
        this.externalId = externalId;
        this.refPatient = refPatient;
        this.dateReception = dateReception;
        this.cabinet = cabinet;
        this.commentaire = commentaire;
        this.dateEcheance = dateEcheance;
        this.adresseDeLivraison = adresseDeLivraison;
        this.telephone = telephone;
        this.typeTraitement = typeTraitement;
        this.vu = false;
        this.statut = StatutCommande.EN_ATTENTE;
        this.plateforme = Plateforme.MEDITLINK;
    }

    // Getters et Setters
    public Long getId() {
        return id;
    }

    public String getCaseMasterId() {
        return caseMasterId;
    }

    public void setCaseMasterId(String caseMasterId) {
        this.caseMasterId = caseMasterId;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getRefPatient() {
        return refPatient;
    }

    public void setRefPatient(String refPatient) {
        this.refPatient = refPatient;
    }

    public LocalDate getDateReception() {
        return dateReception;
    }

    public void setDateReception(LocalDate dateReception) {
        this.dateReception = dateReception;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Plateforme getPlateforme() {
        return plateforme;
    }

    public void setPlateforme(Plateforme plateforme) {
        this.plateforme = plateforme;
    }

    public String getCabinet() {
        return cabinet;
    }

    public void setCabinet(String cabinet) {
        this.cabinet = cabinet;
    }

    public String getCommentaire() {
        return commentaire;
    }

    public void setCommentaire(String commentaire) {
        this.commentaire = commentaire;
    }

    public LocalDate getDateEcheance() {
        return dateEcheance;
    }

    public void setDateEcheance(LocalDate dateEcheance) {
        this.dateEcheance = dateEcheance;
    }

    public Boolean getVu() {
        return vu;
    }

    public void setVu(Boolean vu) {
        this.vu = vu;
    }

    public StatutCommande getStatut() {
        return statut;
    }

    public void setStatut(StatutCommande statut) {
        this.statut = statut;
    }

    public String getTypeAppareil() {
        return typeAppareil;
    }

    public void setTypeAppareil(String typeAppareil) {
        this.typeAppareil = typeAppareil;
    }

    public String getNumeroSuivi() {
        return numeroSuivi;
    }

    public void setNumeroSuivi(String numeroSuivi) {
        this.numeroSuivi = numeroSuivi;
    }

    public String getAdresseDeLivraison() {
        return adresseDeLivraison;
    }

    public void setAdresseDeLivraison(String adresseDeLivraison) {
        this.adresseDeLivraison = adresseDeLivraison;
    }

    public String getAdresseDeFacturation() {
        return adresseDeFacturation;
    }

    public void setAdresseDeFacturation(String adresseDeFacturation) {
        this.adresseDeFacturation = adresseDeFacturation;
    }

    public Long getCabinetId() {
        return cabinetId;
    }

    public void setCabinetId(Long cabinetId) {
        this.cabinetId = cabinetId;
    }

    public String getTelephone() {
        return telephone;
    }

    public void setTelephone(String telephone) {
        this.telephone = telephone;
    }

    public String getTypeTraitement() {
        return typeTraitement;
    }

    public void setTypeTraitement(String typeTraitement) {
        this.typeTraitement = typeTraitement;
    }

    @JsonIgnore
    // Méthodes utilitaires
    public boolean isTerminee() {
        return this.statut == StatutCommande.TERMINEE;
    }

    @JsonIgnore
    public boolean isEnCours() {
        return this.statut == StatutCommande.EN_COURS;
    }

    @JsonIgnore
    public boolean isExpediee() {
        return this.statut == StatutCommande.EXPEDIEE;
    }

    @JsonIgnore
    public boolean isAnnulee() {
        return this.statut == StatutCommande.ANNULEE;
    }

    public boolean hasNumeroSuivi() {
        return this.numeroSuivi != null && !this.numeroSuivi.trim().isEmpty();
    }

    @JsonIgnore
    public boolean isRead() {
        return Boolean.TRUE.equals(vu);
    }

    // Méthode pour mettre à jour avec les données de prescription
    public void updateFromPrescriptionData(String refPatient, LocalDate dateReception, String commentaire,
            LocalDate dateEcheance, String adresseDeLivraison,
            String cabinet, String telephone, String typeTraitement) {
        if (refPatient != null)
            this.refPatient = refPatient;
        if (dateReception != null)
            this.dateReception = dateReception;
        if (commentaire != null)
            this.commentaire = commentaire;
        if (dateEcheance != null)
            this.dateEcheance = dateEcheance;
        if (adresseDeLivraison != null)
            this.adresseDeLivraison = adresseDeLivraison;
        if (cabinet != null)
            this.cabinet = cabinet;
        if (telephone != null)
            this.telephone = telephone;
        if (typeTraitement != null)
            this.typeTraitement = typeTraitement;
    }

    @Override
    public String toString() {
        return "Commande{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", refPatient='" + refPatient + '\'' +
                ", dateReception=" + dateReception +
                ", plateforme=" + plateforme +
                ", cabinet='" + cabinet + '\'' +
                ", statut=" + statut +
                ", typeAppareil='" + typeAppareil + '\'' +
                ", numeroSuivi='" + numeroSuivi + '\'' +
                ", dateEcheance=" + dateEcheance +
                ", vu=" + vu +
                ", adresseDeLivraison='" + adresseDeLivraison + '\'' +
                ", adresseDeFacturation='" + adresseDeFacturation + '\'' +
                ", telephone='" + telephone + '\'' +
                ", typeTraitement='" + typeTraitement + '\'' +
                ", caseMasterId='" + caseMasterId + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Commande commande = (Commande) o;
        return Objects.equals(externalId, commande.externalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(externalId);
    }
}