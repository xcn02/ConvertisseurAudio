# Convertisseur V0.5

- 22 conversion families including HVAC air flow, pressure, thermal power, energy, heat transfer coefficients, density and viscosity.
- Converter menu moved to the top right.
- Recorder is its own mini-app: controls, live microphone level, library and a separate settings page.
- Automatic stop is configured in minutes.
- Visible stop-and-save action remains available in the app and its Android notification.
- No hidden hardware-button stop/kill behavior. Android cannot guarantee a force-stop of its own process; normal service shutdown is used.
- R8 shrinking and optimization enabled; obfuscation disabled.
- GitHub Actions workflow retained.

Build: GitHub Actions > Build Android APK. No local Android SDK build verification was available.


## V0.5.1 — Arrêt rapide

Dans Réglages audio → Commandes rapides, activer « Double appui Volume − : arrêter et sauvegarder ». Disponible uniquement sur l'écran Enregistreur, application au premier plan, durant un enregistrement actif ou en pause. Deux appuis à moins de 650 ms d'intervalle. Le service finalise le fichier normalement.

Avec l'écran verrouillé, utiliser l'action « Arrêter » de la notification Android. Aucune interception des touches de volume en arrière-plan.
