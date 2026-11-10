# Security Policy

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.**

Report it privately through
[GitHub's private vulnerability reporting](https://github.com/envite-consulting/camunda-stackit-connectors/security/advisories/new),
or by email to **security@envite.de** with `camunda-stackit-connectors` in the subject.

Please include the affected connector and version, the Camunda version and deployment type,
a description of the impact, and the steps to reproduce. If you have a proof of concept,
send it — but never include real credentials, access keys or STACKIT project identifiers.

We will acknowledge your report and keep you updated on the fix. If you would like credit in
the advisory, say so and tell us how you want to be named.

## Supported versions

Only the latest release line receives security fixes. Connector releases track Camunda minor
versions, because the Connector SDK's minor version must match the Connector Runtime's — see
the compatibility table in the [README](README.md).

## Response targets

These mirror the
[Camunda Marketplace security guidelines](https://camunda.com/trust-center/marketplace-security/),
which apply to connectors listed on the Marketplace. They are best-effort commitments.

| CVSS v3 severity | Acknowledge | Mitigate | Fix |
| --- | --- | --- | --- |
| Critical (9.0–10.0) | 24 hours | 48 hours | 4 weeks |
| High (7.0–8.9) | 48 hours | 72 hours | 6 weeks |
| Medium (4.0–6.9) | 5 days | 10 days | 8 weeks |
| Low (0.1–3.9) | Accepted risk | — | — |

## Security properties of these connectors

What we commit to, and what you should verify in a review:

- **All traffic is TLS.** STACKIT endpoints are HTTPS only; the connectors never downgrade.
- **Credentials are never persisted.** A connector receives credentials per job invocation
  and holds them only for that invocation. Nothing is written outside your Camunda cluster.
- **Credentials are never logged.** Every model holding a credential redacts it in
  `toString()`, and error messages name the bucket, key or endpoint rather than the key
  material. If you ever find a credential in a log line or a Camunda incident message, that
  is a vulnerability — report it.
- **Input is validated** with Jakarta Bean Validation before any request is issued, and the
  constraints are mirrored into the element template so Modeler rejects bad input early.
- **No obfuscation.** Dependency versions stay detectable by scanners; nothing is shaded in a
  way that hides what we ship.

## Handling credentials in your processes

Do not put a STACKIT access key or service account key literally into a BPMN diagram — it
ends up in your repository, your deployment history and anyone's Modeler.

Use Camunda secrets instead:

```
{{secrets.STACKIT_S3_ACCESS_KEY}}            legacy syntax, resolved by the connector runtime
=camunda.secrets.STACKIT_S3_ACCESS_KEY       recommended, resolved before job activation
```

Two things that catch people out:

- Since Camunda 8.9 the default environment secret provider applies the prefix `SECRET_`, so
  `{{secrets.MY_KEY}}` reads the environment variable `SECRET_MY_KEY`.
- A `{{secrets.X}}` reference only resolves at runtime if the *same* secret was already
  referenced in the *same* field in the deployed BPMN. Injecting a secret reference into a
  field at runtime does not work, by design.

## Scoping STACKIT credentials

STACKIT Object Storage access keys are **project-scoped, not bucket-scoped**: by default
every credential in a project can read and write every bucket in that project. Credentials
groups — or separate STACKIT projects — are the only isolation mechanism. Give a process its
own credentials group, and remember that an access key's secret is shown only once, at
creation.

See
[STACKIT's credentials documentation](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/).

## Dependencies

Dependencies are updated by Dependabot (`.github/dependabot.yml`) and reviewed on every pull request by
`actions/dependency-review-action`, which fails on high-severity advisories and on licenses
incompatible with Apache-2.0 redistribution. CodeQL runs on every pull request and on a
schedule.
