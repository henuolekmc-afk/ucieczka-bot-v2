# Ucieczka Bot 1.1.0 (precyzyjny)

Budowanie (Minecraft 1.21.4, Fabric, Java 21):

    gradle wrapper        # jednorazowo, jesli nie ma gradlew
    ./gradlew build       # gotowy jar: build/libs/ucieczka-bot-1.1.0.jar

Jesli Gradle nie znajdzie `fabric_version`, wybierz aktualny numer dla 1.21.4 na https://fabricmc.net/develop
i wpisz go w gradle.properties.

Stroiki precyzji: stale na poczatku PathBotClient.java (START_REACH, STEER_GAIN, STEER_MAX_DEG, SYNC_*).
Stary plik config/pathbot.json jest w pelni kompatybilny - nie trzeba nagrywac trasy od nowa.
