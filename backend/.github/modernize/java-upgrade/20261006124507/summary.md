# Upgrade Summary: DataSifter Backend (20261006124507)

## Outcome

The backend has been upgraded to Java 25 LTS. The build uses Spring Boot 3.5.16, the available compatible maintenance release, and the existing application code remains unchanged.

## Changes

| File | Change |
|------|--------|
| `backend/pom.xml` | Updated the Spring Boot parent from 3.3.5 to 3.5.16 and `java.version` from 17 to 25; configured Surefire to launch Mockito as a Java agent |
| `README.md` | Updated the documented backend Java and Spring Boot versions |
| `CHANGELOG.md` | Recorded the runtime and framework upgrade under Unreleased / Changed |

## Verification

| Check | Result |
|-------|--------|
| Java 17 baseline `mvn clean compile test-compile -q` | Passed |
| Java 17 baseline `mvn clean test -q` | 13/13 tests passed |
| Java 25 `mvn clean test-compile -q` | Passed; production and test sources compile |
| Java 25 `mvn clean test -q` | 13/13 tests passed |
| Java 25 `mvn clean verify -Djacoco.skip=false -q` | Passed; 13/13 tests passed |
| Java 25 follow-up `mvn clean test` with Mockito agent | Passed; 13/13 tests passed; Mockito self-attachment warning eliminated |
| Direct dependency CVE scan | No known CVEs requiring remediation were reported for the 10 resolved direct dependencies |

Java 25.0.2 LTS and Maven 3.9.11 were used for target validation. No JaCoCo plugin is configured, so coverage metrics were not available.

## Key Risks and Notes

- Configuring Mockito as a Surefire Java agent eliminated the Mockito dynamic self-attachment warning. Maven 3.9.11 still emits an unrelated Guice `sun.misc.Unsafe` deprecation warning during Maven startup; the Java 25 test run succeeds.
- The Java 25 test JVM also emits a class-data-sharing warning when Mockito's agent appends to the bootstrap class path; all tests pass.
- CVE validation covered resolved direct dependencies. No reported CVE required a dependency change.
- The backend folder is not a Git repository, so no branch or commit was created.

## Execution

- **Executed by**: MGP
- **Completed**: 2026-10-06
- **Result**: Successful
