# mcp-data-gateway

> **Une passerelle MCP de qualité production** qui expose les données d'entreprise aux agents IA via un accès sécurisé et contrôlé par rôle — construite et publiée en open source par [Ancoris](https://www.ancoris.fr), la division numérique et IA du [Groupe AXTOM](https://www.ancoris.fr).

[![CI](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/RegionalPartner/mcp-data-gateway/actions/workflows/ci.yml)
[![Licence : MIT](https://img.shields.io/badge/Licence-MIT-yellow.svg)](LICENSE)

🇫🇷 Français | [🇬🇧 English](README.md)

---

## De quoi s'agit-il

[Model Context Protocol (MCP)](https://modelcontextprotocol.io) est le standard émergent qui permet aux modèles d'IA — Claude, GPT, Gemini — de dialoguer avec les systèmes d'entreprise. Cette passerelle est le **pont sécurisé** entre vos données internes et tout agent IA compatible MCP.

**Le pont a un choix de conception délibéré :** le côté ingestion des données n'est intentionnellement pas inclus dans cette version open source. Charger vos documents, structurer votre base de données, classifier vos actifs informationnels — c'est là que se trouve le vrai travail en entreprise, et il est différent pour chaque organisation. C'est ce qu'[Ancoris](https://www.ancoris.fr) fait pour ses clients.

Ce que vous obtenez ici, c'est la couche passerelle complète, robustie pour la production : authentification, autorisation, recherche sémantique, audit, et un déploiement prêt pour Kubernetes.

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

Clés de démo (rôles lecture et admin) :

| Clé | Rôle | Données accessibles |
|-----|------|---------------------|
| `demo-readonly-key-001` | READ_ONLY | Documents PUBLIC + INTERNAL, employés (sans salaire) |
| `demo-admin-key-001` | ADMIN | Tous les documents y compris CONFIDENTIAL, table employés complète |

Essayez de demander à votre agent IA : *"Liste les sources de données disponibles"* ou *"Cherche des documents sur la politique RH"*.

---

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

L'authentification passe par OAuth 2.0 PKCE — Claude Code et les autres clients MCP gèrent cela automatiquement. En interne, les clés API sont pepperées HMAC et hachées BCrypt.

**La sécurité au niveau des lignes (RLS) est appliquée au niveau PostgreSQL**, pas dans le code applicatif. Les rôles ne peuvent pas être usurpés par un processus applicatif compromis.

| Rôle | Données structurées | Documents |
|------|--------------------|-----------| 
| `READ_ONLY` | Toutes les colonnes sauf `salary` | PUBLIC + INTERNAL |
| `ADMIN` | Toutes les colonnes | PUBLIC + INTERNAL + CONFIDENTIAL |

Protections supplémentaires :
- Noms de tables et colonnes validés contre une liste d'autorisation codée en dur (pas de SQL dynamique)
- Valeurs de filtres utilisent des requêtes paramétrées partout
- Contenu des documents chiffré AES-256 au repos
- Affinité de session NGINX pour les connexions MCP avec état entre réplicas
- Limiteur de débit (configurable, défaut 300 req/fenêtre par IP)

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

**Lancer les tests :**

```bash
./gradlew test   # Docker requis pour Testcontainers
```

---

## Configuration

Toutes les valeurs sensibles sont injectées via des variables d'environnement :

| Variable | Description | Défaut dev |
|----------|-------------|------------|
| `DB_URL` | URL JDBC | `jdbc:postgresql://localhost:5432/mcpgateway` |
| `DB_USER` | Utilisateur DB | `mcpuser` |
| `DB_PASSWORD` | Mot de passe DB | `mcppass` |
| `MCP_HMAC_PEPPER` | Pepper côté serveur pour le hachage des clés API | défaut dev (non sécurisé) |
| `MCP_CONTENT_KEY` | Clé AES-256 (64 caractères hex) pour le chiffrement des documents | défaut dev (zéros) |
| `MCP_JWT_SECRET` | Secret pour la signature des tokens JWT OAuth | défaut dev (non sécurisé) |

Le profil `dev` (`SPRING_PROFILES_ACTIVE=dev`) charge `application-dev.yaml` avec les valeurs ci-dessus et active les logs de debug.

---

## Déploiement (Kubernetes / OVH)

L'infrastructure est gérée avec Terraform (provider OVH). Un Makefile encapsule le cycle de vie complet :

```bash
# Copier et remplir les credentials
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars

# Déployer (~20 min au premier lancement — provisionne le cluster, installe cert-manager, déploie l'app)
source ~/.ovh-terraform.env
make up

# Arrêter le cluster et stopper la facturation (~5 min, secrets préservés dans .deploy.env)
make down
```

Avant de lancer `make up`, configurez votre domaine dans `k8s/app/ingress.yaml` et votre email dans `k8s/app/cert-manager-issuer.yaml` pour le TLS. Voir [`docs/OVH_DEPLOYMENT.md`](docs/OVH_DEPLOYMENT.md) pour le guide complet.

Les secrets sont auto-générés au premier `make up` et sauvegardés dans `.deploy.env` (gitignored).
**Conservez `.deploy.env` précieusement — la perte de `MCP_CONTENT_KEY` rend les documents chiffrés irrécupérables.**

---

## Qualité du build

| Vérification | Outil | Seuil |
|--------------|-------|-------|
| Tests unitaires + intégration | JUnit 5, Testcontainers | Doit passer |
| Couverture de code | JaCoCo | 70 % |
| Style de code | Checkstyle 10.17 | Zéro violation |
| Bugs de sécurité | SpotBugs + FindSecBugs | Zéro finding non exclu |
| CVE dépendances | OWASP Dependency Check | Échec sur CVSS 7.0 |
| Vulnérabilités conteneur | Trivy | Échec sur HIGH/CRITICAL |
| Secrets dans le code | Gitleaks | Zéro correspondance |

```bash
./gradlew build                  # Toutes les vérifications sauf OWASP (lent)
./gradlew dependencyCheckAnalyze # Scan CVE OWASP (nécessite le réseau)
```

---

## Aller plus loin

La passerelle telle que livrée gère l'authentification, le contrôle d'accès, la recherche et l'audit. Un déploiement en production pour une organisation réelle ajoute typiquement les couches suivantes.

### Pipeline d'ingestion documentaire

L'extension la plus impactante. Aujourd'hui les chunks sont insérés manuellement dans la base. Un vrai service d'ingestion gère : dépôt de fichier (PDF, DOCX, Excel, export email) → extraction texte → découpage → vectorisation via Ollama → chiffrement AES → stockage dans PostgreSQL. Cela peut être implémenté comme un nouvel outil MCP `ingest_document` (ADMIN uniquement) ou un endpoint REST dédié — dans tous les cas, le modèle de sécurité existant s'applique sans modification.

C'est la couche qui rend la passerelle utile avec *vos* données plutôt qu'avec des données de démo. C'est le premier élément configuré par Ancoris lors d'un engagement client.

### Connecteurs données d'entreprise

Le modèle d'outils de la passerelle est additif — les nouvelles classes `@Tool` s'ajoutent sans toucher à la couche de sécurité. Connecteurs à valeur immédiate pour les clients entreprise et secteur public :

| Connecteur | Cas d'usage |
|-----------|-------------|
| **Zoho CRM / Zoho One** | Exposer les contacts, opportunités et pipeline commercial aux agents IA — sans que l'agent voie jamais les credentials CRM |
| **Microsoft 365 / Google Workspace** | Chercher dans les emails et comptes-rendus de réunion passés par projet ou client — *"retrouve les échanges avec la CCIM sur le dossier cosmétiques"* |
| **SharePoint / Google Drive** | Ingérer les documents depuis les drives d'équipe existants selon un planning |
| **AWS S3 / Azure Blob / GCP Storage / OVH Object Storage** | Récupérer les documents stockés dans n'importe quel cloud — la passerelle normalise l'accès quelle que soit l'origine des données |

Dans tous les cas, la passerelle agit comme proxy de credentials : l'agent IA ne voit que le résultat de la requête, jamais les clés API amont. La passerelle elle-même est cloud-agnostique — elle se déploie sur tout cluster Kubernetes (OVH, AWS EKS, Azure AKS, GCP GKE) avec un Terraform adapté au provider.

### Fil de veille comme source interrogeable

Si vous disposez d'un pipeline de veille automatisée (monitoring web, presse sectorielle, évolutions réglementaires), l'exposer comme un outil MCP `search_intelligence` suit le même schéma que `search_documents` — même RLS, même audit, mêmes niveaux d'accès. Les agents IA peuvent alors croiser les documents internes avec les signaux marché en temps réel dans une même requête.

### Orchestration d'agents

La passerelle répond à des questions. Une couche d'orchestration lui permet d'agir. Des frameworks comme [LangGraph](https://github.com/langchain-ai/langgraph), [CrewAI](https://github.com/crewAIInc/crewAI) ou [n8n](https://n8n.io) peuvent appeler la passerelle comme un outil parmi d'autres — assembler un rapport de diagnostic territorial, préparer un dossier investisseur, ou déclencher une veille à la détection d'un nouveau projet. La passerelle reste sans état ; l'orchestrateur gère l'état de la tâche.

### Observabilité

La passerelle émet déjà des métriques Micrometer (`mcp.tool.calls`, `mcp.auth.failures`, `mcp.rate.limit.exceeded`) via `/actuator/metrics`. Connecter un stack Prometheus + Grafana transforme ces données en tableaux de bord temps réel et alertes automatiques — pics d'échecs d'authentification, usage par outil et par clé API, saturation du limiteur de débit. Pertinent pour toute organisation avec un RSSI ou une exigence de conformité.

---

**Les points 1 à 3 ci-dessus sont là où Ancoris intervient directement** — la modélisation des données, le développement des connecteurs et la configuration de l'ingestion sont spécifiques à l'architecture informationnelle de chaque client. Les points 4 et 5 sont en libre-service avec l'outillage OSS standard. [Contactez-nous](https://www.ancoris.fr) pour discuter du périmètre.

---

## À propos d'Ancoris

**Ancoris** est la division numérique et IA du [Groupe AXTOM](https://www.ancoris.fr), acteur de référence français du développement économique des territoires et de l'immobilier d'entreprise — *Champion de la Croissance* (Les Échos) depuis 2023.

Nous avons construit cette passerelle parce que nos clients — intercommunalités, agences de développement économique, opérateurs fonciers et entreprises privées — détiennent des années de données structurées que leurs équipes ne peuvent pas facilement interroger ou analyser. Connecter ces données en toute sécurité à des agents IA est aujourd'hui l'une des choses les plus utiles que nous faisons.

**Ce que nous faisons pour nos clients :**

- Structurer et ingérer vos actifs documentaires (documents internes, bases de données, exports CRM) dans une passerelle de ce type
- Définir vos niveaux de classification et rôles d'accès selon votre politique de sécurité
- Déployer et opérer l'infrastructure sur le cloud de votre choix (OVH, Azure, GCP)
- Former vos équipes à interroger leurs propres données via des agents IA

Le code est ouvert. La valeur est dans la donnée — et c'est là que nous intervenons.

**Contact :** [ancoris.fr](https://www.ancoris.fr)

---

## Licence

MIT — voir [LICENSE](LICENSE). Copyright (c) 2026 Groupe AXTOM.
