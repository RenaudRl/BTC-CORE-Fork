## 1. Contrat BTC-CORE

- [x] 1.1 Définir les types API `IslandKey`, ownership, lease, fencing token et `CatchUpContext`.
- [x] 1.2 Ajouter `IslandActivationEvent` et `ChunkResumeEvent` avec préconditions de thread Folia.
- [x] 1.3 Ajouter le registre de handlers borné, désinscriptible à l’unload, sans Koin/Typewriter dans l’API.
- [x] 1.4 Refuser explicitement monde inconnu, île non possédée, timestamp futur et lease expiré.
- [ ] 1.5 Tester ordre, concurrence régionale, unload/reload et absence de joueur.
- [ ] 1.6 Écrire le producteur de lignes `btc_island_ownership`, sans lequel `SqlIslandOwnershipSource`
      ne peut rien résoudre.
      **Arbitrage du 2026-08-21 — la source liée reste `BTCSkyIslandOwnershipSource`.** Constat :
      aucune instruction du fork n'insère dans `btc_island_ownership` (le schéma ne fait que `CREATE`,
      `SELECT` et `UPDATE`), et la table n'existe même pas dans la base du banc — preuve que la classe
      n'est jamais construite. La lier aujourd'hui ferait répondre « monde inconnu » partout et
      **désactiverait le rattrapage en silence**. Deuxième écart : son périmètre est un carré
      origine + rayon, alors que BTCSky possède un ensemble explicite `unlockedChunks`.
      Basculer suppose donc (a) un écrivain qui enregistre l'île au provisionnement de son monde,
      (b) un périmètre qui accepte un ensemble de chunks. Les deux sources ne coexistent plus sans
      décision : celle de BTCSky est la seule liée, celle de la plateforme est documentée comme
      inatteignable dans son propre javadoc.

- [ ] 1.7 Donner au balayage des chunks à balayer : mesuré le 2026-08-21, le rattrapage **ne traite
      aucun bloc**.
      Banc, île `btcsky_951bf7bd_overworld_5154de89`, fenêtre de **31 min 55 s**, `random_tick_speed`
      du monde forcé à **0** pour neutraliser la pousse en ligne : **0 blé avancé sur 79**, dans les
      quatre chunks de la parcelle, chunk de spawn compris. L'activation, elle, est prouvée
      (`Island catch-up ran for …`) et l'idempotence par la fenêtre aussi (fenêtre de 2 min 26 s ⇒ 0).
      Entrées mesurées : `effectiveRandomTickSpeed` = 4,5 (gamerule réappliqué à 5), terre labourée
      hydratée, donc quota `ticksPerBlock` = 10. Aucun log `deferred for`, `threw for` ni
      `refused for` : toutes les autres sorties `noWork()` sont exclues, il ne reste que
      `blocksProcessed == 0`.
      Cause : `IslandCropCatchUpHandler` itère `world.loadedChunks`, mais il est appelé depuis
      `IslandActivationListener` sur **`WorldLoadEvent`** — l'instant précis où un monde slime
      fraîchement chargé n'a encore aucun chunk chargé.
      **Confirmé par une seconde mesure, saturante.** `lastCatchUpToMillis` reculé de deux jours,
      donc fenêtre réelle de **48 h 00** (borne précédente `1787131284264`, commit `1787304164690`)
      et quota plafonné à `MAX_TICKS_PER_BLOCK` = 512 : tout blé traité aurait saturé à l'âge 7.
      Résultat : **0 avancé sur 80**, dans les quatre chunks. Il ne s'agit donc pas d'une couverture
      partielle mais d'un balayage qui ne traite **rien**.
      `forceload` est écarté comme contournement : les tickets **ne survivent pas** au déchargement
      d'un monde slime (`forceload query` répond « No force loaded chunks were found » après
      rechargement). Le correctif doit donc **charger lui-même** les chunks du périmètre
      `unlockedChunks` avant de balayer, ou différer le balayage après le chargement effectif —
      et non compter sur `world.loadedChunks`.

## 2. Distances et chargement

- [ ] 2.1 Instrumenter les counts `full`, `block-ticking`, `entity-ticking`, block entities et displays.
- [ ] 2.2 Mesurer A/B/A’ : 50 îles tickées, mêmes îles chargées sans tick, puis retour tické.
- [ ] 2.3 Mesurer heap/GC séparément avant/après chunk unload et world unload.
- [ ] 2.4 Valider `unlockedChunks` sur une île 15×15, sans ticket joueur ni `/forceload` exposé.
- [ ] 2.5 Documenter les gains comme mesures ou hypothèses ; ne pas utiliser le +0,85 ms `/forceload`
      comme preuve d’un autre levier.

## 3. Plugin unique

- [x] 3.1 Intégrer les sources du bridge dans `:plugin` avec un hôte de cycle de vie unique.
      `BridgePlugin` devient `BridgeService`, démarré et arrêté par `SWPlugin`. La résolution de
      monde passait par réflexion sur `ConfigManager` et `LoaderManager` faute de voir leurs classes
      depuis un plugin séparé : appels directs maintenant, ~50 lignes et autant d'échecs muets en
      moins.
