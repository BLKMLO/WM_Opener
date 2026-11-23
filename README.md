# WM Opener

Application Android qui ouvre automatiquement les paquets de cartes sur wiki-masters.com, en arrière-plan.

## Fonctionnement
- Vous vous connectez vous-même dans la WebView de l'appli. La connexion passe par un captcha Cloudflare Turnstile, donc elle ne peut pas être automatisée. La session est ensuite gardée dans les cookies.
- Toutes les *N* minutes (15 au minimum, c'est la limite de WorkManager sous Android), une WebView invisible charge `/pulls` et exécute `app/src/main/assets/opener.js` :
  1. elle clique le bouton dont le texte correspond à la regex « ouverture » ;
  2. elle clique les boutons qui suivent (révéler, continuer…) jusqu'à ce qu'il n'y en ait plus ;
  3. elle recommence jusqu'au nombre maximal de paquets fixé.
- Un bouton dont le texte correspond à la regex « exclusion » (achats, gemmes, €, pubs…) n'est **jamais** cliqué.
- Une notification s'affiche quand des paquets ont été ouverts, ou quand la session a expiré.

## Première utilisation
1. Installez l'APK (autorisez les « sources inconnues »).
2. Connectez-vous dans l'appli.
3. Appuyez sur **Autorisations** : acceptez l'exclusion de l'optimisation de batterie et la superposition (recommandée).
4. Sur la page des paquets, appuyez sur **Diagnostic** : la liste des boutons visibles s'affiche. Si le bouton d'ouverture ne correspond pas à la regex par défaut, corrigez-la dans **Réglages**.
5. Faites un essai avec **Ouvrir maintenant** (visible), puis avec **Test arrière-plan**.
6. Activez **Auto**.

## Build
`./gradlew assembleRelease` (il faut le SDK Android 35). Le workflow GitHub Actions `.github/workflows/build.yml` produit l'APK sous forme d'artefact.
