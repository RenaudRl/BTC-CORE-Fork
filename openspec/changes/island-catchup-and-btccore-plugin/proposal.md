## Pourquoi

Le banc montre 37,9 ms de MSPT avec 50 îles lourdes chargées sans joueur (225 chunks par île,
environ 0,80 ms par île). La campagne 50 joueurs est déjà à 33,8 ms, mais le coût persiste quand
les joueurs sont absents. Le mécanisme actuel ticke les chunks entity-ticking ; il ne possède pas
de contrat commun de reprise à la demande pour les extensions.

L’audit SWM établit qu’un `.slime` est entièrement désérialisé dans un `Long2ObjectMap<SlimeChunk>`
au `readWorld`. Un unload de chunk remplace le chunk live par un snapshot dans cette map. Il coupe
le tick du chunk, mais ne libère pas le stockage du monde. Seul l’unload du monde retire l’instance
du serveur et peut réduire le heap après GC.

## Ce qui change

- Exposer dans l’API BTC-CORE un contexte d’île possédée, ancré par `worldName`, avec lease et
  fencing token MySQL ; aucun monde non possédé ne peut être repris.
- Émettre des événements de cycle de vie `IslandActivationEvent` et `ChunkResumeEvent`, en
  garantissant le contexte global/région Folia approprié.
- Fournir un point d’enregistrement borné pour les rattrapeurs d’extensions ; le cœur orchestre le
  moment et le thread, l’extension possède la règle de gameplay.
- Conserver la séparation view-distance / simulation-distance. Les distances par monde restent
  disponibles ; la distance par joueur est une capacité Paper, pas un comportement BTC-CORE acquis.
- Fusionner `:bridge-plugin` dans `:plugin` et produire un unique artefact `btccore-plugin-<version>.jar`.
  Le runtime plugin devient `BTCCore`; le bridge V2 est un service interne initialisé après l’hôte
  ASWM. Supprimer la double livraison `BTCBridge` + `ASPaperPlugin` dans le lot de migration.
- Rendre le bridge réellement optionnel : l’hôte BTCCore doit démarrer et exposer ASWM/BTC-CORE sans
  BTCVelocity ; le transport ne s’active que si la configuration le demande et si la capacité
  Velocity est détectée. Le serveur BTC de test doit donc fonctionner avec BTCCore seul.
- Conserver `dev.btc.core.api` et les coordonnées API publiques comme contrat de compilation. Le
  renommage vise le plugin livré, son nom d’affichage et son artefact, pas une rupture des packages
  consommés par Typewriter, CraftEngine, PacketEvents ou MiniPlaceholders.

## Coût de gameplay

Le mode par défaut reste inchangé : aucun rattrapage vanilla implicite, aucun tick permanent,
aucune fausse entité et aucune réduction de view-distance. Les systèmes doivent déclarer leur
contrat : rattrapable, gelable ou impossible. `unlockedChunks` ne doit activer que les chunks
explicitement débloqués et son coût doit être visible dans la progression de l’île.

Le rattrapage des cultures modifiera le résultat observé après absence selon un taux gratuit mais
réduit ; ce taux doit rester configuré et mesuré, pas codé en dur. Le four vanilla reste gelé tant
que le lot de comparaison n’a pas démontré le coût et les invariants d’un four MachineExtension.
Le remplacement du plugin change le nom du plugin, le nom du jar et l’ordre d’initialisation ;
c’est un coût opérationnel de migration, pas un fallback silencieux.

## Non-goals

- Ne pas simuler les entités, les mobs ou une ferme vanilla hors présence.
- Ne pas introduire de tick d’arrière-plan pour les chunks déchargés.
- Ne pas prétendre libérer le heap au chunk unload ; le gain mémoire doit être mesuré au world unload.
- Ne pas rendre les API de ticket internes accessibles aux joueurs ni réintroduire les primitives
  inertes déjà constatées dans `PerformanceManager`.
- Ne pas déplacer l’autorité du temps dans BTCVelocity ou Redis.
- Ne pas migrer automatiquement les extensions Typewriter ni modifier RPGCore dans ce dépôt ; la
  compatibilité de nommage sera auditée côté Typewriter avant le changement de descriptor.
- Ne pas conserver deux plugins runtime ou une compatibilité legacy non spécifiée.

## Impact

API `dev.btc.core.api`, hooks de chargement/unload, configuration de propriété d’île, module Gradle
`:plugin`, bridge V2 et procédure de déploiement. La persistence exacte une fois et les handlers
de gameplay sont définis dans l’annexe Typewriter.
