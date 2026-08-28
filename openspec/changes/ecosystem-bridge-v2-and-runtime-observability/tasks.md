## 1. Bridge V2 backend

- [x] 1.1 Introduire l'enveloppe V2 et un codec borné sans casser les types de payload nécessaires.
- [x] 1.2 Rejeter les origines Player pour les messages de contrôle et vérifier backend/cible.
- [x] 1.3 Ajouter ACK/NACK, expiration et déduplication bornée.
- [ ] 1.4 Implémenter le preload réel et n'annoncer WorldLoaded qu'après preuve.
- [x] 1.5 Remplacer les schedulers interdits du bridge par des contextes compatibles.
- [ ] 1.6 Ajouter tests codec, payload oversize, origine, replay et preload failure.

## 2. Runtime manifest and telemetry

- [ ] 2.1 Ajouter le snapshot de compatibilité et sa sortie startup redacted.
- [ ] 2.2 Ajouter l'API de compteurs opt-in par extension.
- [ ] 2.3 Ajouter tests de bornage, concurrence et absence de données sensibles.

## 3. Bench contract

- [x] 3.1 Documenter le manifest runtime, les artefacts et la variante btccore.
- [x] 3.2 Documenter le protocole A/B/A', nettoyage logs, MSPT parser et réserves.
- [x] 3.3 Ajouter les critères de décision p50/p95/p99 sans lancer de campagne.

## 4. Vérification

- [x] 4.1a Compiler le module bridge ciblé après restauration du prototype non conforme.
- [ ] 4.1 Compiler et exécuter les tests ciblés.
- [ ] 4.2 Relire le diff limité à ce changement en préservant les modifications existantes.
- [ ] 4.3 Préparer le déploiement contrôlé, sans rotation automatique de secrets.
