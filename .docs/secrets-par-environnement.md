# Secrets lus dans l'environnement

Mesuré le 2026-09-15 sur le banc CloudNet, jar `aspaper-paperclip-26.2.build.3`.

Un serveur orchestré n'est pas propriétaire de ses secrets : son répertoire est recopié d'un modèle
à chaque démarrage, et ce modèle est lu par tout ce qui sait lire un fichier. Écrire le mot de passe
RCON dans le `server.properties` d'un modèle CloudNet, c'est le publier à tous les services que ce
modèle produira.

## Ce que le fork ajoute

Une propriété de `server.properties` dont la valeur vaut **exactement** `${env:NOM}` est lue dans
l'environnement du processus.

```properties
enable-rcon=true
rcon.port=30250
rcon.password=${env:BTC_RCON_PASSWORD}
```

Le correctif est posé sur `Settings.getStringRaw`, seul lecteur de `server.properties`
(`aspaper-server/minecraft-patches/features/0006-…`). Il couvre donc **toutes** les propriétés, pas
seulement celles qui portent un secret : `rcon.password`, `management-server-secret`,
`management-server-tls-keystore-password`, et n'importe quelle autre.

Le secret de forwarding Velocity n'est pas concerné : Paper le lit déjà dans `PAPER_VELOCITY_SECRET`,
en amont, sans correctif de notre part.

## Les deux propriétés qui comptent

**Le renvoi survit à l'écriture.** `server.properties` est réécrit à chaque démarrage. Sans
précaution, la valeur résolue y serait recopiée en clair dès le premier lancement, et le secret
serait sur le disque du service — donc dans toute sauvegarde et tout déploiement de modèle.
L'invariant tenu par le correctif est que la table de propriétés ne contient **jamais** une valeur
résolue ; ce qui repart sur le disque est toujours `${env:NOM}`.

Vérifié sur le banc : après démarrage, le `server.properties` du service contenait
`rcon.password=${env\:BTC_RCON_PASSWORD}` (l'échappement du `:` est celui de `Properties.store`).

**Une variable absente ne vaut pas la chaîne littérale.** Si `NOM` n'est pas défini ou est vide, la
propriété est rendue *absente* et une erreur est journalisée :

```
ERROR: Property 'rcon.password' reads environment variable 'BTC_RCON_PASSWORD', which is not set.
       Treating the property as absent.
```

La valeur par défaut s'applique alors — pour `rcon.password`, la chaîne vide, ce qui **désactive**
RCON. C'est délibéré : un service qui tourne avec un mot de passe que personne ne connaît est pire
qu'un service sans RCON.

## Côté CloudNet

La variable se déclare sur la tâche, dans `processConfiguration.environmentVariables` :

```json
"processConfiguration": {
  "environmentVariables": {
    "BTC_RCON_PASSWORD": "…"
  }
}
```

puis `tasks reload` en console. Le modèle, lui, ne contient que le renvoi.

## Ce qui a été mesuré

| | |
|---|---|
| RCON démarre | `RCON running on 127.0.0.1:30250` — donc le mot de passe n'était pas vide |
| Le bon secret authentifie | `list` exécuté, réponse du serveur |
| Un mauvais secret est refusé | authentification rejetée |
| **Le renvoi littéral est refusé** | le serveur n'a pas pris `${env:BTC_RCON_PASSWORD}` pour mot de passe |
| Le disque du service | contient toujours le renvoi, jamais le secret |

Le troisième point est celui qui distingue un correctif qui marche d'un correctif qui ne fait rien :
sans lui, un serveur qui accepte la chaîne littérale passerait les deux premiers tests par accident.

## Régénérer le correctif

Le patch se régénère depuis l'arbre de travail `aspaper-server/src/minecraft/java`, qui est un dépôt
git : y commiter la modification, puis

```powershell
.\gradlew.bat :aspaper-server:rebuildMinecraftFeaturePatches -x :aspaper-server:rebuildMinecraftSourcePatches
```

`rebuildMinecraftSourcePatches` est une piste morte, voir `upstream-maintenance.md`.
