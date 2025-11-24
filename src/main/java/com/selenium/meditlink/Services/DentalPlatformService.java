package com.selenium.meditlink.Services;

import java.util.List;

import com.selenium.meditlink.Entity.Commande;

public interface DentalPlatformService {

    String login();

    List<Commande> fetchCommandes();

    String logout();

    boolean isLoggedIn();
}