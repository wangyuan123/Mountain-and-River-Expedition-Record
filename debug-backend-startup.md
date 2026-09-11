# Debug Session: backend-startup

- Status: [OPEN]
- Symptom: `spring-boot:run` exits with code 1; Maven only reports `MojoExecutionException` summary.
- Scope: Backend startup.

## Falsifiable hypotheses

1. Recent battle-report changes introduced a Java compilation or Spring bean wiring failure.
   - Evidence: Maven output includes compiler errors or a bean creation exception referencing edited classes.
2. Database migration or connection fails during application initialization.
   - Evidence: Startup stack trace includes Flyway, JDBC, Hibernate, or connection errors.
3. Required configuration/environment values are missing.
   - Evidence: Startup stack trace includes placeholder resolution, binding, or required-property errors.
4. The configured HTTP port is occupied.
   - Evidence: Startup trace includes `Port ... was already in use`.

## Evidence

- Confirmed by runtime stack trace: `DepotService.<init>` fails because final fields `playerRepository` and `buildingRepository` were not initialized.
- Confirmed by IDE diagnostics in `DepotService.java`: both fields were reported as uninitialized.
- Rejected: database, configuration, port, and battle-report changes are not involved in this startup failure.

## Fix

- Assigned the constructor parameters to `this.playerRepository` and `this.buildingRepository`.

## Verification

- IDE diagnostics for `DepotService.java`: no errors.
- Direct Java 17 compilation of `DepotService.java` against the project classes/dependencies: passed.
- Pending user verification: rebuild and start the backend in the Maven-capable environment.
