# Upgrade Plan: DataSifter Backend (20261006124507)

- **Generated**: 2026-10-06 08:45 America/New_York
- **HEAD Branch**: N/A
- **HEAD Commit ID**: N/A

> Notice: The backend directory is not a Git repository. Upgrade changes will remain unversioned in the working directory.

## Available Tools

**JDKs**
- JDK 17.0.20.1: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` (current project JDK, available for baseline)
- JDK 25: **<TO_BE_INSTALLED>** (required for the upgrade and final validation)

**Build Tools**
- Maven 3.9.11: `C:\Tools\apache-maven-3.9.11`
- Maven Wrapper: not present

## Guidelines

> Note: You can add any specific guidelines or constraints for the upgrade process here if needed, bullet points are preferred.

## Options

- Working branch: appmod/java-upgrade-20261006124507
- Run tests before and after the upgrade: true
- Execution mode: automatic (requested by user)

## Upgrade Goals

- Upgrade the backend Java runtime and compile target to Java 25, the latest LTS release.

## Technology Stack

| Technology/Dependency | Current | Min Compatible Version | Why Incompatible |
| --------------------- | ------- | ---------------------- | ---------------- |
| Java | 17 | 25 | User requested the latest LTS runtime |
| Spring Boot parent | 3.3.5 | 3.5.16 | Boot 3.3 predates Java 25 support; 3.5.16 is available and supports Java 25 |
| Maven | 3.9.11 | 3.9.11 | Compatible; no wrapper is present |
| Spring Boot Maven plugin | 3.3.5 (parent-managed) | 3.5.16 (parent-managed) | Must remain aligned with the Spring Boot parent |
| Spring Boot managed compiler/test plugins | Parent-managed | Parent-managed | No explicit plugin overrides found; inherit compatible Boot 3.5.16 plugin management |

## Derived Upgrades

- Spring Boot parent 3.3.5 → 3.5.16: the current Boot release line does not support Java 25; 3.5.16 is the available compatible 3.5 maintenance release and preserves the Spring Boot 3 generation.
- Spring Boot Maven plugin: remains parent-managed and moves with the parent, avoiding a version mismatch.
- No Java source/API rewrites are indicated by the compatibility scan.

## Impact Analysis

### Dependency Changes

| File | Dependency | Current | Action | Target | Reason |
|------|-----------|---------|--------|--------|--------|
| `pom.xml` | `spring-boot-starter-parent` | 3.3.5 | upgrade | 3.5.16 | Spring Boot 3.5.16 supports Java 25 and is available in Maven Central metadata |
| `pom.xml` | `java.version` | 17 | upgrade | 25 | User-requested Java 25 runtime and compilation target |
| `pom.xml` | `spring-boot-maven-plugin` | parent-managed 3.3.5 | upgrade (managed) | parent-managed 3.5.16 | Keep the build plugin aligned with the parent |

### Source Code Changes

No Java source changes are required. Scanned backend main and test sources for internal JDK APIs, reflective access into JDK internals, and APIs requiring a Java 25 rewrite; no upgrade blockers were found.

### Configuration Changes

No application property, YAML, or test-profile changes are required. Existing configuration contains no Java-version-specific settings.

### CI/CD Changes

No CI/CD, Docker, or pipeline definitions are present in the project scope. The only hardcoded Java runtime description is in the root `README.md`.

### Documentation Changes

| File | Location | Current | Required Change | Reason |
|------|----------|---------|----------------|--------|
| `../README.md` | Stack section | Backend: Java 17, Spring Boot 3.3.5 | Update to Java 25 and Spring Boot 3.5.16 | Keep the documented runtime and framework aligned with the backend build |
| `../CHANGELOG.md` | Unreleased / Changed | No Java runtime upgrade entry | Record the Java 25 and Spring Boot 3.5.16 upgrade | The project changelog tracks ongoing changes |

### Risks & Warnings

- **Framework compatibility update**: Moving Spring Boot from 3.3.5 to 3.5.16 updates its managed dependency set. **Mitigation**: Compile production and test sources and run the full existing test suite on Java 25; remediate any failures before completion.
- **Unobserved environment integrations**: Java 25 may expose incompatibilities in deployment environments not represented in the repository. **Mitigation**: Validate local build/tests under JDK 25; deployment runtime image changes cannot be made because no deployment definitions exist in scope.
- **Security scan coverage**: Dependency CVE findings depend on resolved versions after the Boot update. **Mitigation**: Scan resolved direct dependency versions after the upgrade and fix any reported vulnerabilities before final validation.

## Upgrade Steps

- Step 1: Setup Environment
  - **Rationale**: Java 25 is required to compile and run the application at the requested target.
  - **Changes to Make**: Install JDK 25; retain JDK 17 for baseline verification.
  - **Verification**: Detect JDK 25 with the JDK listing tool. Expected: JDK 25 is available.

- Step 2: Setup Baseline
  - **Rationale**: Establish pre-upgrade compile and test results using the current JDK 17.
  - **Changes to Make**: No project file changes.
  - **Verification**: `mvn clean compile test-compile -q` and `mvn clean test -q`, using JDK 17 and Maven 3.9.11. Expected: baseline outcomes and test pass rate recorded.

- Step 3: Upgrade Backend Runtime and Documentation
  - **Rationale**: Set Java 25 and a Java-25-compatible Spring Boot line together so the project remains buildable at the end of the change.
  - **Changes to Make**: Apply the Dependency Changes and Documentation Changes above.
  - **Verification**: `mvn clean test-compile -q`, using JDK 25 and Maven 3.9.11. Expected: production and test sources compile.

- Step 4: Final Validation and CVE Remediation
  - **Rationale**: Confirm the requested target, dependency security status, compilation, and test behavior on the target JDK.
  - **Changes to Make**: If the dependency scan identifies fixable CVEs, update the minimal affected dependency/version pin and preserve Boot BOM alignment where possible.
  - **Verification**: Scan resolved direct dependencies for CVEs; then run `mvn clean test -q` under JDK 25 (and `mvn clean verify -Djacoco.skip=false` if a coverage plugin is configured). Expected: no unremediated fixable CVEs, all tests pass, and Java 25 is the active runtime.
