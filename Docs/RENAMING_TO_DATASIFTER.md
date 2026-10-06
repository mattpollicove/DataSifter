# Migrating NexusSync configuration and data to DataSifter

DataSifter uses a new application identity and new default names. This release does not automatically rename, move, or delete existing databases, files, Docker volumes, or credentials. Back up the database, local state, uploaded files, provider configuration, and vault key material before migrating.

## Configuration

- Rename application environment variables by replacing the `NEXUS_` prefix with `DATASIFTER_`, then compare the result with `deploy/.env.example`. For example, `NEXUS_DB_URL` becomes `DATASIFTER_DB_URL`.
- Direct Spring configuration keys formerly beginning with `nexus.` are now under `datasifter.`.
- If using the included Compose deployment, update the domain variable to `DATASIFTER_DOMAIN`. The Caddyfile and Compose environment now use this same name.
- Keep the existing vault passphrase and key/provider material during migration. Changing the application name does not re-encrypt vault contents.

## Database

The default database name is now `datasifter`. Existing MySQL databases are not renamed or migrated automatically. Either:

1. Keep the existing database and set `DATASIFTER_DB_URL` to its current JDBC URL; or
2. Back up the old database, create the new database, restore the backup, and verify Flyway state and application reads before switching production traffic.

Do not point a new empty database at production until the restore and secret-decryption checks have passed. A fresh Compose MySQL volume initializes the `datasifter` database; it does not rename a database inside an existing volume.

## Local files and Compose volumes

The local state default changed from `${user.home}/.nexussync` to `${user.home}/.datasifter`. To retain the existing state without copying it, set `DATASIFTER_DATA_DIRECTORY` to the old directory. Alternatively, stop all DataSifter processes, make a verified backup, copy the complete directory (including vault salt/key material and uploads) to the new location, and verify access before resuming.

The Compose-managed application state volume is now named `datasifter-state`; Docker treats it as a different volume from the previous `nexus-state`. Before deploying, back up the old volume and either configure the old data path or perform a verified volume migration. Do not remove the old volume until DataSifter workflows, file references, and secret reads have been verified.

## Windows project folder

The source tree and Java package names are updated, but the project directory name is intentionally unchanged by this migration. Rename that folder separately after closing IDEs, terminals, and processes that may hold files open; update any local shortcuts or scripts that use its path.
