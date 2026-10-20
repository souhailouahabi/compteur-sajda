# Compteur Sajda 🕌

Application Android qui compte les sajdas (prosternations) pendant la prière grâce au **capteur de proximité** du téléphone. 2 sajdas = 1 rak'a.

## Fonctionnement

1. Choisissez la prière : Fajr (2), Dhuhr (4), Asr (4), Maghrib (3), Isha (4) ou Libre.
2. Appuyez sur **Démarrer**. Vous avez 3 secondes pour poser le téléphone.
3. Posez le téléphone **au sol, écran vers le haut, le haut du téléphone vers vous**, à l'endroit où se pose votre front. Le capteur se trouve en haut de l'écran, près de la caméra avant.
4. Priez normalement. Le téléphone vibre :
   - **1 vibration courte** : 1ʳᵉ sajda de la rak'a
   - **2 vibrations courtes** : rak'a terminée
   - **2 vibrations longues** : prière terminée (toutes les rak'at faites)
5. Les boutons **+1 / −1** servent à corriger le compte. **Remettre à 0** recommence.

Pendant le comptage, l'écran reste allumé avec une luminosité très faible pour ne pas gêner.

## Installer l'APK

Ouvrez **Releases** (colonne de droite de la page du dépôt) depuis votre téléphone Android, téléchargez `CompteurSajda.apk`, ouvrez-le et autorisez « Installer des applications inconnues » si Android le demande.

## Réglages (fichier `MainActivity.kt`)

| Paramètre | Valeur | Rôle |
|---|---|---|
| `dureeMinProche` | 500 ms | durée minimale « tête proche » pour compter une sajda |
| `dureeMinLoin` | 900 ms | un éloignement plus court est ignoré (tremblement) |
| `delaiDemarrage` | 3000 ms | temps pour poser le téléphone après Démarrer |

## Limites

- Le téléphone doit avoir un capteur de proximité (presque tous en ont un). Sinon, seul le comptage manuel est disponible.
- Le front ne doit pas forcément toucher le téléphone : une approche à moins de quelques centimètres du capteur suffit. Si les sajdas ne sont pas détectées, rapprochez le téléphone de l'endroit du front.
