# Convertisseur 0.10.0 — ARM64

Base : archive 0.9.2 et correctifs YAML/CMake fournis ensuite. VersionCode 18, même applicationId et secrets JKS. ABI arm64-v8a exclusivement.

- Marqueurs directs pendant enregistrement, pause et lecture. Repères sur l'onde, liste en lecture, appui sur le nom pour chercher, appui long pour renommer, croix pour retirer. Anciennes marques CSV conservées.
- Corbeille immédiate depuis la bibliothèque et le panneau Retour : finalisation audio avant déplacement. Restauration disponible. Confirmation uniquement pour suppression définitive. MediaStore sur Android 11+, dossier de corbeille sur Android 10 et stockage interne sur Android 8/9. Si finalisation impossible (fichier trop court), aucun fichier valide ne peut être restauré.
- Raccourcir : deux curseurs de sélection, zone colorée, garder ou retirer la sélection, réencodage AAC mono et préécoute avant sauvegarde d'une copie. Zoom à deux doigts sur l'onde de lecture. Les marqueurs ne sont pas transférés à la copie montée.
- Traitement combiné manuel : RNNoise à intensité réglable, clarté vocale, gain automatique doux, atténuation des grondements. Profils précédents conservés. Progression et annulation du traitement, préécoute A/B à position reprise pour le traitement sans découpage.
- Contrôles de lecture et enregistrement ancrés en bas. Position de lecture mémorisée.

Limites : les écrans de montage et de traitement restent des panneaux natifs, pas une reproduction pixel à pixel de la maquette. Pas de bouton Annuler temporaire après mise à la corbeille (restaurer depuis la corbeille). Pas de favoris ou de nouvelle politique d'expiration ajoutés. Pas de normalisation LUFS ni d'export MP3 ajouté. Aucun changement aux commandes globales.

Validation locale : analyse syntaxique Kotlin (PSI), YAML, Bash, XML. SDK/NDK/Gradle Android absents de l'environnement local : compilation et tests sur appareil non réalisés. Lancer GitHub Actions avec les quatre secrets existants. Vérifier en particulier marqueurs, corbeille/restauration, montage, traitement RNNoise et mise à jour depuis 0.9.2.
