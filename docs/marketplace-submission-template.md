# Camunda Marketplace submission template

Answer sheet for the Camunda Marketplace **Submit a Connector** form. It holds the reusable template
and the filled-in values for the first two submissions, both from the `object-storage` module:

1. **STACKIT Object Storage** (data plane, access-key credentials)
2. **STACKIT Object Storage Management** (management API, service-account credentials)

The form takes one connector per submission, so it is submitted twice (see
[open question 1](#3-open-questions-for-the-marketplace-team)). For a new connector, copy the
*Template value* column, fill the [variables](#1-variables), then paste field by field. Everything
that is the same for every STACKIT connector is already filled in.

- **Form:** <https://jfk-1.tasklist.camunda.io/e0d3996c-b748-424b-9283-142ce53df946/new/SubmitConnector-V2>
  (the link on the [Marketplace guideline page](https://marketplace.camunda.com/pages/submitConnector)).
  The same form is served at `https://jfk-1.api.camunda.io/e0d3996c-b748-424b-9283-142ce53df946/tasklist/new/SubmitConnector-V2`.
  Its title reads "…marketplace DEV" on both hosts; that is Camunda's naming, not a test copy.
- **Verified against the live form on 2026-11-27** by rendering it in a browser and toggling every
  conditional field. Camunda can change the form without notice, so re-check before reusing this
  sheet after a few months. The form is a Tasklist public start form: its definition is behind
  authentication, so it can only be read by rendering the page.
- `*` after a label means the form marks the field required.
- **Template value** is the reusable value: a generic value, or a pattern with `{{VARIABLES}}` filled
  from the variables table. The two connector columns are the **final, ready-to-paste values**.
  `<<TODO: …>>` is an artifact or fact that does not exist yet and has to be produced.
- **Scope** says whether a value is the same for every connector (**generic**) or has to be
  rewritten (**per connector**). Generic values are repeated in both connector columns on purpose, so
  each column can be pasted without looking anywhere else.

## 1. Variables

Fill this table first; the rest of the sheet refers to it.

| Variable | Meaning | Object Storage | Object Storage Management |
| --- | --- | --- | --- |
| `{{SERVICE_NAME}}` | The STACKIT service, as STACKIT's docs name it, plus a qualifier when one service has several connectors | `Object Storage` | `Object Storage Management` |
| `{{MODULE}}` | Directory below `connectors/` | `object-storage` | `object-storage` |
| `{{RELEASE_TAG}}` | Git tag of the released version the listing points at | `v0.1.0` | `v0.1.0` |
| `{{TEMPLATE_FILE}}` | Generated template in `connectors/{{MODULE}}/element-templates/` (not `hybrid/`, not `versioned/`) | `stackit-object-storage-outbound-connector.json` | `stackit-object-storage-management-outbound-connector.json` |
| `{{DOCS_ANCHOR}}` | Section of the module README that covers this connector, if it shares one | *(empty)* | `#management-connector` |
| `{{SDK_VERSION}}` | `version.connectors` in the root `pom.xml` | `8.10.4` | `8.10.4` |
| `{{CAMUNDA_MINOR}}` | Minor from `camunda.engine.version.range` | `8.10` | `8.10` |
| `{{WHAT_IT_DOES}}` | One clause: what a process can do with it | `store, retrieve and protect files in STACKIT Object Storage` | `create and manage buckets, credentials groups, access keys and Object Lock settings in STACKIT Object Storage` |
| `{{DATA_RESIDENCY}}` | Where the data, or the resources the connector creates, live | `your data stays in the STACKIT region you choose` | `the buckets it creates live in the STACKIT region you choose` |
| `{{ENDPOINT_PHRASE}}` | What the connector talks to. **Differs by connector**: the management API is global, the S3 endpoints are regional | `STACKIT's regional HTTPS endpoints` | `the STACKIT management API over HTTPS` |
| `{{ONE_CLAUSE_BENEFIT}}` | One clause for the SEO description | `upload, download, copy and protect files with Object Lock straight from your BPMN processes` | `create buckets, credentials groups and access keys straight from your BPMN processes` |
| `{{SEO_TAIL}}` | Closing clause of the SEO description | `keeps your data in the STACKIT sovereign cloud` | `provisions resources in the STACKIT sovereign cloud` |
| `{{CAPABILITIES}}` | 3 to 5 sentences on what the operations cover | the *Description* block in 2.2 | the *Description* block in 2.2 |

`{{RELEASE_TAG}}` is `v0.1.0` as planned; **no tag exists yet** and the pom is still `0.1.0-SNAPSHOT`.

`{{DATA_RESIDENCY}}` deliberately names no country: the listing says "the STACKIT region you choose",
so it cannot go stale if STACKIT adds a region. Today every STACKIT region is in Germany (`eu01`) or
Austria (`eu02`) ([STACKIT: Regions and availability zones](https://docs.stackit.cloud/platform/regions/),
updated 2026-11-06), and the region dropdown offers exactly those two. That is a property of STACKIT,
not a restriction of the connectors: they work from any Camunda runtime that can reach the STACKIT
endpoints. If STACKIT adds a region, the dropdown needs a new entry; the listing copy does not.

Derived URLs, used below:

```text
{{TEMPLATE_URL}}     = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/connectors/{{MODULE}}/element-templates/{{TEMPLATE_FILE}}
{{DOCS_URL}}         = https://github.com/envite-consulting/camunda-stackit-connectors/blob/{{RELEASE_TAG}}/connectors/{{MODULE}}/README.md{{DOCS_ANCHOR}}
{{LOGO_URL}}         = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/docs/marketplace-assets/STACKIT_Logo_Bildmarke_Pos_Navy_RGB_Safespace.png
{{COMPANY_LOGO_URL}} = https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/{{RELEASE_TAG}}/docs/marketplace-assets/envite_icon_aquamarine.png
```

Use a **release tag**, never `main`, in all four, so the listing never offers a template newer than
the released jar (see [Releasing](../AGENTS.md#releasing)). The two logo URLs have no per-connector
part: the logos are the same for every STACKIT connector.

## 2. The form

### 2.1 Your Information

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| Company / Contributor Name | | generic | `envite consulting GmbH` | `envite consulting GmbH` | `envite consulting GmbH` | Appears on your Marketplace profile |
| Email Contact | * | generic | `practice-green-bpm@envite.de` | `practice-green-bpm@envite.de` | `practice-green-bpm@envite.de` | Internal communication only |
| Link to Your Website | * | generic | `https://www.envite.de` | `https://www.envite.de` | `https://www.envite.de` | Where the company name on the listing links to |

### 2.2 Connector Details: Marketplace Profile

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| Title | * | per connector | `STACKIT {{SERVICE_NAME}} Connector` | `STACKIT Object Storage Connector` | `STACKIT Object Storage Management Connector` | The connector's name, e.g. "OpenAI Connector" |
| Splash Title | * | per connector | `Sovereign {{SERVICE_NAME}} for Camunda 8 Processes` | `Sovereign Object Storage for Camunda 8 Processes` | `Sovereign Object Storage Management for Camunda 8 Processes` | Value proposition in a few words, **Title Case** |
| Five-Word Description | * | per connector | exactly five words, e.g. `STACKIT Object Storage for Camunda` | `STACKIT Object Storage for Camunda` | `Manage STACKIT Object Storage resources` | Five words, **sentence case** |
| Connector Type | * | per connector | tick **Outbound Connector** (and/or **Inbound Connector**) | **Outbound Connector** | **Outbound Connector** | Select both if the connector supports both. Ticking reveals the template-URL fields in 2.5 |
| Application Category | | generic | `Cloud Computing Services` | `Cloud Computing Services` | `Cloud Computing Services` | Options: Cloud Computing Services, Automation Services, AI Services, External Data Services, Enterprise Applications, Collaboration Tools, Content Management, Data & Analytics, Protocol Connectors. Switch to `AI Services` or `Data & Analytics` where the STACKIT service is one |
| Industry | | generic | `Any` | `Any` | `Any` | Options: Any, Financial Services, Healthcare, Insurance, Manufacturing, Media and entertainment, Public Sector, Retail, Supply chain & logistics, Technology, Telecommunications |
| Use Case | | generic | `Microservices orchestration` | `Microservices orchestration` | `Microservices orchestration` | Only three options: Human Task orchestration, Microservices orchestration, RPA Bot orchestration. The middle one is the closest fit for an integration connector |
| Link to App Listing Logo | * | generic | `{{LOGO_URL}}` | `https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/v0.1.0/docs/marketplace-assets/STACKIT_Logo_Bildmarke_Pos_Navy_RGB_Safespace.png` | same as Object Storage | Downloadable link, min **512 × 512 px**, **SVG is not supported** |
| Link to App Profile Logo | | generic | `{{COMPANY_LOGO_URL}}` | `https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/v0.1.0/docs/marketplace-assets/envite_icon_aquamarine.png` | same as Object Storage | Downloadable link, min **230 × 230 px**, **SVG is not supported**. Assumed to be the company logo; see the note below |
| Link to Screenshots | * | per connector | `<<TODO: link to the screenshots>>` | `<<TODO: link to the Object Storage screenshots>>` | `<<TODO: link to the Object Storage Management screenshots>>` | Visuals of the connector in action, min **1,500 × 1,200 px**. One link field only; see [open questions](#3-open-questions-for-the-marketplace-team) |
| Link to Promo Video | | per connector | leave empty unless a video exists | leave empty | leave empty | Shown on the connector's primary page |

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

**Screenshots to produce** (min 1,500 × 1,200 px each; none exist yet):

| Connector | Suggested screenshots |
| --- | --- |
| Object Storage | 1. The *Upload object* template in Modeler's properties panel, with the Authentication, Configuration and Operation groups visible. 2. A process that uploads a document, reads its metadata and creates a presigned download URL. 3. Operate showing the upload result JSON with `checksumSha256` |
| Object Storage Management | 1. The *Create bucket* template in Modeler's properties panel with Object Lock switched on. 2. A provisioning process: create credentials group, create access key, store the secret. 3. Operate showing a result, or an error branch on `ALREADY_EXISTS` |

#### Description `*` (per connector)

*Provide a detailed description of your Connector.* The reusable template:

```text
The STACKIT {{SERVICE_NAME}} Connector lets your Camunda 8 processes {{WHAT_IT_DOES}}.

STACKIT is the sovereign cloud of the Schwarz Group. The connector talks to STACKIT over HTTPS only, and {{DATA_RESIDENCY}}.

{{CAPABILITIES}}

The connector runs in your own Camunda Connector Runtime; Camunda does not host third-party connector code. You configure it with the supplied element template in Desktop Modeler or Web Modeler. Credentials are referenced from Camunda secrets, are never written to logs or incident messages, and are not stored outside your cluster.

Compatibility: Camunda {{CAMUNDA_MINOR}} (Connector SDK {{SDK_VERSION}}), Java 21.

The connector is open source under the Apache License 2.0 and maintained by envite consulting GmbH, a Camunda partner. Camunda provides no support for it; please use the GitHub issue tracker.
```

**Object Storage**, ready to paste:

```text
The STACKIT Object Storage Connector lets your Camunda 8 processes store, retrieve and protect files in STACKIT Object Storage.

STACKIT is the sovereign cloud of the Schwarz Group. The connector talks to STACKIT over HTTPS only, and your data stays in the STACKIT region you choose.

Upload and download Camunda documents with verified integrity: STACKIT checks a SHA-256 of every upload before it stores anything, and documents over 5 GiB go up as a multipart upload that is verified part by part. Copy, delete, list and inspect objects, and create time-limited presigned download and upload URLs so that a browser or a third party can transfer a file without a STACKIT credential. Protect objects with Object Lock (WORM) retention, GOVERNANCE or COMPLIANCE, and with legal holds. Every failure maps to a stable error code such as NOT_FOUND or ACCESS_DENIED, so your process can branch on it. The connector authenticates with an access key of a STACKIT credentials group.

The connector runs in your own Camunda Connector Runtime; Camunda does not host third-party connector code. You configure it with the supplied element template in Desktop Modeler or Web Modeler. Credentials are referenced from Camunda secrets, are never written to logs or incident messages, and are not stored outside your cluster.

Compatibility: Camunda 8.10 (Connector SDK 8.10.4), Java 21.

The connector is open source under the Apache License 2.0 and maintained by envite consulting GmbH, a Camunda partner. Camunda provides no support for it; please use the GitHub issue tracker.
```

**Object Storage Management**, ready to paste:

```text
The STACKIT Object Storage Management Connector lets your Camunda 8 processes create and manage buckets, credentials groups, access keys and Object Lock settings in STACKIT Object Storage.

STACKIT is the sovereign cloud of the Schwarz Group. The connector talks to STACKIT over HTTPS only, and the buckets it creates live in the STACKIT region you choose.

Create and delete buckets, credentials groups and access keys, for example a bucket per customer or an access key per tenant. A bucket can be created with Object Lock (WORM), and the connector manages the project compliance lock and a bucket's default retention. It pairs with the STACKIT Object Storage Connector, which then reads and writes objects with the access keys created here. The connector authenticates with a STACKIT service account key, which it exchanges for a short-lived token and caches across jobs. Every failure maps to a stable error code such as NOT_FOUND, ALREADY_EXISTS or ACCESS_DENIED, so your process can branch on it.

The connector runs in your own Camunda Connector Runtime; Camunda does not host third-party connector code. You configure it with the supplied element template in Desktop Modeler or Web Modeler. Credentials are referenced from Camunda secrets, are never written to logs or incident messages, and are not stored outside your cluster.

Compatibility: Camunda 8.10 (Connector SDK 8.10.4), Java 21.

The connector is open source under the Apache License 2.0 and maintained by envite consulting GmbH, a Camunda partner. Camunda provides no support for it; please use the GitHub issue tracker.
```

#### SEO Description `*` (per connector)

*We recommend two sentences, or approximately 250 characters.* The reusable template:

```text
Connect Camunda 8 to STACKIT {{SERVICE_NAME}}: {{ONE_CLAUSE_BENEFIT}}. Open source (Apache 2.0), runs in your own Connector Runtime and {{SEO_TAIL}}.
```

**Object Storage**, 250 characters:

```text
Connect Camunda 8 to STACKIT Object Storage: upload, download, copy and protect files with Object Lock straight from your BPMN processes. Open source (Apache 2.0), runs in your own Connector Runtime and keeps your data in the STACKIT sovereign cloud.
```

**Object Storage Management**, 259 characters:

```text
Connect Camunda 8 to STACKIT Object Storage Management: create buckets, credentials groups and access keys straight from your BPMN processes. Open source (Apache 2.0), runs in your own Connector Runtime and provisions resources in the STACKIT sovereign cloud.
```

### 2.3 Your Listing

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| Listing Options | | generic | `Open Source (Github)` | `Open Source (Github)` | `Open Source (Github)` | Radio: `Open Source (Github)` or `Listing Only`. **`Listing Only` hides the GitHub repository and template-URL fields in 2.5**, so it cannot be used for a connector that Camunda should review from source |

#### Feature Description

Three features are required, six are allowed. Re-read each generic feature against a new connector
before submitting, because a claim that is false for one connector is worse than a missing feature.
Feature 2 is the example: "regional endpoints" is true for the S3 data plane but **false for the
management connector**, which talks to one global API, so it is per connector.

The reusable template:

| # | Req. | Scope | Title | Description |
| --- | --- | --- | --- | --- |
| 1 | * | per connector | `{{FEATURE_1_TITLE}}` | `{{FEATURE_1_TEXT}}`: the capability that sets this connector apart, in two sentences |
| 2 | * | per connector | `Sovereign cloud, data stays put` | `Talks only to {{ENDPOINT_PHRASE}}; {{DATA_RESIDENCY}}. Built for teams that need European data residency without leaving BPMN.` |
| 3 | * | generic | `Secure credential handling` | `Credentials come from Camunda secrets, are redacted in every log line and incident message, and are never collected or stored outside your cluster.` |
| 4 | | generic | `Errors you can handle in BPMN` | `Failures map to stable error codes you can branch on with error events. Permanent errors such as bad credentials fail at once instead of being retried, so the incident appears immediately.` |
| 5 | | generic | `Ready-to-use element template` | `Configure the connector in Desktop Modeler or Web Modeler from a guided template; no custom code needed. Templates are versioned, so Modeler offers a clean upgrade path.` |
| 6 | | generic | `Open source, Apache 2.0` | `Source, tests and issue tracker are public on GitHub. Maintained by envite consulting GmbH, a Camunda partner.` |

**Object Storage**, ready to paste:

| # | Req. | Title | Description |
| --- | --- | --- | --- |
| 1 | * | `Verified uploads and Object Lock` | `STACKIT checks a SHA-256 of every upload before it stores anything; for Camunda documents it is the checksum the document store recorded, so what is stored is provably what the process received. Object Lock retention and legal holds protect objects against deletion.` |
| 2 | * | `Sovereign cloud, data stays put` | `Talks only to STACKIT's regional HTTPS endpoints; your data stays in the STACKIT region you choose. Built for teams that need European data residency without leaving BPMN.` |
| 3 | * | `Secure credential handling` | `Credentials come from Camunda secrets, are redacted in every log line and incident message, and are never collected or stored outside your cluster.` |
| 4 | | `Errors you can handle in BPMN` | `Failures map to stable error codes you can branch on with error events. Permanent errors such as bad credentials fail at once instead of being retried, so the incident appears immediately.` |
| 5 | | `Ready-to-use element template` | `Configure the connector in Desktop Modeler or Web Modeler from a guided template; no custom code needed. Templates are versioned, so Modeler offers a clean upgrade path.` |
| 6 | | `Open source, Apache 2.0` | `Source, tests and issue tracker are public on GitHub. Maintained by envite consulting GmbH, a Camunda partner.` |

**Object Storage Management**, ready to paste:

| # | Req. | Title | Description |
| --- | --- | --- | --- |
| 1 | * | `Provision storage from a process` | `Create and delete buckets, credentials groups and access keys from BPMN, for example a bucket per customer or an access key per tenant. Object Lock buckets, the project compliance lock and default retention are managed the same way.` |
| 2 | * | `Sovereign cloud, data stays put` | `Talks only to the STACKIT management API over HTTPS; the buckets it creates live in the STACKIT region you choose. Built for teams that need European data residency without leaving BPMN.` |
| 3 | * | `Secure credential handling` | `Credentials come from Camunda secrets, are redacted in every log line and incident message, and are never collected or stored outside your cluster.` |
| 4 | | `Errors you can handle in BPMN` | `Failures map to stable error codes you can branch on with error events. Permanent errors such as bad credentials fail at once instead of being retried, so the incident appears immediately.` |
| 5 | | `Ready-to-use element template` | `Configure the connector in Desktop Modeler or Web Modeler from a guided template; no custom code needed. Templates are versioned, so Modeler offers a clean upgrade path.` |
| 6 | | `Open source, Apache 2.0` | `Source, tests and issue tracker are public on GitHub. Maintained by envite consulting GmbH, a Camunda partner.` |

### 2.4 Technical Information

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| Connector Runtime | * | generic | `Camunda Connectors Bundle` | `Camunda Connectors Bundle` | `Camunda Connectors Bundle` | Radio: `Camunda Connectors Bundle` or `Your own implementation`. Hint in the form: "Are you reusing Camunda's runtime (Protocol Connector)?". The connectors are `OutboundConnectorFunction`s loaded by Camunda's runtime, so: yes |
| Connector SDK Version | | per release | `{{SDK_VERSION}}` | `8.10.4` | `8.10.4` | Free text |
| Compatible Version | | per release | **leave empty** | **leave empty** | **leave empty** | Dropdown with only Camunda 8.6, 8.7, 8.8, 8.9 (as of 2026-11-27). Do **not** pick 8.9 for an 8.10 connector; state `{{CAMUNDA_MINOR}}` in the SDK-version field and the description instead |

### 2.5 GitHub Information

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| GitHub Repository URL | * | generic | `https://github.com/envite-consulting/camunda-stackit-connectors` | `https://github.com/envite-consulting/camunda-stackit-connectors` | `https://github.com/envite-consulting/camunda-stackit-connectors` | |
| Outbound Template URL | | per connector | `{{TEMPLATE_URL}}` | `https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/v0.1.0/connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json` | `https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/v0.1.0/connectors/object-storage/element-templates/stackit-object-storage-management-outbound-connector.json` | Raw GitHub link. Shown after ticking *Outbound Connector* |
| Start Inbound Template URL | | per connector | `{{TEMPLATE_URL}}` or empty | not shown | not shown | Raw GitHub link. Shown after ticking *Inbound Connector* |
| Intermediate Inbound Template Url | | per connector | `{{TEMPLATE_URL}}` or empty | not shown | not shown | Raw GitHub link. Shown after ticking *Inbound Connector* |
| Boundary Inbound Template Url | | per connector | `{{TEMPLATE_URL}}` or empty | not shown | not shown | Raw GitHub link. Shown after ticking *Inbound Connector* |

The template URLs are optional in the form, but they are what the Marketplace reads to build the
listing: fill every one that applies. Both connectors here are outbound, so only the first template
field appears, and the inbound fields stay hidden because *Inbound Connector* is not ticked. The two
connectors differ only in this URL, and each listing must point at **its own** template.

### 2.6 Support Links

| Field | Req. | Scope | Template value | Object Storage | Object Storage Management | Form hint |
| --- | --- | --- | --- | --- | --- | --- |
| Documentation Link | * | per connector | `{{DOCS_URL}}` | `https://github.com/envite-consulting/camunda-stackit-connectors/blob/v0.1.0/connectors/object-storage/README.md` | `https://github.com/envite-consulting/camunda-stackit-connectors/blob/v0.1.0/connectors/object-storage/README.md#management-connector` | "This link can be part of your repository" |
| Support Link | * | generic | `https://github.com/envite-consulting/camunda-stackit-connectors/issues` | `https://github.com/envite-consulting/camunda-stackit-connectors/issues` | `https://github.com/envite-consulting/camunda-stackit-connectors/issues` | Camunda suggests `https://forum.camunda.io` if you have nothing better |
| Video Link | | per connector | leave empty unless a video exists | leave empty | leave empty | Shown under the resource tab |
| Additional Resources | | per connector | leave empty | leave empty | leave empty | "A whitepaper or something similar (PDF file type necessary)" |

## 3. Open questions for the Marketplace team

Neither the form nor the guideline pages answer these. Ask before the first submission, then record
the answers here.

1. **One template field per type, two templates in one module.** Object Storage ships two connectors
   with two element templates (data plane and management), but the form has a single *Outbound
   Template URL*. Working assumption, and the reason this sheet has two columns: **submit the form
   twice**, one listing per connector, each with its own title, template URL and the same repository.
   Confirm that two listings from one repository and one jar are accepted.
2. **Compatible Version stops at Camunda 8.9**, while the SDK and element templates here target 8.10.
3. **Link to Screenshots is a single URL.** Is a folder, an archive, or one image expected?
4. **What the App Profile Logo is** and where it is shown: the company logo, or a second app image?
5. **Review turnaround** and any technical acceptance checklist beyond the guideline pages.

## 4. Before you press submit

- [ ] The **Release** workflow ran and `v0.1.0` exists, with a published (not draft) GitHub Release.
- [ ] Every URL below opens without a login. Run it once the tag exists; each line must print `200`:

  ```bash
  B=https://raw.githubusercontent.com/envite-consulting/camunda-stackit-connectors/v0.1.0
  for u in \
    "$B/connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json" \
    "$B/connectors/object-storage/element-templates/stackit-object-storage-management-outbound-connector.json" \
    "$B/docs/marketplace-assets/STACKIT_Logo_Bildmarke_Pos_Navy_RGB_Safespace.png" \
    "$B/docs/marketplace-assets/envite_icon_aquamarine.png" \
    "https://github.com/envite-consulting/camunda-stackit-connectors/blob/v0.1.0/connectors/object-storage/README.md"; do
    curl -s -L -o /dev/null -w '%{http_code}  %{url_effective}\n' "$u"
  done
  ```

- [x] The repository is public (verified 2026-11-27) and `LICENSE.md` still opens with Camunda's
  third-party notice **before** the Apache text.
- [x] The module `README.md` follows `connectors/object-storage/README.md` (for these two connectors it is that file).
- [x] `npm --prefix tools run validate:templates` passes (checked 2026-11-27: all 5 template files valid).
- [ ] Live tests ran against the release candidate. The compatibility notes record runs with AWS SDK 2.59.0
  in both regions on 2026-11-16 and 2026-11-19; repeat them if anything touching S3 changed since.
- [ ] Every image URL is public and raster: listing logo ≥ 512 × 512, profile logo ≥ 230 × 230,
  screenshots ≥ 1,500 × 1,200, no SVG.
- [ ] Screenshots exist for **both** connectors (none yet).
- [x] Permission to show the STACKIT name and logo in the listing images: envite holds the logo rights
  as a STACKIT partner (confirmed 2026-11-27).
- [x] The contact mailbox is monitored. The form says only that it is for internal communication.

This is the base listing. *Certified* connectors are a separate, partner-only process with extra
requirements (source in the Camunda Community Hub, open-source dependencies only, re-certification
every two Camunda releases); see the [certification guideline](https://marketplace.camunda.com/pages/certificationConnector).
