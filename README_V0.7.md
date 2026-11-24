# Convertisseur 0.7.0 — version de développement

Base : 0.6.2. VersionCode 11. GitHub Actions : assembleRelease, R8 sans obfuscation.

- Écran audio : chronomètre, forme d’onde centrée, bouton circulaire unique, pause et bibliothèque.
- Lecteur : forme d’onde défilante, commandes horizontales et vitesse.
- Bibliothèque : menu contextuel, sélection multiple par appui long, partage groupé pour MediaStore, corbeille MediaStore à partir d’Android 11 (durée de conservation déterminée par Android). Sur les anciens fichiers internes, suppression définitive après confirmation.
- Source audio : sélection de périphérique d’entrée via MediaRecorder à partir d’Android 9. Affichage de la route effective lorsque disponible. Bluetooth expérimental : le casque doit exposer un périphérique d’entrée compatible ; le profil SCO/HFP peut nécessiter une adaptation spécifique selon appareil. Aucun routage Bluetooth garanti.
- Thème système clair/sombre, palette ardoise. Notification audio explicite, canal basse importance sans vibration ni son (les réglages d’un canal existant restent sous contrôle Android).
- Convertisseur : copie du résultat, présentation et palette adaptées.
- Pas de retour automatique destiné à masquer une capture active. Les indicateurs système restent actifs.
- Marqueurs, découpage, lecture intégrée dans la liste, recherche et favoris restent à développer. 

## Validation
Le SDK Android et Gradle ne sont pas installés dans l’environnement de préparation. Compilation et tests sur appareil à effectuer via GitHub Actions. Vérifier en particulier le routage Bluetooth, la corbeille et la lecture de fichiers longs.
