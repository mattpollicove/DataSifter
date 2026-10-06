# Deployment operations

## Required production configuration

1. Copy `deploy/.env.example` to a deployment-only `deploy/.env` file and replace every sample value with generated credentials. Restrict file permissions; never commit the populated file.
2. Create distinct high-entropy admin and worker credentials. Optional role users must be separately provisioned. The worker token is not a human password.
3. Provision a HashiCorp Vault Transit key and an S3-compatible bucket reachable over HTTPS. The production Compose stack selects the built-in `hashicorp-vault-transit` and `s3-compatible` providers and fails startup if either service or its configuration is unavailable. Create secret files for a least-privilege Vault token and S3 access/secret keys, then set the corresponding `*_SECRET_FILE` paths in `deploy/.env`. Keep those files outside version control; `deploy/secrets/` is ignored.
   - Grant the Vault token `read` on `transit/keys/<key>` and `update` on `transit/encrypt/<key>` and `transit/decrypt/<key>`. A Vault Agent may maintain the mounted token file; the provider rereads it for each Transit operation.
   - Grant the S3 identity bucket-list access for startup readiness plus object get/put/delete access only to the configured bucket. Configure server-side encryption and lifecycle/backup policy at the object store.
4. Supply `DATASIFTER_DB_URL`, `DATASIFTER_DB_USERNAME`, and `DATASIFTER_DB_PASSWORD` from the approved secret store. Restrict database access to the API and workers; use TLS if the database is not on a trusted single-host network.
5. Point `DATASIFTER_DOMAIN` at the deployment and set `DATASIFTER_VAULT_TRANSIT_ADDRESS`, `DATASIFTER_VAULT_TRANSIT_KEY`, `DATASIFTER_STORAGE_S3_ENDPOINT`, and `DATASIFTER_STORAGE_S3_BUCKET`. Ensure the certificates for both HTTPS endpoints are trusted by the Java runtime. Then run:

   ```text
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build
   ```

   Caddy is the only public service. Confirm the certificate is valid and renewed automatically, `GET /actuator/health` responds without exposing details, and authenticated `GET /api/session` succeeds. Verify a secret write/read after Transit key rotation and upload/read/delete of a workflow file across API and worker hosts. Never expose the API, worker, frontend, Vault, object store, or database ports directly to the public network.

## Backup and restore

Back up the MySQL database and the configured object store together before releases, migrations, or key changes. Pause new job submissions and workers for a consistent cut, wait for or cancel active jobs according to the operational policy, record the storage-provider and key-provider versions, and take encrypted, access-controlled snapshots. Retain the KMS key versions and provider configuration needed to decrypt stored secret values. For the local development adapter, include the complete `DATASIFTER_DATA_DIRECTORY`; it is not a multi-host backup strategy.

After a backup, restore the database and object storage to an isolated environment using the selected provider's documented restore tools. Validate Flyway state, workflow references, secret decryption, a representative file read, and a test workflow before considering the backup recoverable. Do not perform restore validation over production data.

## Key rotation

### Managed provider

- Use the key provider's approved rotation process. New secret writes use the provider's active key reference; existing envelope ciphertext records the reference needed to unwrap its data key.
- Keep prior key versions enabled for decrypt until every ciphertext and backup that depends on them has passed retention or been deliberately rewrapped. Changing a KMS alias alone is not proof that old key versions are safe to retire.
- Coordinate plugin rollout across API and worker processes. Verify reads of secrets encrypted before and after rotation and keep a recoverable provider configuration.
- The local `POST /api/keyvault/rotate` endpoint returns `409 Conflict` when a managed provider is active; it must not be used to rotate provider-managed keys.

### Local passphrase provider

- Back up MySQL and the local vault state. Pause workers and stop other API replicas before rotation.
- Call `POST /api/keyvault/rotate` as an administrator with the new passphrase, then update `DATASIFTER_VAULT_PASSPHRASE` in the deployment secret store before restarting any process.
- Restart one process and verify secret access before resuming workers. If any step fails, keep the backup and do not restart other instances with an uncertain passphrase.

## Provider migration limits

The provider interfaces do not automatically migrate data between providers. Moving from local storage to an object store requires copying each referenced object and updating workflow references as a controlled migration; retain the old store until every workflow and backup is verified. Moving local vault ciphertext to a managed provider requires a controlled migration with the old passphrase available so secrets can be decrypted and re-encrypted. Keep the old key/provider available until the migration is proven complete.

## Operational limits

- A shared Docker named volume supports replicas on one Docker host only. Multi-host workers require a provider offering shared durable object storage and an orchestrator that mounts the same provider plugin/configuration into all replicas.
- Vault Transit tokens may be refreshed through the mounted token file; S3 access/secret key file changes require restarting API and worker processes because the S3 client captures credentials at startup.
- The queue is at-least-once. Use destination-side idempotency/unique keys and verify retry/expired-lease recovery behavior.
- Backups, Vault key retention/restore, object-store encryption and recovery, live connector TLS chains, and multi-host recovery must be exercised in a staging deployment; local unit tests do not certify external-service behavior.
