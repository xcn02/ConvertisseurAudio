# Convertisseur 0.9.1 — version de test

Base : archive V0.9.0. Aucun module fonctionnel de cette base n'a été volontairement supprimé. Les commandes globales et la fermeture automatique au verrouillage, absentes de la V0.9.0, ne sont pas réintroduites. La notification de capture demeure identifiable.

## Audio
- Original : moteur MediaRecorder existant, deux qualités AAC et prétraitement Android indépendant conservés.
- Léger, Équilibré, Renforcé, Personnalisé : nouvelle chaîne AudioRecord / traitement PCM / MediaCodec AAC / MediaMuxer M4A.
- Réduction adaptative quatre bandes, filtre contre les grondements, égalisation vocale, gain automatique doux optionnel et limiteur. Les pourcentages indiquent la force du filtre, pas une réduction mesurée. Ce n'est ni RNNoise ni une séparation des voix.
- Paramètres personnalisés : bruit, clarté, gain, grondements. Le profil prend effet à la prochaine session. Les profils numériques sont expérimentaux, notamment avec les casques Bluetooth.
- Arrêt : finalisation asynchrone du fichier en mode numérique. Le bouton Arrêt ne demande pas de confirmation. Le panneau Retour conserve les choix sauvegarder, abandonner, reprendre.
- Améliorer un fichier mono depuis le lecteur : traitement hors ligne, préécoute original/traité, création explicite d'une copie AAC. Le fichier original n'est jamais écrasé. Les fichiers temporaires sont supprimés après annulation ou sauvegarde.

## Interface
- Fond anthracite, accents bleu ardoise, chronomètre léger, icônes vectorielles centrées, contrôles circulaires et barres de navigation fixes.
- Bibliothèque compacte, durée/date, recherche par nom, lecture rapide, lecteur complet, sélection multiple, actions groupées fixées en bas et corbeille.
- Lecteur : forme d'onde avec fenêtre temporelle élargie, curseur central, temps actuel/total, +/-10s, vitesse, repères, amélioration et partage.
- Convertisseur, paramètres et confidentialité conservés.

## Vérification
- Test isolé JVM du filtre : le profil sans traitement ne modifie pas le signal PCM. Sur une sinusoïde synthétique de 70 Hz, gain automatique désactivé, RMS original 2828, profil léger 918, profil renforcé 210 (unités PCM). Cela ne démontre pas la qualité de séparation de la voix en conditions réelles.
- XML analysés et références aux ressources vérifiées. Archive ZIP contrôlée.
- Pas de SDK Android dans l'environnement : compilation complète, rendu réel, codecs, pause/reprise, routage Bluetooth et qualité en circulation NON validés.
- Pas de RNNoise, pas de découpage audio (déjà absent de la V0.9.0), pas de test microphone autonome. Le bouton essai ouvre l'enregistreur ; aucun enregistrement automatique.

## Build
Version 0.9.1 / code 15. Workflow GitHub Actions signé conservé, nom d'APK actualisé, quatre secrets JKS inchangés.
