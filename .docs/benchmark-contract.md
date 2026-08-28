# BTC-CORE benchmark contract

Ce contrat décrit une campagne comparable ; il ne remplace pas le protocole opératoire du serveur
et ne lance aucune commande live.

## Manifest obligatoire

Le manifest JSON doit contenir :

- schemaVersion: 1 ;
- runtimeVariant: "btccore" ;
- les chemins et SHA-256 des deux artefacts BTC-CORE déployés ;
- la liste exacte des extensions installées ;
- bots: 50 et worlds: 50 ;
- warmupSeconds >= 60, measurementSeconds >= 90, minSamples >= 30 ;
- protocol: "A/B/A'".

Validation PowerShell :

    .\scripts\benchmark\Test-BenchManifest.ps1 -ManifestPath .\bench\run.json

## Protocole

1. Vérifier que le banc est libre et purger logs/latest.log.
2. Démarrer via cmd.bat détaché selon le protocole serveur validé.
3. Installer exactement l’artefact Typewriter btccore, BTC-CORE et l’ensemble déclaré.
4. Chauffer 60 secondes avant de collecter.
5. Collecter au moins 30 échantillons pendant 90 secondes ou plus.
6. Répéter en A, B, puis A’ sur des redémarrages frais.
7. Retirer les codes §. avant parsing MSPT ; §a signifie que la mesure est verte.
8. Ne jamais piloter la campagne par la réponse RCON list, qui peut être tronquée.

Chaque résultat doit conserver p50, p95, p99, n, variante, commit, empreintes d’artefacts,
extensions, réserves de chauffe et absence de base vanilla. Aucun gain n’est accepté depuis une
mesure unique.
