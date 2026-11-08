# NCT Compteur

Appli compteur vélo aux couleurs de NCT pour le Xcadey Alpha 800 (Android 8.1).

**Installer la dernière version depuis le compteur (Chrome) :**
https://github.com/cedricg21-a11y/nct-compteur/releases/latest/download/nct-compteur.apk

- Capteurs Bluetooth : puissance, cardio, vitesse/cadence (connexion automatique)
- GPS + baromètre, enregistrement `.FIT` dans `/sdcard/NCT/`
- 4 pages : LIVE, EFFORT (W'bal « Réservoir »), GRIMPÉE, TOUR
- 4 boutons physiques configurables au premier lancement

Boutons : START = démarrer/pause · TOUR = nouveau tour (en pause : terminer et enregistrer ; à l'arrêt : reconfigurer les boutons).

Maquettes HTML de référence dans `design/` (aperçu navigateur : `index.html?demo=1`). L’interface de l’appli est dessinée en natif (`Dash.java`) car la ROM Xcadey n’a pas de WebView.
