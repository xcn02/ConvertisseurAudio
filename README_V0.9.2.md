# V0.9.2
Base : V0.9.1.1 RNNoise, versionCode 17.
- Réglages audio > Commandes rapides et navigation : extinction écran (normal, confidentialité, arrêt + accueil, arrêt + fermeture), double Volume moins (désactivé, arrêt, arrêt + fermeture) sur écran enregistreur au premier plan.
- Le service reçoit ACTION_SCREEN_OFF ; arrêt seulement si enregistrement ou pause, pas de capture masquée. L'extinction automatique de l'écran déclenche aussi ce comportement.
- Navigation après confirmation de finalisation du fichier ; aucune fermeture demandée sur échec. Si l'activité a déjà été détruite, aucun écran n'est relancé.
- Notification : action supplémentaire Arrêter et quitter. Pas d'interception globale ni de service d'accessibilité.
- RNNoise, profils vocaux, traitement manuel, signature et autres fichiers conservés.
- Compilation Android et tests physiques non exécutés localement. Vérifier extinction automatique, arrêt PCM asynchrone, microphone Bluetooth, notification, double appui et navigation.
