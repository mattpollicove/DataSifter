# DataSifter Application Changes for Migration

## 2026-10-06

### JSON persistence safety update

#### MySQL persistence and migration update

#### Files changed
- `backend/pom.xml`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__create_control_plane_tables.sql`
- `backend/src/main/java/com/datasifter/repository/Jdbc*.java`
- `backend/src/main/java/com/datasifter/support/JsonStateImporter.java`
- `RULES.md`
- `backend/src/test/resources/application-test.yml`
- `backend/src/test/java/com/datasifter/repository/JdbcRepositoryPersistenceTest.java`
- `README.md`

#### Applicable platforms
- Linux (Debian/Fedora/Arch)
- macOS
- Windows

#### Old -> New: application persistence backend
Old behavior:
- Default runtime repositories persisted state only to local JSON files under the current user's home directory.
New behavior:
- The default Spring profile uses MySQL repositories and Flyway-managed schema migrations; the existing JSON repositories remain selectable with the `json` profile.

#### Upgrade and import notes
- Configure `DATASIFTER_DB_URL`, `DATASIFTER_DB_USERNAME`, and `DATASIFTER_DB_PASSWORD` before launching the default profile.
- Create the target MySQL database before startup; Flyway creates and evolves the DataSifter tables inside it.
- To import a previous JSON installation once, set `DATASIFTER_IMPORT_JSON_STATE=true` while connecting to an empty database. Import runs in a database transaction, writes a completion marker, and does not delete or modify source JSON files.
- Validate backups before changing persistence backends. Do not import when target DB has records; the importer rejects non-empty target tables.

### Files changed
- `backend/src/main/java/com/datasifter/support/JsonFileStore.java`
- `backend/src/main/java/com/datasifter/repository/`
- `backend/src/test/java/com/datasifter/support/JsonFileStoreTest.java`
- `backend/src/test/java/com/datasifter/repository/JsonRepositoryPersistenceTest.java`
- `README.md`

#### Applicable platforms
- Linux (Debian/Fedora/Arch)
- macOS
- Windows

#### Old -> New: preserve state errors and write replacement
Old behavior:
```java
catch (IOException e) {
    return new ArrayList<>();
}
```
New behavior:
```java
catch (IOException e) {
    throw new IllegalStateException("Unable to read JSON store: " + normalizedFile, e);
}
```
Writes now go to a temporary file in the same directory and replace the destination atomically where supported, falling back to a standard replace move if the filesystem does not support atomic moves.

#### Platform notes
- Uses Java NIO APIs and same-directory temporary files; no shell or OS-specific paths were added.
- File replacement is atomic only where the filesystem/provider supports it.
- Locking coordinates repository access inside one JVM. This JSON persistence layer is not safe for concurrent multi-process writers; a database is still required before multi-instance production deployment.

### Files changed
- `RULES.md`
- `backend/src/main/java/com/datasifter/support/JsonFileStore.java`
- `backend/src/main/java/com/datasifter/service/KeyVaultService.java`
- `backend/src/main/java/com/datasifter/controller/DashboardController.java`

### Applicable platforms
- Linux (Debian/Fedora/Arch)
- macOS
- Windows

### Summary of modifications

#### Old -> New: portable file state
Old behavior:
```java
Paths.get("C:\\Users\\...\\datasifter")
```
New behavior:
```java
Paths.get(System.getProperty("user.home"), ".datasifter")
```
Why: Keeps runtime data local to the current user and avoids hard-coded Windows-specific path assumptions.

#### Old -> New: stricter secret validation
Old behavior:
```java
if (secret.getValue() == null || secret.getValue().isBlank()) {
    throw new IllegalArgumentException("Secret value cannot be blank");
}
```
New behavior:
```java
String name = normalizeRequired(secret.getName(), "Secret name");
String type = normalizeOptional(secret.getType(), "database");
String status = normalizeOptional(secret.getStatus(), "active");
String value = normalizeRequired(secret.getValue(), "Secret value");
```
Why: This reduces invalid secret payloads and trims values before persistence while maintaining cross-platform stability.

#### Old -> New: project-level rules adaptation
Old behavior:
- Generic stack-specific rules were copied into the project without adaptation.
New behavior:
- The project now documents rule intent specific to Java/Spring Boot + React and notes old mismatches explicitly in `RULES.md`.

#### Old -> New: removing stale mock dashboard state
Old behavior:
```java
if (jobs.isEmpty()) {
    jobs = List.of(
        new JobStatus("HR User Sync", ...),
        new JobStatus("Customer Delta Feed", ...),
        new JobStatus("Finance Reconciliation", ...)
    );
}
```
New behavior:
```java
List<Alert> alerts = jobs.isEmpty()
    ? List.of(new Alert("info", "No workflow jobs are currently running."))
    : List.of(new Alert("success", "Control-plane state is current."));
```
Why: Keeps the UI and API aligned with real state and avoids reintroducing stale example data into a control-plane that is expected to reflect actual work.

### Notes
- The repo is still at the MVP control-plane layer. It is portable and more robust than a mock-only implementation, but it is not yet a full encrypted vault or distributed worker system.
- The project still needs deeper execution and security work before it reaches enterprise-grade production maturity.
