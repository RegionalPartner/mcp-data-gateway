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

![MCP Data Gateway — Le pont sécurisé entre vos données et l'IA](docs/assets/mcp-gateway-overview.png)

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
