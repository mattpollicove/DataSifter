# DataSifter
data synchronization and transformation tool

DataSifter is a Java + React control-plane MVP built from the specification in `spec.md`. The app includes a Spring Boot backend with service-backed local state, portable JSON persistence, encrypted-at-rest secret storage, and a dashboard that models the workflow orchestration, connector health, mapping editor, KeyVault, and audit views described in the design.

## Project notes

- See [Docs/RENAMING_TO_DATASIFTER.md](./Docs/RENAMING_TO_DATASIFTER.md) for safe configuration and data migration from NexusSync.
- Build and validation commands should only be run after user confirmation unless the task explicitly requests a validation pass.
- The project changelog is tracked in `CHANGELOG.md` and should be updated as work progresses and whenever a release or repository push is completed.

## Stack

- Backend: Java 25, Spring Boot 3.5.16, Maven
- Persistence: MySQL with Flyway-managed SQL migrations (default profile); H2 MySQL-mode database for automated tests
- Frontend: React + Vite + Tailwind CSS
- Deployment model: HTTPS control-plane API plus independently deployed, horizontally scalable worker containers sharing a durable database queue

## Run locally

### Backend

Set `DATASIFTER_DB_URL`, `DATASIFTER_DB_USERNAME`, `DATASIFTER_DB_PASSWORD`, `DATASIFTER_SECURITY_ADMIN_USERNAME`, `DATASIFTER_SECURITY_ADMIN_PASSWORD`, and `DATASIFTER_VAULT_PASSPHRASE`, then:

```bash
cd backend
mvn spring-boot:run
```

Create the `datasifter` database first. The default URL is `jdbc:mysql://localhost:3306/datasifter?serverTimezone=UTC`. The configured MySQL user must be allowed to create and alter tables for Flyway migrations. Do not commit database credentials.

For a temporary file-backed local/development mode, run with the `json` Spring profile; default runtime uses MySQL. JSON mode is single-process only and does not run Flyway. `DATASIFTER_DATA_DIRECTORY` overrides the default `${user.home}/.datasifter` location for JSON state, vault salt, and uploaded CSV files.

The admin password must be at least 16 characters; the vault passphrase must be 12–1024 characters. Optional `DATASIFTER_SECURITY_OPERATOR_USERNAME` / `DATASIFTER_SECURITY_OPERATOR_PASSWORD`, `DATASIFTER_SECURITY_AUDITOR_USERNAME` / `DATASIFTER_SECURITY_AUDITOR_PASSWORD`, and `DATASIFTER_SECURITY_VIEWER_USERNAME` / `DATASIFTER_SECURITY_VIEWER_PASSWORD` configure additional users. Each username/password pair must be supplied together. The API uses HTTP Basic and the UI keeps credentials in memory only; use HTTPS outside localhost. Mutating browser requests require the CSRF token provided by `GET /api/csrf`.

The local development vault derives an AES-256-GCM key from `DATASIFTER_VAULT_PASSPHRASE` using PBKDF2-HMAC-SHA-256 and a per-installation salt. Legacy ciphertext is read with the existing `vault-master.key` and migrated when secrets are next saved; back up `.datasifter` and retain the legacy key until imports are complete. `POST /api/keyvault/rotate` is for the local passphrase provider only.

For managed key custody, install a `VaultKeyProvider` implementation as a plugin JAR under the configured plugin directory and register it through Java `ServiceLoader` (`META-INF/services/com.datasifter.support.VaultKeyProvider`). The provider wraps/un-wraps per-secret data-encryption keys and must retain decrypt access to old key references during rotation. Configure `DATASIFTER_VAULT_PROVIDER` to its provider ID and set `DATASIFTER_VAULT_REQUIRE_MANAGED_PROVIDER=true`; startup fails if the plugin is absent or invalid. The v3 ciphertext format uses AES-256-GCM envelope encryption. Provider-managed rotation is performed through the selected provider, not the local passphrase-rotation endpoint. No vendor-specific KMS adapter is bundled.

### Durable worker queue

The MySQL profile persists queue state and uses database-conditional claims so concurrent workers cannot claim the same queued job. Configure a strong shared worker credential in `DATASIFTER_WORKER_TOKEN` before starting the backend; worker endpoints return `503` when it is not configured. Keep the token private and do not commit it. Use HTTPS whenever worker traffic crosses an untrusted network.

Worker clients use `X-Worker-Token` and these endpoints:

