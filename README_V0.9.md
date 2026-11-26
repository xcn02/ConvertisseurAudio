# V0.9.0 (versionCode 14) — test
Base V0.8 et fichiers MainActivity/RecorderService transmis le 26 novembre.
- Bibliothèque compacte, sélection multiple et sélection globale, durées courtes et cache.
- Lecteur: bouton rond fixe, actions de fichier dans le menu, temps séparés.
- Retour depuis la capture: pause et panneau avec nom, sauvegarde, abandon confirmé et reprise. Arrêt normal: sauvegarde immédiate.
- Prétraitement vocal optionnel via VOICE_COMMUNICATION, dépendant du matériel; aucun RNNoise ni traitement permanent des anciens fichiers.
- Interception globale des touches, service d’accessibilité et fermeture automatique pendant capture retirés. Notification identifiable et commandes conservées.
- Workflow manuel signé via les quatre secrets GitHub habituels; aucun JKS dans l’archive.
- SDK Android et Gradle absents du conteneur: compilation et tests appareil non réalisés.
