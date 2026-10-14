# Frontend-Backend Integration and Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete real frontend-backend integration on the current backend refactor and frontend branches, fix contract/runtime defects, and produce verified release artifacts and an operator checklist without exposing credentials.

**Architecture:** Keep the existing two-checkout workflow: `/home/dream/Projects/innovation-space-management` runs the backend refactor branch and `/home/dream/Public/innovation-space-management` runs the `frontend` branch. Use an isolated local MySQL container and synthetic accounts for end-to-end verification. Production deployment is same-origin through a reverse proxy because backend CORS remains intentionally disabled.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, MySQL 8.0, Maven Wrapper, Vue 3, TypeScript, Vite, Vitest, pnpm, Docker Engine, Nginx.

**Spec:** `/home/dream/Public/innovation-space-management/docs/api/openapi.yaml`, `/home/dream/Projects/innovation-space-management/docs/requirements.md`, and `/home/dream/Projects/innovation-space-management/backend/README.md`.

## Global Constraints

- Preserve all existing HTTP routes, response envelopes, role rules, database behavior, and business behavior.
- Do not commit real accounts, passwords, API keys, database dumps, tokens, or `.env` files.
- Student and direction-leader login uses student number plus password; college-admin credentials are supplied privately by the project owner.
- The frontend must use same-origin `/api/v1/...` paths in production and must not call OJ or AI providers directly.
- IDs remain decimal strings in JSON and must not be converted to JavaScript `Number`.
- Production disables Swagger/Knife4j endpoints and serves the SPA and API through one origin.
- The current contract copy is authoritative when it contains newer confirmed daily-report semantics; synchronize backend OpenAPI copies from it rather than reverting confirmed frontend behavior.

## Review Focus

- OpenAPI drift can compile cleanly while sending incompatible daily-report fields; verify generated frontend types and backend docs use the same schema.
- A dev proxy can hide same-origin production routing failures; verify both Vite proxy and a production Nginx-style reverse proxy.
- Session refresh and forced password changes are cross-cutting flows; exercise them after full page reload and concurrent requests.
- Production configuration can accidentally expose Knife4j, stack traces, mock providers, or secrets; boot the packaged JAR with `prod` and probe documented endpoints.
- Release artifacts can build while missing operator prerequisites such as TLS, backups, account delivery, MySQL support lifecycle, and external AI/OJ controls; record explicit go/no-go gates.

---

## Task 1: Align Baselines and API Contract

**Files:**
- Modify: `/home/dream/Projects/innovation-space-management/backend/src/main/resources/api-contract.yaml`
- Modify: `/home/dream/Projects/innovation-space-management/docs/api/openapi.yaml`
- Modify: `/home/dream/Public/innovation-space-management/frontend/src/api/types.gen.ts`
- Test: `pnpm test`, `pnpm type-check`, `backend/mvnw test`

**Interfaces:**
- Consumes: the confirmed OpenAPI document at `/home/dream/Public/innovation-space-management/docs/api/openapi.yaml`.
- Produces: identical OpenAPI content in both repositories and regenerated frontend types with no pending diff.

- [ ] **Step 1: Record baseline status and checksums**
- [ ] **Step 2: Copy the confirmed frontend OpenAPI document to both backend contract locations**
- [ ] **Step 3: Regenerate frontend API types and confirm a clean Git diff**
- [ ] **Step 4: Run frontend tests/type-check and backend unit tests**
- [ ] **Step 5: Commit synchronized contract changes in each repository**

## Task 2: Build an Isolated Integration Dataset

**Files:**
- Create only ignored local runtime state under `/tmp` and Docker's named test resources.
- Test: health, migration, admin bootstrap, login and member creation HTTP calls.

**Interfaces:**
- Consumes: packaged backend JAR, Docker MySQL 8.0.46, private synthetic environment variables.
- Produces: one disposable database with one synthetic college admin, one direction leader, and one student suitable for browser testing.