- `POST /api/workers/jobs/claim` with `{"workerId":"worker-name"}`; returns `204` when no job is ready.
- `POST /api/workers/jobs/{id}/heartbeat` with worker ID and optional progress/success/failed counts.
- `POST /api/workers/jobs/{id}/complete` with worker ID and final success/failed counts.
- `POST /api/workers/jobs/{id}/fail` with worker ID to schedule a retry or mark the job failed after its retry budget is exhausted.
- `GET /api/workers/health` to view heartbeat and lease health for workers currently holding jobs.
- `GET /api/jobs/{id}` to inspect retry, worker, heartbeat, lease, and next-attempt state.

The default policy permits three retries with exponential backoff starting at five seconds and capped at five minutes. A worker lease lasts 60 seconds and is extended by each heartbeat; expired leases are recovered automatically. Jobs are delivered at least once, so destination tables should have appropriate unique keys or other deduplication controls.

Run the worker in a separate process/container by setting `DATASIFTER_WORKER_ENABLED=true` and disabling its HTTP listener with `--server.port=-1`. The API and worker processes use the MySQL lease queue; the local `json` profile is rejected for worker execution. Run multiple worker replicas to scale job execution.

For the container stack, fill in `deploy/.env.example` as `deploy/.env`, install the selected provider plugins under `deploy/plugins`, then run `docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build`. Scale separate workers on a single Docker host with `docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --scale worker=3`; for multi-host scheduling, deploy the worker image through an orchestrator and configure the shared storage/KMS plugins identically on every replica.

The processor streams CSV, uses forward-only JDBC `SELECT` queries from MySQL or PostgreSQL, or pages LDAPS searches, then writes parameterized batches to MySQL/PostgreSQL targets. MySQL JDBC URLs must set `sslMode=VERIFY_IDENTITY`; PostgreSQL URLs must set `sslmode=verify-full`. The processor adds connection/socket timeouts, but never disables TLS or certificate/hostname verification. LDAP requires `ldaps://`; the JVM trust store must trust the directory certificate chain. JDBC and LDAP passwords are referenced by Key Vault secret IDs. Target tables must already exist, and jobs are at-least-once, so destinations should use suitable idempotency/unique keys.

Mappings support trim/lowercase/uppercase field transforms and `none`, `mask`, `sha256`, `encrypt`, or `drop` privacy actions. `encrypt` uses the configured vault provider. JDBC writes use bounded batches sized from current JVM heap headroom; JDBC source reads fetch rows incrementally and LDAP searches use paged results. CSV uploads remain limited to 100 MiB.

Uploads use the `WorkflowFileStore` extension point, registered by Java `ServiceLoader` (`META-INF/services/com.datasifter.support.WorkflowFileStore`). The built-in `local` adapter stores opaque file references under the managed data directory and is suitable only for development or a single Docker host with a shared volume. Multi-host workers require an installed shared durable object-store plugin. Configure `DATASIFTER_STORAGE_PROVIDER` and set `DATASIFTER_STORAGE_REQUIRE_DISTRIBUTED_PROVIDER=true` to fail closed if the configured provider is unavailable. No cloud-specific storage implementation is bundled.

`deploy/docker-compose.yml` defines the React frontend, API, worker, MySQL, and Caddy HTTPS ingress services. Production startup requires external `VaultKeyProvider` and `WorkflowFileStore` plugins; configure the values in `deploy/.env.example`, provision plugin JARs in the plugin directory, and use the local storage option only for single-host development. The frontend, API, and worker do not publish public ports; only Caddy exposes 80/443 and routes API requests to the backend. Follow the [operations runbook](./Docs/OPERATIONS.md) for credential provisioning, backups/restores, and key rotation; see the [release checklist](./Docs/RELEASE_CHECKLIST.md) for deployment validation.

Worker progress and state transitions are accepted only from the lease-owning worker endpoint. The legacy `PUT /api/jobs/{id}/status` route cannot transition a job into or out of the running state.

### Frontend

```bash
cd frontend
npm install
npm run dev -- --host 0.0.0.0 --port 5173
```

Then open the UI at http://localhost:5173.

## API endpoints

- `GET /api/dashboard`
- `GET /api/session`
- `GET /api/csrf`
- `GET /api/workflows`
- `GET /api/connectors`
- `GET /api/audit`
- `GET /api/keyvault`
- `GET /api/mapping`
- `POST /api/jobs/run`
- `POST /api/workflows/{id}/csv` (multipart field `file`; operator/admin)
- `POST /api/keyvault/rotate` (admin)
- `POST /api/workers/jobs/claim`
- `POST /api/workers/jobs/{id}/heartbeat`
- `POST /api/workers/jobs/{id}/complete`
- `POST /api/workers/jobs/{id}/fail`
- `GET /api/workers/health`

