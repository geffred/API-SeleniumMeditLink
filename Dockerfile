# 🏗️ Étape de construction
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copier et télécharger les dépendances Maven
COPY pom.xml .
RUN mvn dependency:go-offline

# Copier le code source et compiler
COPY src ./src
RUN mvn package -DskipTests

# 🚀 Étape d'exécution
FROM eclipse-temurin:21-jammy

# Définir le dossier de travail
WORKDIR /app

# Copier le jar depuis l'étape précédente
COPY --from=build /app/target/*.jar app.jar

# ✅ Installer Chrome et les dépendances nécessaires pour Selenium
# procps fournit ps/pkill, indispensables au nettoyage des processus Chrome
RUN apt-get update && apt-get install -y \
    wget \
    gnupg \
    procps \
    && wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome-keyring.gpg] http://dl.google.com/linux/chrome/deb/ stable main" | tee /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update && apt-get install -y \
    google-chrome-stable \
    fonts-freefont-ttf \
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    libasound2 \
    && rm -rf /var/lib/apt/lists/* \
    && rm -rf /var/cache/apt/*

# Vérifier la version de Chrome installée
RUN google-chrome-stable --version

# Créer un utilisateur non-root pour plus de sécurité
RUN groupadd -r selenium && useradd -r -g selenium -G audio,video selenium \
    && mkdir -p /home/selenium/Downloads \
    && chown -R selenium:selenium /home/selenium \
    && chown -R selenium:selenium /app

# Passer à l'utilisateur non-root
USER selenium

# Exposition du port de l'application Spring Boot
EXPOSE 8080

# Démarrage de l'application.
# MaxRAMPercentage=50 : la JVM ne prend que la moitié de la RAM du conteneur,
# l'autre moitié reste disponible pour la mémoire native de Chrome/Chromedriver.
# ExitOnOutOfMemoryError : un OOM heap produit un arrêt net et journalisé
# plutôt qu'un service zombie.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=50.0", \
    "-XX:InitialRAMPercentage=25.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+UseG1GC", \
    "-jar", "app.jar"]