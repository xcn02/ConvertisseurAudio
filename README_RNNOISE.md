# 0.9.1.1 — RNNoise (traitement manuel)
Depuis le lecteur, Améliorer l’audio : trois options RNNoise (35, 65, 100 % de mélange humide). Les quatre options DSP précédentes restent disponibles. Le fichier original reste intact, la copie est enregistrée uniquement après validation. Aucun changement au moteur de capture.
Le projet télécharge RNNoise 0.2 (archive officielle avec modèle, SHA256 fixé) lors du build CMake. NDK 28.2 et CMake 3.22.1 installés par GitHub Actions. L’application n’a pas besoin d’Internet. Sortie mono 48 kHz, conversion linéaire si nécessaire. Les niveaux sont des taux de mélange, pas des mesures de suppression du bruit.
Source : https://github.com/xiph/rnnoise/releases/tag/v0.2
Compilation Android et qualité audio sur appareil non vérifiées dans cet environnement. Le téléchargement de la dépendance n’a pas été possible localement. 
