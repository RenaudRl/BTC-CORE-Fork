## Décisions

### 1. Autorité et ownership

MySQL est la source canonique de l’horodatage et du claim. Une ligne par île porte `island_id`,
`world_name`, `state`, `backend_id`, `fencing_token`, `from_ts`, `to_ts`, `operation_id` et une
version. Le backend obtient un lease par CAS ; tout commit avec un token ancien est rejeté.
Redis et BTCVelocity ne font que transporter ou accélérer des notifications.

Un simple update de timestamp n’est pas exactly-once : un crash entre l’écriture du monde et la
mise à jour MySQL peut produire une perte ou un doublon. Le commit doit donc être lié à une
opération idempotente/journalisée côté Typewriter ; ce dépôt expose le fencing et les événements,
il ne prétend pas rendre un artifact Typewriter transactionnel.

### 2. Chunks, monde et distances

Le cœur distingue :

- rendu : `view-distance`, éventuellement par joueur via Paper ;
- simulation : `simulation-distance` et tickets entity-ticking ;
- progression hors ligne : handler à la reprise, sans chunk tické pendant l’absence.

Le comportement SWM observé interdit de vendre le chunk unload comme optimisation mémoire : le
chunk est converti en `SlimeChunkSkeleton` dans `SlimeInMemoryWorld.chunkStorage`. Une expérimentation
heap doit mesurer séparément world loaded/non-ticked et world unloaded/GC.

### 3. Fusion des plugins et bridge optionnel

`:plugin` devient l’unique module livré. Le cycle de vie hôte reste celui de `SWPlugin`, mais le
bridge est converti en service interne recevant le contexte hôte ; aucune seconde `JavaPlugin` ne
doit être créée. Le descriptor généré devient `BTCCore`, et le jar devient `btccore-plugin-*`.

Le bridge n’est pas une dépendance de démarrage. Son service est créé en mode désactivé, puis passe
en mode actif uniquement si une configuration explicite l’autorise et si le canal/capability
BTCVelocity est présent. L’absence de Velocity est un état normal : aucun heartbeat, listener ou
appel de transport ne doit empêcher BTCCore de servir un backend autonome. Le protocole V2 reste
inchangé ; le transport actuel dépend toutefois d’un joueur porte-canal, ce qui doit être signalé
comme limite et couvert par un test de migration.

Les appels de découverte `BTCBridge`, `ASPaperPlugin`, `ASPaper` et `SlimeWorldManager` doivent être
remplacés par le service/API stable. Les coordonnées Maven publiques restent celles de l’API ; aucune
dépendance à une variante `mavenLocal` ne doit faire fuiter les classes moteur dans les extensions.
Le nom runtime `Typewriter` de Typewriter n’est pas modifié par cette fusion : le scan a trouvé de
nombreux `getPlugin("Typewriter")`/`getPlugin("TypeWriter")` côté extensions.

### 4. Preuve obligatoire

Le lot ne peut être déclaré acquis que si :

1. les warnings de compilation sont lus ;
2. le jar unique est déployé sur `H:\Serveurs Minecraft\serveur btc` ;
3. le log prouve l’ordre ASWM → bridge → événements ;
4. SoulFire prouve absence de tick sur chunk non possédé et reprise sur chunk possédé ;
5. la campagne A/B/A’ compare tick activé, monde chargé sans tick et world unload ;
6. un test crash/retry prouve le fencing et l’idempotence sans double application ;
7. un démarrage BTCCore sans BTCVelocity prouve que le mode autonome ne tente aucun transport ;
8. un démarrage avec BTCVelocity prouve l’activation à la demande, le protocole V2 et le comportement
   lorsque aucun joueur porte-canal n’est connecté.
