-- Demo API keys (bcrypt hash of the raw key values shown in README)
-- READ_ONLY key: demo-readonly-key-001
-- ADMIN key:     demo-admin-key-001
INSERT INTO api_keys (key_hash, label, role)
VALUES ('$2a$12$WkJWGPvVdh6YZiXhcMhkIuFOz1Y9mVqJnJtlFW3nGn5WZSD7lXGLi', 'Demo Read-Only Client', 'READ_ONLY'),
       ('$2a$12$9DXqPKbqzaIRuM0u5K1QFeJFGn2mEhlqTSfLKJt7WGe3fCRdAvzm.', 'Demo Admin Client',     'ADMIN');

-- Employees (salary visible to ADMIN only)
INSERT INTO employees (name, department, email, salary)
VALUES ('Alice Martin',  'RH',      'alice@ad-normandie.fr',  42000.00),
       ('Bob Dupont',    'IT',      'bob@ad-normandie.fr',    55000.00),
       ('Claire Morin',  'Finance', 'claire@ad-normandie.fr', 61000.00),
       ('David Leroy',   'IT',      'david@ad-normandie.fr',  52000.00),
       ('Emma Bernard',  'RH',      'emma@ad-normandie.fr',   44000.00);

-- Document chunks stored in MinIO (pre-indexed text fragments)
INSERT INTO document_chunks (doc_name, classification, minio_key, chunk_index, text_preview)
VALUES
    ('rapport-annuel-2024.txt', 'INTERNAL',
     'chunks/rapport-annuel-2024-chunk-00.json', 0,
     'Le rapport annuel 2024 présente les résultats consolidés de l''Agence de Développement de Normandie. L''exercice démontre une progression significative des activités.'),

    ('rapport-annuel-2024.txt', 'INTERNAL',
     'chunks/rapport-annuel-2024-chunk-01.json', 1,
     'Les investissements en infrastructure numérique ont augmenté de 23% par rapport à l''exercice précédent, reflétant l''engagement vers la transformation digitale.'),

    ('rapport-annuel-2024.txt', 'INTERNAL',
     'chunks/rapport-annuel-2024-chunk-02.json', 2,
     'Le bilan énergétique des datacenters normands montre une réduction de 15% de la consommation électrique grâce aux nouveaux équipements.'),

    ('politique-rh-v3.txt', 'CONFIDENTIAL',
     'chunks/politique-rh-v3-chunk-00.json', 0,
     'La politique RH version 3 définit les procédures de recrutement et d''évaluation des compétences pour l''ensemble du personnel de l''agence.'),

    ('note-technique-securite.txt', 'PUBLIC',
     'chunks/note-technique-securite-chunk-00.json', 0,
     'Cette note technique décrit les bonnes pratiques de sécurité informatique applicables à tous les agents. Elle couvre la gestion des mots de passe et les accès distants.');
