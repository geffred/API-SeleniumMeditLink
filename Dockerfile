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
#
# tini : init minimal utilisé comme PID 1. INDISPENSABLE.
# Sans lui la JVM est PID 1 et ne fait jamais wait() sur les enfants Chrome
# orphelins (renderers, crashpad, zygote). Ils restent zombies indéfiniment,
# chacun occupant un slot PID. Sur Railway la limite est de 1000 PID : après
# ~40 h d'uptime le conteneur atteint 1000/1000, plus aucun fork ni
# pthread_create n'est possible, et TOUT scraping meurt sur
# « OutOfMemoryError: unable to create native thread ».
# Un zombie ne peut pas être tué : pkill est totalement inopérant ici.
RUN apt-get update && apt-get install -y \
    wget \
    gnupg \
    procps \
    tini \
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
#
# tini -g -- : tini devient PID 1 et récolte (wait) tous les processus
#   orphelins, y compris les enfants de Chrome. C'est LE correctif de fond
#   contre l'accumulation de zombies qui saturait la limite de PID.
#   -g propage aussi les signaux à tout le groupe de processus, donc un
#   SIGTERM Railway arrête proprement la JVM ET les Chrome encore ouverts.
#
# MaxRAMPercentage=35 : 8 Go de heap sur un conteneur de 16 Go étaient
#   inutiles (heap mesuré à 25 Mo / 8108 Mo, soit 0 %) et privaient Chrome
#   de mémoire native. 35 % suffit très largement et laisse respirer Chrome.
# Xss512k : divise par deux la pile de chaque thread, double la marge avant
#   épuisement des ressources natives.
# ExitOnOutOfMemoryError : arrêt net sur OOM heap plutôt qu'un service zombie.
#   Attention : ce flag NE couvre PAS « unable to create native thread » —
#   c'est PidMonitorService qui prend le relais pour ce cas.
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "java", \
    "-XX:MaxRAMPercentage=35.0", \
    "-XX:InitialRAMPercentage=15.0", \
    "-Xss512k", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+UseG1GC", \
    "-jar", "app.jar"]
