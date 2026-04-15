<div align="center">

# Le Pont Sécurisé entre vos Données d'Entreprise et l'IA

**Donnez à vos agents IA un accès à vos données internes — sans exposer vos credentials, sans contourner la sécurité, sans reconstruire votre infrastructure.**

[![CI](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml)
[![Licence : MIT](https://img.shields.io/badge/Licence-MIT-yellow.svg)](LICENSE)

🇫🇷 Français | [🇬🇧 English](README.md)

*Construit et publié en open source par [Ancoris](https://www.ancoris.fr) — conseil IA & numérique, Groupe AXTOM*

</div>

---

[Model Context Protocol (MCP)](https://modelcontextprotocol.io) est le standard émergent pour connecter les modèles d'IA aux systèmes d'entreprise. Cette passerelle est le **pont sécurisé** entre vos données internes et tout client IA compatible MCP. Testé avec **Claude Code**, **claude.ai** et **Mistral**.

Le côté ingestion des données n'est intentionnellement pas inclus dans cette version — structurer et charger vos données, c'est là que se trouve le vrai travail en entreprise, et il est différent pour chaque organisation. Ce que vous obtenez ici, c'est la couche passerelle complète, robustifiée pour la production : authentification, autorisation, recherche sémantique, audit, et un déploiement prêt pour Kubernetes.

## Sommaire

- [Démo en ligne](#démo-en-ligne)
- [Ce que ça fait](#ce-que-ça-fait)
- [Pour les DSI et responsables sécurité](#pour-les-dsi-et-responsables-sécurité)
- [Modèle de sécurité](#modèle-de-sécurité)
- [Stack technique](#stack-technique)
- [Démarrage rapide](#démarrage-rapide-local)
- [Documentation](#documentation)
- [À propos d'Ancoris](#à-propos-dancoris)

---

## Démo en ligne

Une instance avec des données anonymisées est disponible à :

```
https://mcp.37.59.24.118.nip.io
```

Connexion via Claude Code :

```bash
claude mcp add mcp-data-gateway --transport http https://mcp.37.59.24.118.nip.io/mcp
```

Clés de démo :

| Clé | Rôle | Données accessibles |
|-----|------|---------------------|
| `demo-readonly-key-001` | READ_ONLY | Documents PUBLIC + INTERNAL, employés (sans salaire) |
| `demo-admin-key-001` | ADMIN | Tous les documents y compris CONFIDENTIAL, table employés complète |

Essayez : *"Liste les sources de données disponibles"* ou *"Cherche des documents sur la politique RH"*.

---

<div align="center">

![Passerelle de Données MCP — Couche d'Accès Sécurisée](docs/assets/mcp-gateway-overview.png)

</div>

## Ce que ça fait

Quatre outils MCP, exposés via OAuth 2.0 PKCE (transport HTTP streamable) :

| Outil | Description |
|-------|-------------|
| `list_sources` | Liste les tables et collections documentaires accessibles avec la clé courante, y compris les colonnes visibles et les niveaux de classification |
| `query_database` | Interroge des tables structurées avec filtrage des colonnes par rôle et SQL paramétré (protection injection) |
| `search_documents` | Recherche par mots-clés dans les documents internes — retourne des fragments de texte, jamais les fichiers bruts |
| `semantic_search_documents` | Recherche par similarité vectorielle (pgvector + Ollama) — trouve le contenu conceptuellement proche même sans correspondance exacte de mots-clés |

Chaque appel d'outil est inscrit dans un journal d'audit immuable.

---

## Pour les DSI et responsables sécurité

La passerelle n'est pas un service de stockage cloud. Elle ne copie, ne réplique ni n'héberge vos données. **C'est une couche de contrôle d'accès** — elle gère quels fragments de vos données existantes peuvent être partagés avec un LLM, et dans quelles conditions.

### Ce qui se passe quand vous connectez une source de données existante

Prenons l'exemple d'un tenant Microsoft 365. Votre tenant reste exactement là où il est — SharePoint, Exchange et Teams demeurent dans l'infrastructure Microsoft, sous vos licences et politiques de gouvernance existantes.

Ce que la passerelle ajoute, c'est une étape d'ingestion contrôlée et une interface de requête :

| Étape | Ce qui se passe | Ce que la passerelle stocke |
|-------|----------------|----------------------------|
| Vous sélectionnez les sources | Une bibliothèque SharePoint, une boîte partagée, un canal Teams | Rien pour l'instant |
| L'ingestion s'exécute | Les documents sont extraits → découpés en fragments texte → vectorisés par un modèle local (Ollama) | Fragments texte + embeddings vectoriels uniquement — jamais le fichier original |
| La classification est assignée | Chaque fragment est tagué PUBLIC, INTERNAL ou CONFIDENTIAL selon votre politique | Métadonnées de classification |
| Le LLM envoie une requête | L'IA pose une question à la passerelle | Rien — la requête est transitoire |
| La passerelle filtre et répond | Seuls les fragments que le rôle de l'appelant est autorisé à voir sont retournés | La requête est inscrite dans le journal d'audit |

**Le LLM ne se connecte jamais à votre tenant M365.** Il envoie une question à la passerelle ; la passerelle retourne des fragments de texte filtrés. Aucune credential SharePoint, aucun token Exchange, aucun fichier brut n'est jamais exposé au modèle IA.

### Ce que la passerelle ne fait pas

- Ne stocke pas les fichiers bruts (PDF, DOCX, pièces jointes, images, vidéos)
- N'indexe pas tout votre tenant — uniquement les sources que vous configurez explicitement
- Ne donne pas accès au LLM aux boîtes personnelles, données de calendrier ou OneDrive personnel, sauf ajout explicite
- N'envoie aucune donnée à des API IA externes — le modèle d'embedding (Ollama) tourne localement dans votre infrastructure

### Ce que cela signifie concrètement

| Préoccupation | Comment la passerelle y répond |
|---------------|-------------------------------|
| Souveraineté des données | Toutes les données restent dans votre infrastructure — PostgreSQL et Ollama tournent dans votre cluster |
| Contrôle du périmètre | Vous définissez chaque source explicitement — rien n'est ingéré par défaut |
| Segmentation des accès | Les rôles READ_ONLY et ADMIN contrôlent ce que chaque utilisateur IA peut interroger |
| Auditabilité | Chaque requête est journalisée avec la clé, l'outil appelé et le résumé du résultat |
| RGPD / classification | Les niveaux PUBLIC / INTERNAL / CONFIDENTIAL correspondent directement à votre politique de sécurité de l'information |

---

## Modèle de sécurité

L'authentification passe par OAuth 2.0 PKCE — Claude Code et les autres clients MCP gèrent cela automatiquement. Les clés API sont pepperées HMAC et hachées BCrypt. **La sécurité au niveau des lignes (RLS) est appliquée au niveau PostgreSQL**, pas dans le code applicatif.

| Rôle | Données structurées | Documents |
|------|--------------------|-----------| 
| `READ_ONLY` | Toutes les colonnes sauf `salary` | PUBLIC + INTERNAL |
| `ADMIN` | Toutes les colonnes | PUBLIC + INTERNAL + CONFIDENTIAL |

Référence complète — modèle de menace, RLS, chiffrement AES-256, double piste d'audit : [docs/SECURITY_HARDENING.md](docs/SECURITY_HARDENING.md)

---

## Stack technique

| Couche | Technologie |
|--------|------------|
| Runtime | Java 21, Spring Boot 3.5 |
| MCP | Spring AI MCP Server (HTTP streamable, protocoles 2025-03-26 + 2025-11-25) |
| Base de données | PostgreSQL 16 — données, documents chiffrés, logs d'audit, embeddings pgvector |
| Migrations | Flyway |
| Embeddings | Ollama (local, aucune dépendance API externe) |
| Infrastructure | Kubernetes (OVH), Terraform, NGINX Ingress, cert-manager (Let's Encrypt) |
| CI | GitHub Actions — tests, scan CVE OWASP, scan conteneur Trivy, Gitleaks |

---

## Démarrage rapide (local)

**Prérequis :** Docker, Java 21

```bash
# Démarrer PostgreSQL + Ollama
docker compose up -d

# Lancer l'application
./gradlew bootRun

# Application sur http://localhost:8080
curl -H "X-API-Key: demo-readonly-key-001" http://localhost:8080/actuator/health
```

```bash
./gradlew test   # Lancer les tests (Docker requis pour Testcontainers)
```

---

## Documentation

| Document | Contenu |
|----------|---------|
| [Démarrage guidé](docs/GETTING_STARTED.fr.md) | Guide pas-à-pas — connecter Claude Code à la démo |
| [Configuration](docs/CONFIGURATION.md) | Variables d'environnement, profil dev, génération des secrets |
| [Sécurité](docs/SECURITY_HARDENING.md) | Modèle de menace, HMAC, RLS, AES-256, double piste d'audit |
| [Déploiement OVH](docs/OVH_DEPLOYMENT.md) | Guide Kubernetes / Terraform complet |
| [Architecture](docs/ARCHITECTURE.md) | Décisions de conception, pipeline de requêtes, ajout d'outils |
| [Étendre la passerelle](docs/EXTENDING.md) | Ingestion, connecteurs, observabilité, orchestration |
| [Qualité du build](docs/BUILD_QUALITY.md) | Tests, couverture, scan OWASP CVE, Trivy, Gitleaks |
| [Pour les agents IA](docs/for-ai-agents.md) | Référence des outils lisible par machine |

---

## À propos d'Ancoris

**Ancoris** est la division numérique et IA du [Groupe AXTOM](https://www.ancoris.fr), acteur de référence français du développement économique des territoires et de l'immobilier d'entreprise — *Champion de la Croissance* (Les Échos) depuis 2023.

Nous avons construit cette passerelle parce que nos clients — intercommunalités, agences de développement économique, opérateurs fonciers et entreprises privées — détiennent des années de données structurées que leurs équipes ne peuvent pas facilement interroger ou analyser.

- Structurer et ingérer vos actifs documentaires dans une passerelle de ce type
- Définir vos niveaux de classification et rôles d'accès selon votre politique de sécurité
- Déployer et opérer l'infrastructure sur le cloud de votre choix (OVH, Azure, GCP)
- Former vos équipes à interroger leurs propres données via des agents IA

Le code est ouvert. La valeur est dans la donnée — et c'est là que nous intervenons. [ancoris.fr](https://www.ancoris.fr)

---

## Licence

MIT — voir [LICENSE](LICENSE). Copyright (c) 2026 Groupe AXTOM.
