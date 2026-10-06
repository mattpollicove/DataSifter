# DataSifter Phased Roadmap

## Phase 1 — Just before production

This phase is the minimum gate before the project can be treated as a production-ready platform for internal or limited external use.

### Goals
- Replace the local JSON-first state with a durable persistence layer and schema migration path.
- Add durable queue semantics for workflow execution with retries, backoff, and real worker health tracking.
- Add end-to-end observability: health endpoints, queue summaries, and audit correlation.
- Harden secret handling with a real KMS or managed vault integration path.
- Add secure access controls for the control plane and connector policy enforcement at runtime.
- Validate release packaging and cross-platform compatibility in CI.

### Phase 1 work already underway
- Service/repository layering for workflows, connectors, audit, jobs, and secrets.
- MySQL-backed repositories with Flyway schema migrations and an opt-in transactional import path from legacy local JSON.
- Portable JSON profile retained for isolated development and testing.
- Job lifecycle status improvements including pause/resume/cancel semantics.
- Durable database job claiming with expiring worker leases, authenticated worker heartbeats, retry/backoff, and automatic recovery of expired leases.
- Shared-token worker API for claiming jobs, reporting progress, completing/failing work, and checking active worker lease health.
- Standalone worker runtime: the same backend artifact can run in a separate JVM/container with its web port disabled and worker polling enabled; multiple worker replicas use the shared MySQL lease queue.
- Pluggable `WorkflowFileStore` contract with a local development implementation and a built-in S3-compatible provider for shared object storage. Production startup requires shared storage.
- Pluggable `VaultKeyProvider` contract with a local development implementation and a built-in HashiCorp Vault Transit provider for envelope-key wrapping. Production startup requires the managed provider.
- Container deployment definition with separate API and worker services, private MySQL networking, shared local state for single-host operation, and Caddy-managed HTTPS ingress.
- Streamed CSV, forward-only JDBC source queries (MySQL/PostgreSQL drivers), and paged LDAPS sources to MySQL/PostgreSQL targets; target values use parameterized batches and strict identifier checks.
- Heap-aware bounded JDBC insert batching; LDAP results are paged. Field transforms include trim/lowercase/uppercase and privacy actions include mask, SHA-256, vault-backed encryption, and drop.
- Persisted workflow canvas nodes/edges and field mappings, and multipart CSV upload from both shell and PowerShell file agents.
- Role-enforced environment-configured HTTP Basic users, CSRF protection for browser mutations, and separate shared-token worker authentication.
- Passphrase-derived, versioned AES-GCM vault encryption with legacy ciphertext migration, fail-closed decryption, and explicit re-encryption/rotation.
- Cross-platform Linux/Windows/macOS CI matrix and tag-gated release artifact packaging with a release smoke checklist.
- Secret masking and encrypted-at-rest storage for local MVP use.
- Operational health and queue-summary endpoints.

### Exit criteria
- Workflow orchestration runs through a durable queue with recoverable state.
- Worker heartbeats/recovery semantics are tested; run workers as separate replicas against the MySQL queue and configure only workflows with validated targets.
- The production compose profile selects the built-in Vault Transit and S3-compatible providers and requires their external services. Provisioning the key, bucket, permissions, TLS trust, and live staging validation remain release blockers.
- The local filesystem adapter is single-host only. Multi-host workers require an installed shared object-store adapter; a shared Docker named volume is not a multi-host storage solution.
- LDAPS certificate verification uses the JVM trust store; production deployments must provision and validate the directory CA chain.
- Release CI and a cross-platform smoke checklist are in place; actual CI and container-release runs must pass before publishing.

## Phase 2 — Enterprise-ready

This phase moves the platform from internal MVP toward enterprise-class deployment and governance.

### Goals
- Extend the initial environment-configured RBAC to enterprise identity providers, centralized user lifecycle, and stronger audit governance.
- Add tenant isolation and environment separation.
- Add connector network policy and certificate validation.
- Add versioned workflow templates and change approvals.
- Add immutable audit evidence and long-term retention.
- Add stronger operational dashboards and alerting.
- Expand provider integrations as needed and certify key-version rotation and data-recovery behavior against the selected production deployment.

### Exit criteria
- Multiple environments are supported with separate secrets, policies, and execution data.
- The platform can be governed by enterprise compliance and operations teams.
- Workflow approvals and audit retention are in place.

## Phase 3 — Multi-tenant and managed deployment

This phase targets managed, scalable deployment across teams or customers.

### Goals
- Support multi-tenant partitioning, isolated storage, and secure routing.
- Add managed deployment options for cloud or hybrid infrastructure.
- Provide built-in disaster recovery, backup restore, and release orchestration.
- Add enterprise SSO and policy enforcement at scale.
- Provide multi-region deployment and operational resilience.
- Add SaaS/API connectors with configurable rate limits, sandboxed scripting, and conflict-resolution policies.
- Implement the remaining v6 processing modes: success/failure gates, expansion/aggregation, nested JSON mapping, and AI-assisted workflow/mapping authoring.

### Exit criteria
- The platform can be operated as a managed service with isolated tenants and resilient deployment.
- Support teams can manage environment lifecycle, upgrades, and recovery with minimal manual intervention.