## Current MVP scope

- Role-enforced control-plane APIs with environment-configured admin/operator/auditor/viewer identities; vault operations are administrator-only and audit reads are restricted to admins/auditors
- Versioned AES-256-GCM vault ciphertext with PBKDF2-HMAC-SHA-256 passphrase derivation, explicit passphrase rotation, legacy ciphertext migration, and fail-closed decryption
- Persisted workflow canvas nodes/edges, field mappings, and target JDBC configuration
- Separate worker process/container for MySQL-backed lease-queue execution; multiple replicas may claim jobs concurrently
- CSV, MySQL/PostgreSQL JDBC query, and LDAPS data sources with MySQL/PostgreSQL JDBC targets
- Pluggable workflow file storage and managed vault-key provider contracts with production fail-closed configuration
- Streamed and paged data processing, adaptive bounded insert batches, built-in field transforms, and vault-backed field encryption
- MySQL-backed repositories store workflows, connectors, jobs, audit events, and encrypted key-vault values as versioned JSON payloads in database tables
- Schema changes are versioned in `backend/src/main/resources/db/migration` and applied by Flyway at startup
- Optional one-time JSON import: set `DATASIFTER_IMPORT_JSON_STATE=true` to import existing `${user.home}/.datasifter` JSON records into an empty database. The import runs transactionally, marks completion, leaves the JSON files untouched, and refuses to import over existing database records.
- The local JSON profile remains available for development and tests; it uses explicit read/write errors, in-process locking, and temporary-file replacement. It is not safe for multi-instance use.
- Dashboard with KPI cards and execution queue
- Operational health and queue summary endpoints to support phase-1 readiness checks

The local JSON store is an interim single-control-plane persistence option, not a multi-process database. Back up the `.datasifter` directory before upgrades or manual state edits. The default MySQL profile persists repositories and uses Flyway-managed schema migrations.

The cloud-neutral provider contracts do not themselves constitute an installed KMS or object-storage service. Production readiness requires selecting, packaging, and validating those provider plugins and running the deployment smoke checks. Not yet implemented from spec v6: SaaS/API connectors and rate limiting, sandboxed scripting, success/failure gates, 1:many expansion, many:1 aggregation, nested JSON mapping, conflict resolution, and AI-assisted configuration.

## Roadmap

See [Docs/PHASED_ROADMAP.md](./Docs/PHASED_ROADMAP.md) for the project’s phased progression:
- Phase 1: just before production
- Phase 2: enterprise-ready
- Phase 3: multi-tenant and managed deployment
- Workflow catalog with a live authoring surface for editing names, schedule, status, and stage definitions
- Drag-and-drop workflow canvas with draggable node palette, node selection, node inspector, and persisted edge routing
- Mapping editor with editable per-field mappings and privacy actions saved to the selected workflow
- Connector health and policy panels
- KeyVault secret view with masking
- Audit log and settings views
- Local backend API and frontend proxy configuration
- Cross-platform CI validation on Linux, Windows, and macOS and a tag-gated release artifact job

## Local file agent

The backend accepts multipart CSV uploads at `POST /api/workflows/{id}/csv` and stores each uploaded file under the managed `.datasifter/uploads` directory. Both companion agents upload actual file contents, not file metadata. Configure:

- `DATASIFTER_WORKFLOW_ID`
- `DATASIFTER_USERNAME` and `DATASIFTER_PASSWORD` for an operator/admin account
- Optionally `DATASIFTER_ENDPOINT` (shell) or `-Endpoint` (PowerShell), defaulting to `http://localhost:8080`

Run the scripts:

```bash
# Linux/macOS
chmod +x scripts/local-file-agent.sh
DATASIFTER_WORKFLOW_ID=workflow-id DATASIFTER_USERNAME=operator DATASIFTER_PASSWORD='...' \
  DATASIFTER_WATCH_DIR=./data/inbox ./scripts/local-file-agent.sh

# Windows PowerShell
$env:DATASIFTER_WORKFLOW_ID = 'workflow-id'
$env:DATASIFTER_USERNAME = 'operator'
$env:DATASIFTER_PASSWORD = '...'
pwsh ./scripts/local-file-agent.ps1 -WatchDir ./data/inbox
```

Uploaded files are renamed to `.processed` only after the API accepts them. Review [Docs/RELEASE_CHECKLIST.md](./Docs/RELEASE_CHECKLIST.md) before preparing a release. CI results are pending until the workflow runs in GitHub.
