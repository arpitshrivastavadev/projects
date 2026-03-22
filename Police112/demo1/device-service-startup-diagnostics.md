# Device Service Startup Diagnostics (Windows / PowerShell)

This guide explains why `device-service` can show only the banner and then exit with code `1`, and how to capture the real exception.

## Findings from this repository

### 1) No bootRun customization that suppresses output
- Root and module Gradle files do not customize `bootRun` to swallow output or call `System.exit`. (`build.gradle`, `settings.gradle`, `device-service/build.gradle`).

### 2) Main application has no explicit early-exit hooks
- `DeviceServiceApplication.main` only calls `SpringApplication.run(...)`.
- No `CommandLineRunner` / `ApplicationRunner` / `System.exit(...)` / `SpringApplication.exit(...)` calls were found under `device-service` and `common` Java sources.

### 3) Most likely reason for "banner then exit" in this repo
Two config patterns make silent-looking startup failures likely:

1. **No default Spring profile is set** in `device-service/src/main/resources/application.yaml`, while datasource is only defined in `application-dev.yaml` and `application-prod.yaml`.
   - If no profile is active, JPA + Flyway can fail very early due to datasource configuration.

2. **`logback-spring.xml` only defines root appenders inside `dev` and `prod` profile blocks**.
   - If no profile is active, there may be no effective root appender for many startup messages.

## Exact commands to capture the real exception

> Run these from repository root.

### A) Verify Java and Gradle
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\gradlew.bat --version
```

### B) Verify executable JAR metadata (Main-Class + Start-Class)
```powershell
jar tf .\device-service\build\libs\device-service.jar | Select-String "META-INF/MANIFEST.MF"
jar xf .\device-service\build\libs\device-service.jar META-INF\MANIFEST.MF
Get-Content .\META-INF\MANIFEST.MF
```
Expected:
- `Main-Class: org.springframework.boot.loader.launch.JarLauncher`
- `Start-Class: com.police.iot.device.DeviceServiceApplication`

### C) Run JAR and split stdout/stderr
```powershell
$runLogDir = ".\logs\device-service-debug"
New-Item -Path $runLogDir -ItemType Directory -Force | Out-Null

java -jar .\device-service\build\libs\device-service.jar --spring.profiles.active=dev --debug --spring.main.log-startup-info=true 1> "$runLogDir\jar.out.log" 2> "$runLogDir\jar.err.log"
$LASTEXITCODE
```

### D) Run bootRun with plain console and split stdout/stderr
```powershell
.\gradlew.bat --stop
.\gradlew.bat :device-service:bootRun --no-daemon --console=plain --stacktrace --info --args="--spring.profiles.active=dev --debug --spring.main.log-startup-info=true" 1> .\logs\device-service-debug\bootRun.out.log 2> .\logs\device-service-debug\bootRun.err.log
$LASTEXITCODE
```

### E) Show last lines + key patterns
```powershell
Get-Content .\logs\device-service-debug\bootRun.err.log -Tail 200
Get-Content .\logs\device-service-debug\bootRun.out.log -Tail 200

Select-String -Path .\logs\device-service-debug\*.log -Pattern "APPLICATION FAILED TO START|Caused by:|BeanCreationException|Flyway|DataSource|BindException|Port|placeholder|IllegalArgumentException|UnsatisfiedDependencyException"
```

## Likely causes and how to prove/disprove

1. **Missing config placeholders / no active profile**
   - Symptom: unresolved placeholders (`${...}`), datasource setup errors, bean creation failures.
   - Prove: run with `--spring.profiles.active=dev`; if it starts, default profile was the issue.

2. **Flyway migration failure**
   - Symptom: `FlywayException`, SQL syntax/permission/schema errors.
   - Prove: look for `Flyway` stack trace in `*.err.log`.

3. **DataSource / DB connection failure**
   - Symptom: `CannotGetJdbcConnectionException`, connection refused, auth failed.
   - Prove: check `Caused by` chain for PostgreSQL connection errors.

4. **Port bind conflict**
   - Symptom: `Port 8081 was already in use` / `BindException`.
   - Prove: run with `--server.port=18081`; if startup works, original port conflicted.

5. **Security bean wiring failure**
   - Symptom: `UnsatisfiedDependencyException` around `SecurityConfig` / `TenantFilter`.
   - Prove: bean creation stack trace names the failing class/constructor.

6. **Profile mismatch (`prod` without env vars)**
   - Symptom: missing `DB_HOST`, `DB_NAME`, etc.
   - Prove: run with `--spring.profiles.active=dev` and compare behavior.

## Recommended config changes to make failures visible

1. Set default profile for local startup.
2. Keep startup info enabled.
3. Ensure logback has a default profile appender so logs appear even when no explicit profile is passed.
4. Add a local profile for no-infra debugging if needed.

### Suggested snippets

`device-service/src/main/resources/application.yaml`
```yaml
spring:
  profiles:
    default: dev
  main:
    log-startup-info: true
```

`common/src/main/resources/logback-spring.xml`
```xml
<springProfile name="default,dev">
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</springProfile>
```

Optional local troubleshooting profile:
```yaml
# application-local.yaml
spring:
  config:
    activate:
      on-profile: local
  flyway:
    enabled: false
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

> Use local profile only for startup debugging of non-DB beans; do not use it for integration readiness checks.

## If you want me to interpret your real failure
Run this and paste output:
```powershell
Get-Content .\logs\device-service-debug\bootRun.err.log -Tail 200
Get-Content .\logs\device-service-debug\bootRun.out.log -Tail 200
```
