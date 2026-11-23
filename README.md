# Convertisseur & Audio — Android 0.3.0

Projet Android Studio Kotlin, local, minSdk 26, targetSdk 35.

## Fonctions
- Convertisseur longueur, masse, volume, surface, vitesse, température.
- Paramètres > Modules > Enregistreur audio.
- Enregistrement M4A/AAC, pause/reprise, arrêt automatique, choix de la durée et de la qualité.
- Réactions paramétrables aux branchements et débranchements jack et Bluetooth.
- Service au premier plan, notification explicite Pause / Arrêt, fonctionnement écran éteint lorsque l'enregistrement a été lancé dans l'activité visible.
- Bibliothèque locale, lecture, export via sélecteur système, suppression confirmée.
- Protection des captures et aperçus d'applications récentes via FLAG_SECURE.

## Compiler
1. Ouvrir le dossier `ConvertisseurAudio` dans Android Studio.
2. Installer Android SDK 35 / JDK 17 et synchroniser Gradle.
3. Build > Build APK(s), puis installer le fichier APK généré sur le téléphone.
4. Autoriser microphone et notifications, lancer l'enregistrement depuis l'interface visible.

## Limites connues
- Sources non compilées ni testées sur un appareil dans cet environnement : compilation et tests à réaliser dans Android Studio.
- Les réactions aux périphériques dépendent des événements fournis par Android et des constructeurs, à valider sur téléphone réel.
- Le minuteur utilise le temps effectif ou le temps écoulé. Les paramètres nouveaux s'appliquent à la session suivante.
- Les sessions interrompues par arrêt brutal du système peuvent laisser un fichier illisible ; l'application évite de présenter une sauvegarde ratée comme réussie.
- Le lancement d'enregistrement doit être effectué lorsque l'activité est visible, conformément aux restrictions Android récentes.
- La notification et l'indicateur de confidentialité Android restent visibles. Aucun déguisement trompeur ni masquage de la captation.
- Pas encore de biométrie, de transcription, de renommage ou de suppression automatique.

## Usage responsable
N'enregistrez pas des conversations privées sans base juridique appropriée ni consentement lorsque requis.
