# Carnet de rêves

Application Android pour pratiquer le rêve lucide : journal des rêves (récit dicté au clavier, dessin,
lucidité, netteté, signes de rêve, émotions, recherche, statistiques), préparation de la nuit
(réveil programmé + MILD ou SSILD, réveil du téléphone pré-rempli), tests de réalité en journée,
et synthèse des études scientifiques avec leurs sources.

**Télécharger l'application :** https://github.com/lbrasselet-spec/Carnet-de-reves/releases/download/apk/Carnet-de-reves.apk
(Android 10 ou plus récent ; aucune autorisation sensible demandée).

- `index.html` : l'application (fonctionne aussi seule, dans un navigateur, pour les essais).
- `android/` : l'enveloppe Android (réveil, sauvegarde/import de fichier, liens externes) et son script
  de compilation `build.sh` (outils Ubuntu, sans Android Studio).
- `.github/workflows/apk.yml` : compilation, publication et essai automatique sur un téléphone virtuel
  Android 15 à chaque modification ; le résultat de l'essai s'affiche sur la page de téléchargement.

Les rêves restent sur le téléphone : rien n'est envoyé sur Internet. Le bouton « Sauvegarder mes rêves »
enregistre une copie (.json) à l'endroit choisi ; « Importer une sauvegarde » la recharge.

La clé de signature (`android/keystore/`) est créée à la première compilation et conservée dans ce dépôt.
Ne pas la supprimer : sans elle, les mises à jour ne s'installeraient plus par-dessus l'appli existante.
Le dépôt étant public, n'installer l'appli que depuis le lien de téléchargement ci-dessus.
