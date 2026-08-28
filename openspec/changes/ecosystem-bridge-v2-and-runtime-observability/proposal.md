## Pourquoi

Le profil JFR validé du 16/08 montre que le cœur serveur n'est plus le seul poste dominant :
ServerScoreboard (2,4 %), Koin Scope.resolve (2,2 %), LaserGridDisplay.traceRay (2,1 %),
CraftEngine PluginChannelEncoder (4,2 %), LevelParticleListener (1,7 %) et les chunks (6,0 %)
restent à instruire. Le chantier de charge est toutefois déjà à 33,8 ms de médiane à 50 joueurs ;
aucun nouveau gain ne doit être annoncé avant une mesure A/B/A'.

Le bridge BTC-CORE/BTCVelocity présente en plus un risque de contrôle : origine Player acceptée,
JSON sans enveloppe d'authentification, absence d'ACK/idempotence et preload pouvant annoncer
WorldLoaded sans chargement réel.

## Ce qui change

- Introduire le contrat bridge V2 partagé : origine backend, cible autorisée, correlationId,
  expiration, accusé de réception, rejet borné et déduplication.
- Fermer le chemin backend/client du bridge et corriger la sémantique de preload.
- Exposer un manifeste de compatibilité runtime : fork, variantes, versions, hooks et persistence.
- Exposer une télémétrie opt-in par extension : résolutions, scheduler, paquets/displays, backoff
  et temps de handlers, sans journaliser de données sensibles.
- Versionner le contrat de banc : artefacts, 50 SoulFire, 50 SlimeWorld, A/B/A', p50/p95/p99,
  nettoyage de logs et réserves de méthode.

### Coût de gameplay

Le défaut ne modifie ni règles vanilla, ni fréquence de ticks, ni rendu. Les budgets de displays,
les tickets de chunks et les réglages de gameplay sont hors de ce delta et nécessitent une décision
séparée si une réduction visible est proposée.

### Non-objectifs

- Ne pas annoncer un gain MSPT avant campagne mesurée.
- Ne pas câbler aveuglément ScoreboardOptimization tant que son call-path n'est pas prouvé.
- Ne pas optimiser LaserGrid ou CraftEngine sans compteurs de paquets et profil ciblé.
- Ne pas déployer ni modifier les secrets de production dans ce changement.
- Ne pas corriger Tycoon ou choisir son spawn de gameplay ici.

## Impact

- bridge-plugin BTC-CORE et contrat partagé avec BTCVelocity.
- API/configuration de compatibilité et telemetry runtime.
- Documentation et scripts de contrat de banc, sans lancer le banc.
- Tests unitaires de codec, auth, déduplication, manifest et limites.