- [x] 3.2 Générer le descriptor `BTCCore` et l’artefact `btccore-plugin-*` ; supprimer la double sortie.
      Module `:bridge-plugin` supprimé de `settings.gradle.kts` et du disque ; build propre vérifié :
      un seul jar runtime, `name: BTCCore` dans le descriptor.
- [x] 3.3 Migrer configuration bridge, tests, découverte de plugin et documentation de déploiement.
      Les clés `bridge.*` sont inchangées mais lues depuis `plugins/BTCCore/config.yml`, désormais
      livré et commenté. `BTCCoreVisualAPIImpl` cherchait `ASPaperPlugin` : corrigé.
      **Défaut trouvé et corrigé** : `BridgeCodecTest` n'avait jamais tourné. Ce n'est pas un test
      JUnit mal configuré mais un harnais `main()` volontairement sans framework, qu'aucune tâche
      Gradle n'invoquait — et qui ne pouvait pas tourner de toute façon, Gson étant `compileOnly`.
      Tâche `:plugin:bridgeCodecCheck` ajoutée et branchée sur `check` : 9 vérifications passent.
- [x] 3.4 Ajouter le mode autonome sans BTCVelocity et l’activation à la demande du bridge lorsqu’une
      capability Velocity est détectée ; documenter la limite du joueur porte-canal.
      `bridge.mode: auto|on|off`, `auto` par défaut. La détection lit
      `GlobalConfiguration.get().proxies.velocity.enabled` — le forwarding moderne est ce qui rend
      un proxy réellement présent, alors qu'un plugin installé ne prouve rien. Sans proxy : aucun
      canal enregistré, aucune tâche de santé planifiée.
      **Limite du joueur porte-canal** : un message de plugin ne circule que sur une connexion
      joueur. `sendToProxy` prend le premier joueur en ligne comme porteur, donc *un backend vide ne
      peut rien envoyer au proxy* — ni ACK, ni NACK, ni rapport de santé. Le proxy doit traiter le
      silence d'un backend vide comme « aucune information », jamais comme « en panne ». Lever cette
      limite demande un transport hors bande (socket ou Redis), hors périmètre de ce lot.
- [x] 3.5 Vérifier API publique, coordonnées Maven, absence de fuite moteur et compatibilité Typewriter,
      CraftEngine, PacketEvents et MiniPlaceholders ; ne pas renommer le plugin Typewriter.
      Aucun code hors de ce dépôt ne référence `ASPaperPlugin`, `BTCBridge` ni `asp-plugin` — seules
      des docs, corrigées. Coordonnées Maven inchangées (`archiveBaseName` ne change que le nom de
      fichier). Gson reste `compileOnly` : rien de neuf n'entre dans le jar. Plugin Typewriter non
      renommé.
- [x] 3.6 Construire paperclip + plugin, lire les warnings, déployer et vérifier le démarrage réel.
      Paperclip `26.2.build.2` + `btccore-plugin` déployés sur `H:\Serveurs Minecraft\serveur btc`,
      anciens `asp-plugin-*.jar` et `BTCBridge-1.0.0.jar` retirés. Démarrage réel : `Done (28,343s)`,
      `Paper plugins (6)` dont `BTCCore`, 53 mondes slime chargés, `/btccore` et `/swm list` répondent.
      **Deux défauts trouvés au déploiement, tous deux corrigés et revérifiés sur le serveur :**
      1. *Le plugin ne chargeait pas du tout.* `processResources` applique `expand()` sur
         `paper-plugin.yml` ; une `description` assez longue pour que plugin-yml la replie avec la
         continuation `\` de YAML est ensuite avalée par le moteur de template Groovy, qui a mangé
         les lignes suivantes — `main` et `bootstrapper` compris. Paper refusait le plugin sur
         `[main] ... A value is required`. Description raccourcie et sans `:`.
      2. *La configuration du bridge n'avait pas suivi.* L'ancien `plugins/BTCBridge/config.yml`
         portait `backend-id: btc` et `allowed-backends: [proxy]` ; le nouveau défaut donnait
         `backend-id: BTC-CORE` et aucune allowlist. Migrée à la main sur le banc. Le code ne la lit
         **pas** automatiquement — ce serait une migration silencieuse vers un fichier introuvable —
         mais avertit à chaque démarrage tant que l'ancien fichier existe.
      Warnings de compilation lus : uniquement des `warning: no @param` / `no comment` javadoc,
      préexistants au module. Aucune erreur.
      Détection Velocity vérifiée sur un vrai proxy en marche : `Bridge enabled for backend btc`.

## 4. Sécurité et acceptation

- [ ] 4.1 Tester rollback sauvegarde, crash après claim, crash après mutation et retry concurrent.
- [ ] 4.2 Tester horloge future, deux backends, monde non possédé et ID d’île contenant `_`.
- [ ] 4.3 Tester messages bridge client-origin, replay, allowlist et accès aux tickets.
- [ ] 4.4 Exécuter SoulFire pour le contrat gameplay puis archiver les mesures et limites.
