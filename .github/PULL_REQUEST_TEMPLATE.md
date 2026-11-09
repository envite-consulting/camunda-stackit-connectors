# Description

<!-- What does this change do, and why? Link the issue it closes. -->

Closes #

## Type of change

- [ ] Bug fix
- [ ] New feature / new connector operation
- [ ] Breaking change (element template properties removed or renamed, public API changed)
- [ ] Build / CI / docs only

# Checklist

## Tests

- [ ] Unit tests added or updated for the changed behaviour (`./mvnw test`).
- [ ] Integration tests added or updated where the change touches the STACKIT
      API or the connector runtime (`@Tag("integration")`, run by `./mvnw verify`,
      **requires a running Docker daemon**).
- [ ] `./mvnw verify` passes locally.

## Element templates

Element templates are **generated build output** — never hand-edit the JSON.

- [ ] Templates regenerated with `./mvnw -DskipTests process-classes` and the
      result **committed** (the `element-templates` CI job re-generates and diffs
      the working tree, so an uncommitted regeneration fails the build).
- [ ] The `hybrid/*-hybrid.json` variants are committed alongside the plain ones.
- [ ] If any template **property** was added, removed, renamed or had its binding
      changed: `@ElementTemplate(version = ...)` was bumped **and** a snapshot of
      the previous version was archived under
      `<module>/element-templates/versioned/`.
      (`versioned/` is excluded from the CI drift check on purpose — those
      snapshots are pinned and are not regenerated.)
- [ ] `engineVersion` on `@ElementTemplate` still matches the Camunda minor
      version this release targets (`version.camunda` / `version.connectors`).
- [ ] Templates still validate against the Zeebe JSON schema
      (`npm ci --prefix tools && node tools/validate-element-templates.mjs`).

## Formatting and licensing

- [ ] `./mvnw -P'!autoFormat' -PcheckFormat validate` passes.
      A plain `./mvnw verify` auto-applies Spotless and the license headers
      (the `autoFormat` profile is active by default); if the check above fails,
      run `./mvnw spotless:apply license:format` and commit the result.
- [ ] New files carry the Apache-2.0 license header.

## Documentation

- [ ] `docs/` updated (new properties, new operations, new error codes).
- [ ] `README.md` updated if the change affects installation or supported
      Camunda / STACKIT versions.
- [ ] Breaking changes and required manual migration steps are called out in the
      PR description above.

## Dependencies

- [ ] No new dependency with a copyleft license (GPL / AGPL / LGPL / SSPL) — the
      project is Apache-2.0 and ships shaded `*-with-dependencies.jar` artifacts.
- [ ] `io.camunda.connector:*` and `io.camunda:*` versions were bumped **in
      lockstep** (the Connector SDK minor must match the Camunda runtime minor),
      together with the `@ElementTemplate(engineVersion)` ranges.
