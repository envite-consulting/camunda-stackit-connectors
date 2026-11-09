# Contributing

Thanks for considering a contribution. Bug reports, connector ideas and pull requests are all
welcome.

**[`AGENTS.md`](AGENTS.md) is the technical guide** — build commands, architecture decisions,
element template conventions, testing rules and the checklist for adding a new connector. It
is written for both humans and coding agents. Read it before you change code. This file only
covers process.

## Before you start

For anything larger than a bug fix, **open an issue first**. A new connector in particular is
worth discussing up front: some STACKIT services do not expose an API that a connector can
usefully call, and we would rather tell you that before you write code than after. The design
documents under `connectors/archiving/` and `connectors/workflows/` are examples of exactly
that problem.

## Development setup

You need **JDK 21** and Docker (for integration tests). Maven comes from the committed wrapper
— always use `./mvnw`, never a system `mvn`.

```bash
git clone https://github.com/envite-consulting/camunda-stackit-connectors.git
cd camunda-stackit-connectors
./mvnw -B verify
```

A normal build formats your code and inserts license headers automatically, so you do not
need to run a formatter by hand.

## Pull requests

- Branch from `main`, named `<issue>-short-description`.
- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`,
  `style`, `refactor`, `test`, `chore`, `build`, `ci`, `deps`. Breaking changes as `feat!:`
  with a `BREAKING CHANGE:` footer.
- Explain **why** in the commit body. The diff already shows what changed.
- Include tests. A connector change without a test will be asked for one.
- If you changed anything that affects an element template, regenerate it and commit the
  result — CI diffs the working tree and will fail otherwise. If you added or removed
  properties, bump the template `version` and commit the archived snapshot too.

Run this before pushing:

```bash
./mvnw -B -DskipTests process-classes && git status --short   # must be clean
./mvnw -B -P'!autoFormat' -PcheckFormat validate
./mvnw -B verify
npm --prefix tools run validate:templates
```

## Reporting bugs

Use the issue template — it asks for the things we always end up asking for anyway: the
connector and version, your Camunda version and deployment type (SaaS hybrid, Self-Managed,
or Camunda 8 Run), the STACKIT region, and the full incident message.

Please redact credentials, access keys and STACKIT project identifiers from anything you
paste.

For security vulnerabilities, follow [`SECURITY.md`](SECURITY.md) instead — do not open a
public issue.

## Licensing

Contributions are accepted under the [Apache License 2.0](LICENSE). By opening a pull request
you confirm you have the right to contribute the code under that license.

Note that `LICENSE` also carries the Camunda Marketplace third-party connector notice ahead
of the Apache terms. That notice is required for the Marketplace listing — please do not
remove or reword it.

## Code of conduct

Be decent to each other. Assume good faith, critique the code rather than the person, and
accept that maintainers may say no to a change without that being a judgement of you. Conduct
concerns can go to **conduct@envite.de**.
