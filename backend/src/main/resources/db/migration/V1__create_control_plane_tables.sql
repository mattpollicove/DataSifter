CREATE TABLE workflows (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL
);

CREATE TABLE connectors (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL
);

CREATE TABLE job_executions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL
);

CREATE TABLE audit_entries (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL
);

CREATE TABLE key_vault_secrets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL
);

CREATE TABLE datasifter_metadata (
    meta_key VARCHAR(100) NOT NULL PRIMARY KEY,
    meta_value VARCHAR(500) NOT NULL
);
