## WM Opener 1.2.0

- **Les 5 cartes de chaque paquet sont vues** : sur l'écran « Carte i / 5 », l'appli attend que le bouton « Continuer » apparaisse (jusqu'à 15 s par carte), le clique, puis passe à la carte suivante. Elle n'ouvre le paquet suivant qu'une fois les 5 cartes vues. En dernier recours, elle touche la carte.
- **Jamais de clic sur « Ajouter aux favoris »** (qui verrouille la carte), ni sur défausse ou vente : ajoutés aux boutons interdits.
- **Passages longs** : jusqu'à 9 min par passage (10 paquets × 5 cartes). Si le délai est dépassé, les paquets déjà ouverts restent dans l'historique.

---

## WM Opener 1.1.1

- **Superposition retirée** : Android la bloque pour les applis installées hors Play Store (« paramètre restreint »), et l'ouverture en arrière-plan fonctionne sans elle (vérifié : 4 paquets ouverts, compteur passé de 10 à 6). Une autorisation sensible en moins.
- **Cartes** : sur l'écran « Carte i / 5 », tout badge de rareté visible est pris en compte, même sans indice dans son style.
- **Déroulé** : la première fois que l'écran « Carte i / 5 » apparaît, ses boutons, badges et titres sont notés dans le bilan (« Ouvrir ici ») et dans le journal, pour ajuster la détection.

---

## WM Opener 1.1.0

### Corrigé
- **Faux décomptes** : un paquet n'est plus compté sur un simple clic. Il faut que le site le confirme (baisse du compteur « X / 10 paquets disponibles » ou apparition de l'écran « Carte 1 / 5 »). Un clic sans effet est signalé comme tel.
- **Écran « Carte i / 5 »** : les boutons ronds sans texte sont maintenant reconnus par leur icône (flèche « suivant »). Le bouton de fermeture (✕) n'est jamais cliqué.
- **Cartes non repérées** : la détection accepte les badges de rareté par style ou par classe.
- L'onglet Jeu se recharge après un passage en arrière-plan, pour afficher le vrai compteur.

### Nouveau
- **Diagnostic** affiche le compteur de paquets, l'écran de révélation, l'état des animations et les boutons-icônes.
- Bouton **Ouvrir ici** dans le Diagnostic : lance une ouverture dans l'onglet Jeu, sous vos yeux, avec le déroulé pas à pas.
- Journal technique : le déroulé de chaque passage et l'utilisation (ou non) de la superposition.

---

## WM Opener 1.0.1

### Correctif
- Android 15 : l'en-tête passait sous la barre d'état et les onglets sous les boutons de navigation. Les marges système sont maintenant réservées (et le clavier remonte l'écran).

Mise à jour : installez par-dessus la 1.0.0 signée (même clé, l'historique et la connexion sont conservés).

---

## WM Opener 1.0.0

Ouverture automatique des paquets wiki-masters.com en arrière-plan.

**Installation** : téléchargez `WM_Opener-vX.Y.Z.apk` ci-dessous, ouvrez-le sur le téléphone et autorisez l'installation depuis cette source.

### Contenu
- Onglets **Jeu** (le site), **Auto** (pilotage) et **Historique**.
- Mode automatique (15 min à 4 h) avec une notification permanente tant qu'il est actif, et une notification non effaçable pendant chaque ouverture.
- Historique des ouvertures avec les cartes repérées. On peut supprimer une entrée (appui long) ou tout effacer.
- Diagnostic : liste les boutons de la page et indique ceux que l'appli cliquerait.

### Sécurité
- Aucun clic sur un bouton d'achat, de monnaie premium ou de publicité (liste modifiable). Si cette liste est invalide, rien n'est cliqué.
- L'appli reste sur wiki-masters.com : les liens externes s'ouvrent dans le navigateur, et le pont JavaScript ne répond qu'au site.
- Session exclue des sauvegardes Android.

### Limites connues
- Le texte des boutons de la page des paquets n'a pas pu être vérifié (page réservée aux comptes connectés). Lancez **Diagnostic** au premier usage.
- La détection des cartes s'appuie sur la structure observée sur la page d'accueil du site. Elle est approximative.
- Android impose 15 min minimum entre deux passages automatiques, et peut les retarder en économie d'énergie.