- [ ] **Step 1: Start a uniquely named MySQL container bound to loopback**
- [ ] **Step 2: Run controlled Flyway migration through the packaged JAR**
- [ ] **Step 3: Bootstrap one synthetic college admin without logging secrets**
- [ ] **Step 4: Create synthetic leader/student accounts through authenticated APIs**
- [ ] **Step 5: Verify health and role-scoped API responses**

## Task 3: Run Real Frontend-Backend Integration

**Files:**
- Modify only if defects are found in `frontend/src/**`, `backend/src/**`, or contract files.
- Test: existing unit/integration suites plus browser/API smoke flows.

**Interfaces:**
- Consumes: Task 2 accounts and running backend, Vite dev proxy with `VITE_USE_MOCK=false`.
- Produces: evidence that login, password lifecycle, dashboards, reports, rankings, pagination, and role restrictions work through the real HTTP stack.

- [ ] **Step 1: Run frozen dependency install and the complete frontend quality suite**
- [ ] **Step 2: Start the packaged backend and Vite in real API mode**
- [ ] **Step 3: Exercise admin, leader, and student login and dashboards**
- [ ] **Step 4: Exercise daily report create/update/submit and points/ranking responses**
- [ ] **Step 5: Exercise reload, token refresh, permissions, empty/error states, and mobile layout**
- [ ] **Step 6: Save a concise integration report with commands and observed status codes**

## Task 4: Fix Integration Defects with Regression Tests

**Files:**
- Modify the smallest backend/frontend/contract files implicated by Task 3.
- Test: add or update focused tests before each behavioral fix.

**Interfaces:**
- Consumes: Task 3 findings.
- Produces: zero known integration blockers and regression tests proving each fix.

- [ ] **Step 1: Reproduce each finding through a focused API or UI test**
- [ ] **Step 2: Implement the minimal compatible fix**
- [ ] **Step 3: Run focused tests and then the relevant complete suites**
- [ ] **Step 4: Commit each independently reviewable fix**

## Task 5: Add Production Deployment Artifacts

**Files:**
- Create: `/home/dream/Projects/innovation-space-management/deploy/nginx.conf`
- Create: `/home/dream/Projects/innovation-space-management/deploy/docker-compose.yml`
- Create: `/home/dream/Projects/innovation-space-management/deploy/README.md`
- Create: `backend/Dockerfile`
- Create: `/home/dream/Public/innovation-space-management/frontend/Dockerfile`
- Test: packaged backend `prod` probes and frontend production preview through the reverse-proxy route rules.

**Interfaces:**
- Consumes: backend JAR and frontend static build.
- Produces: a same-origin production topology with `/api`, `/actuator/health`, SPA fallback, cache rules, and security headers; no credentials committed.

- [ ] **Step 1: Add reproducible backend and frontend container builds**
- [ ] **Step 2: Add Nginx same-origin routing and conservative security/cache headers**
- [ ] **Step 3: Add Compose wiring with environment variables but no secret values**
- [ ] **Step 4: Boot the packaged backend in `prod` and verify docs are hidden while health works**
- [ ] **Step 5: Build the frontend and verify SPA/API route behavior through Nginx**
- [ ] **Step 6: Commit deployment artifacts and validation evidence**

## Task 6: Final Release Readiness Verification

**Files:**
- Create: `/home/dream/Projects/innovation-space-management/docs/superpowers/reports/2026-10-13-frontend-backend-release-readiness.md`
- Modify: release checklist only where verified facts require updates.

**Interfaces:**
- Consumes: Tasks 1-5 outputs.
- Produces: a go/no-go checklist separating completed engineering checks from owner decisions.

- [ ] **Step 1: Re-run backend `clean verify` and frontend test/type-check/lint/build**
- [ ] **Step 2: Verify repository scans contain no committed secrets or generated local artifacts**
- [ ] **Step 3: Verify packaged `prod` security behavior and same-origin routing**
- [ ] **Step 4: Document unresolved owner gates: TLS/domain, account delivery, backups, MySQL lifecycle, AI provider privacy, and Judge0/GPL compliance**
- [ ] **Step 5: Commit and push the final report and deployment work**
