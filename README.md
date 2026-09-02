# RCDIS Agent

RCDIS-agent is a small-scale laboratory research fund management Agent built with Java 17, Spring Boot 3, Spring AI, MyBatis-Plus, PostgreSQL, and SSE.

## Current Status

The project currently contains the initial backend architecture:

- Spring Boot Maven project
- MVC package structure
- Common API response model
- Global exception handling
- OpenAPI / Swagger UI integration
- Model provider configuration from `application.yml`
- Basic chat and SSE endpoints
- MyBatis-Plus mapper sample
- Feishu bot client placeholder and webhook implementation
- Request context interceptor and `@CurrentUser` controller argument injection
- MyBatis-Plus audit metadata auto-fill
- Basic audit operation AOP logs
- Web console frontend (Vue 3 + Element Plus, see `frontend/README.md`)

## Frontend

The web console lives in `frontend/`. It covers the Agent chat workbench (SSE
streaming with tool-call timeline and confirmation cards), research projects,
model provider management, and Feishu notification testing.

```bash
cd frontend
npm install
npm run dev
```

Then open `http://localhost:5173`. The dev server proxies `/api` requests to
the Spring Boot backend on port 8080.

## Local Development

Required tools:

- JDK 17
- Maven 3.9+
- PostgreSQL 14+

Run tests:

```bash
mvn test
```

Start application:

```bash
mvn spring-boot:run
```

Open API documentation:

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

## Configuration

Copy `.env.example` into your local environment or configure variables in your IDE.

Primary configuration lives in:

```text
src/main/resources/application.yml
```

The first version reads model providers from `application.yml`. Later versions can migrate provider metadata to PostgreSQL while keeping secrets in environment variables or a secret manager.

Initial PostgreSQL schema reference:

```text
docs/database-schema.sql
```

Initialize local PostgreSQL after setting `PGPASSWORD`:

```powershell
$env:PGPASSWORD = "your-postgres-password"
.\scripts\init-postgres.ps1
```
