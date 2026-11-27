# Camunda Marketplace submission template

Answer sheet for the Camunda Marketplace **Submit a Connector** form. Copy it per connector, fill
the [variables](#1-variables), then paste field by field. Everything that is the same for every
STACKIT connector is already filled in.

- **Form:** <https://jfk-1.tasklist.camunda.io/e0d3996c-b748-424b-9283-142ce53df946/new/SubmitConnector-V2>
  (the link on the [Marketplace guideline page](https://marketplace.camunda.com/pages/submitConnector)).
  The same form is served at `https://jfk-1.api.camunda.io/e0d3996c-b748-424b-9283-142ce53df946/tasklist/new/SubmitConnector-V2`.
  Its title reads "…marketplace DEV" on both hosts; that is Camunda's naming, not a test copy.
- **Verified against the live form on 2026-11-27** by rendering it in a browser and toggling every
  conditional field. Camunda can change the form without notice, so re-check before reusing this
  sheet after a few months. The form is a Tasklist public start form: its definition is behind
  authentication, so it can only be read by rendering the page.
- `*` after a label means the form marks the field required.
- `{{VARIABLE}}` is filled from the variables table. `<<TODO: …>>` is an artifact or fact that does
  not exist yet and has to be produced.
- **Scope** says whether a value is the same for every connector (**generic**) or has to be
  rewritten (**per connector**).

## 1. Variables

Fill this table first; the rest of the sheet refers to it.

| Variable | Meaning | Example: Object Storage |
| --- | --- | --- |
| `{{SERVICE_NAME}}` | The STACKIT service, as STACKIT's docs name it | `Object Storage` |
| `{{MODULE}}` | Directory below `connectors/` | `object-storage` |
| `{{RELEASE_TAG}}` | Git tag of the released version the listing points at | `v0.1.0` (not tagged yet; the pom is `0.1.0-SNAPSHOT`) |
| `{{TEMPLATE_FILE}}` | Generated template in `connectors/{{MODULE}}/element-templates/` (not `hybrid/`, not `versioned/`) | `stackit-object-storage-outbound-connector.json` |
| `{{SDK_VERSION}}` | `version.connectors` in the root `pom.xml` | `8.10.4` |
| `{{CAMUNDA_MINOR}}` | Minor from `camunda.engine.version.range` | `8.10` |
| `{{WHAT_IT_DOES}}` | One clause: what a process can do with it | `store, retrieve and protect files in STACKIT Object Storage` |
| `{{DATA_RESIDENCY}}` | Where the data lives | `your data stays in Germany (eu01) or Austria (eu02)` |
| `{{CAPABILITIES}}` | 3 to 5 sentences or a short list of what the operations cover | see the module `README.md` |

Derived URLs, used below:

```text
{{TEMPLATE_URL}} = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/connectors/{{MODULE}}/element-templates/{{TEMPLATE_FILE}}
{{DOCS_URL}}     = https://github.com/envite-consulting/camunda-stackit-connectors/blob/{{RELEASE_TAG}}/connectors/{{MODULE}}/README.md
{{LOGO_URL}}         = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/docs/marketplace-assets/STACKIT_Logo_Bildmarke_Pos_Navy_RGB_Safespace.png
{{COMPANY_LOGO_URL}} = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/docs/marketplace-assets/envite_icon_aquamarine.png
```

Use a **release tag**, never `main`, in all three, so the listing never offers a template newer than
the released jar (see [Releasing](../AGENTS.md#releasing)). The two logo URLs have no per-connector
part: the logos are the same for every STACKIT connector.

## 2. The form

### 2.1 Your Information

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| Company / Contributor Name | | generic | `envite consulting GmbH` | Appears on your Marketplace profile |
| Email Contact | * | generic | `practice-green-bpm@envite.de` | Internal communication only |
| Link to Your Website | * | generic | `https://www.envite.de` | Where the company name on the listing links to |

### 2.2 Connector Details: Marketplace Profile

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| Title | * | per connector | `STACKIT {{SERVICE_NAME}} Connector` | The connector's name, e.g. "OpenAI Connector" |
| Splash Title | * | per connector | `Sovereign {{SERVICE_NAME}} for Camunda 8 Processes` | Value proposition in a few words, **Title Case** |
| Five-Word Description | * | per connector | `<<TODO: exactly five words>>`, e.g. `STACKIT Object Storage for Camunda` | Five words, **sentence case** |
| Connector Type | * | per connector | tick **Outbound Connector** (and/or **Inbound Connector**) | Select both if the connector supports both. Ticking reveals the template-URL fields in 2.5 |
| Application Category | | generic | `Cloud Computing Services` | Options: Cloud Computing Services, Automation Services, AI Services, External Data Services, Enterprise Applications, Collaboration Tools, Content Management, Data & Analytics, Protocol Connectors. Switch to `AI Services` or `Data & Analytics` where the STACKIT service is one |
| Industry | | generic | `Any` | Options: Any, Financial Services, Healthcare, Insurance, Manufacturing, Media and entertainment, Public Sector, Retail, Supply chain & logistics, Technology, Telecommunications |
| Use Case | | generic | `Microservices orchestration` | Only three options: Human Task orchestration, Microservices orchestration, RPA Bot orchestration. The middle one is the closest fit for an integration connector |
| Link to App Listing Logo | * | generic | `{{LOGO_URL}}` | Downloadable link, min **512 × 512 px**, **SVG is not supported** |
| Link to App Profile Logo | | generic | `{{COMPANY_LOGO_URL}}` (envite) | Downloadable link, min **230 × 230 px**, **SVG is not supported**. Assumed to be the company logo; see the note below |
| Link to Screenshots | * | per connector | `<<TODO: link to the screenshots>>` | Visuals of the connector in action, min **1,500 × 1,200 px**. One link field only; see [open questions](#3-open-questions-for-the-marketplace-team) |
| Link to Promo Video | | per connector | leave empty unless a video exists | Shown on the connector's primary page |

**Logo assets** live in [`marketplace-assets/`](marketplace-assets/), shared by every connector, and
must be PNG or JPG because the form rejects SVG:

| Field | Shows | File | Status |
| --- | --- | --- | --- |
| App Listing Logo | The STACKIT mark (the connector's subject) | `STACKIT_Logo_Bildmarke_Pos_Navy_RGB_Safespace.png`, exported at 1024 × 1024 from the brand-kit SVG beside it | Present |
| App Profile Logo | The envite logo | `envite_icon_aquamarine.png`, 961 × 951, transparent | Present. It is not exactly square and has no margin, so check how the profile crops it |

What the Marketplace does with the profile logo is not documented. A listing page (checked on the
Camunda RPA Connector, 2026-11-27) shows only the app logo and the creator's name as text, so the
profile logo's placement is unconfirmed; treat "company logo" as an assumption until the first
listing is live.

Commit every logo **before** the release tag is cut, or the URLs do not resolve.

**Why this variant.** The Marketplace shows the app logo on a **white** page (checked on the listing
above), so the listing logo has to be a *positive* mark. From the
[STACKIT partner brand kit](https://brand.schwarz-digits.de/d/Rg3fyb47H1jn/stackit-logo) that is
`Bildmarke_Pos_Navy_RGB_Safespace`: the square mark (the listing wants a square), navy `#0a1e2d`,
with safe space. Avoid the `…_White_…` and `…_Neg_…` files; they are for dark backgrounds and vanish
on white. The SVG is the portal's CDN rendition (coordinates rounded to three decimals; the outline matches the
Illustrator export of the white variant).

The `icon.svg` inside each module is the element template's icon. It is **not** a listing asset.

**Description** `*` (per connector). *Provide a detailed description of your Connector.*

```text
The STACKIT {{SERVICE_NAME}} Connector lets your Camunda 8 processes {{WHAT_IT_DOES}}.

STACKIT is the sovereign cloud of the Schwarz Group. The connector talks to STACKIT over HTTPS only, and {{DATA_RESIDENCY}}.

{{CAPABILITIES}}

The connector runs in your own Camunda Connector Runtime; Camunda does not host third-party connector code. You configure it with the supplied element template in Desktop Modeler or Web Modeler. Credentials are referenced from Camunda secrets, are never written to logs or incident messages, and are not stored outside your cluster.

Compatibility: Camunda {{CAMUNDA_MINOR}} (Connector SDK {{SDK_VERSION}}), Java 21.

The connector is open source under the Apache License 2.0 and maintained by envite consulting GmbH, a Camunda partner. Camunda provides no support for it; please use the GitHub issue tracker.
```

**SEO Description** `*` (per connector). *We recommend two sentences, or approximately 250 characters.*

```text
Connect Camunda 8 to STACKIT {{SERVICE_NAME}}: {{ONE_CLAUSE_BENEFIT}}. Open source (Apache 2.0), runs in your own Connector Runtime and keeps your data in the STACKIT sovereign cloud.
```

Example, 250 characters: *Connect Camunda 8 to STACKIT Object Storage: upload, download, copy and protect files with Object
Lock straight from your BPMN processes. Open source (Apache 2.0), runs in your own Connector Runtime
and keeps your data in the STACKIT sovereign cloud.*

### 2.3 Your Listing

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| Listing Options | | generic | `Open Source (Github)` | Radio: `Open Source (Github)` or `Listing Only`. **`Listing Only` hides the GitHub repository and template-URL fields in 2.5**, so it cannot be used for a connector that Camunda should review from source |

**Feature Description.** Three features are required, six are allowed. Features 2 to 6 below hold for
every connector in this repository; re-read each against the new connector before submitting, because
a claim that is false for one connector is worse than a missing feature.

| # | Req. | Scope | Title | Description |
| --- | --- | --- | --- | --- |
| 1 | * | per connector | `{{FEATURE_1_TITLE}}` | `{{FEATURE_1_TEXT}}`: the capability that sets this connector apart, in two sentences |
| 2 | * | generic | `Sovereign cloud, data stays put` | `Talks only to STACKIT's regional HTTPS endpoints, so {{DATA_RESIDENCY}}. Built for teams that need European data residency without leaving BPMN.` |
| 3 | * | generic | `Secure credential handling` | `Credentials come from Camunda secrets, are redacted in every log line and incident message, and are never collected or stored outside your cluster.` |
| 4 | | generic | `Errors you can handle in BPMN` | `Failures map to stable error codes you can branch on with error events. Permanent errors such as bad credentials fail at once instead of being retried, so the incident appears immediately.` |
| 5 | | generic | `Ready-to-use element template` | `Configure the connector in Desktop Modeler or Web Modeler from a guided template; no custom code needed. Templates are versioned, so Modeler offers a clean upgrade path.` |
| 6 | | generic | `Open source, Apache 2.0` | `Source, tests and issue tracker are public on GitHub. Maintained by envite consulting GmbH, a Camunda partner.` |

Example for feature 1 (Object Storage): title `Verified uploads and Object Lock`; text `STACKIT checks a
SHA-256 of every upload before it stores anything, so what is stored is provably what the process
received. Object Lock retention and legal holds protect objects against deletion.`

### 2.4 Technical Information

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| Connector Runtime | * | generic | `Camunda Connectors Bundle` | Radio: `Camunda Connectors Bundle` or `Your own implementation`. Hint in the form: "Are you reusing Camunda's runtime (Protocol Connector)?". The connectors are `OutboundConnectorFunction`s loaded by Camunda's runtime, so: yes |
| Connector SDK Version | | per release | `{{SDK_VERSION}}` | Free text |
| Compatible Version | | per release | **leave empty** | Dropdown with only Camunda 8.6, 8.7, 8.8, 8.9 (as of 2026-11-27). Do **not** pick 8.9 for an 8.10 connector; state `{{CAMUNDA_MINOR}}` in the SDK-version field and the description instead |

### 2.5 GitHub Information

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| GitHub Repository URL | * | generic | `https://github.com/envite-consulting/camunda-stackit-connectors` | |
| Outbound Template URL | | per connector | `{{TEMPLATE_URL}}` | Raw GitHub link. Shown after ticking *Outbound Connector* |
| Start Inbound Template URL | | per connector | `{{TEMPLATE_URL}}` or empty | Raw GitHub link. Shown after ticking *Inbound Connector* |
| Intermediate Inbound Template Url | | per connector | `{{TEMPLATE_URL}}` or empty | Raw GitHub link. Shown after ticking *Inbound Connector* |
| Boundary Inbound Template Url | | per connector | `{{TEMPLATE_URL}}` or empty | Raw GitHub link. Shown after ticking *Inbound Connector* |

The template URLs are optional in the form, but they are what the Marketplace reads to build the
listing: fill every one that applies. Today all connectors here are outbound, so only the first
template field applies.

### 2.6 Support Links

| Field | Req. | Scope | Value | Form hint |
| --- | --- | --- | --- | --- |
| Documentation Link | * | per connector | `{{DOCS_URL}}` | "This link can be part of your repository" |
| Support Link | * | generic | `https://github.com/envite-consulting/camunda-stackit-connectors/issues` | Camunda suggests `https://forum.camunda.io` if you have nothing better |
| Video Link | | per connector | leave empty unless a video exists | Shown under the resource tab |
| Additional Resources | | per connector | leave empty | "A whitepaper or something similar (PDF file type necessary)" |

## 3. Open questions for the Marketplace team

Neither the form nor the guideline pages answer these. Ask before the first submission, then record
the answers here.

1. **One template field per type, two templates in one module.** Object Storage ships two connectors
   with two element templates (data plane and management), but the form has a single *Outbound
   Template URL*. Working assumption: **submit the form twice**, one listing per connector, each with
   its own title, template URL and the same repository. Confirm that two listings from one repository
   and one jar are accepted.
2. **Compatible Version stops at Camunda 8.9**, while the SDK and element templates here target 8.10.
3. **Link to Screenshots is a single URL.** Is a folder, an archive, or one image expected?
4. **What the App Profile Logo is** and where it is shown: the company logo, or a second app image?
5. **Review turnaround** and any technical acceptance checklist beyond the guideline pages.

## 4. Before you press submit

- [ ] The **Release** workflow ran and `v<version>` exists, with a published (not draft) GitHub Release.
- [ ] `{{TEMPLATE_URL}}`, `{{DOCS_URL}}`, `{{LOGO_URL}}` and `{{COMPANY_LOGO_URL}}` open without a login (`curl -I`).
- [x] The repository is public (verified 2026-11-27) and `LICENSE.md` still opens with Camunda's
  third-party notice **before** the Apache text.
- [ ] The module `README.md` follows `connectors/object-storage/README.md`.
- [ ] `npm --prefix tools run validate:templates` passes; live tests ran if the connector touches STACKIT.
- [ ] Every image URL is public and raster: listing logo ≥ 512 × 512, profile logo ≥ 230 × 230,
  screenshots ≥ 1,500 × 1,200, no SVG.
- [x] Permission to show the STACKIT name and logo in the listing images: envite holds the logo rights
  as a STACKIT partner (confirmed 2026-11-27).
- [x] The contact mailbox is monitored. The form says only that it is for internal communication.

This is the base listing. *Certified* connectors are a separate, partner-only process with extra
requirements (source in the Camunda Community Hub, open-source dependencies only, re-certification
every two Camunda releases); see the [certification guideline](https://marketplace.camunda.com/pages/certificationConnector).
