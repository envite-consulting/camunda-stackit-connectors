# Running the STACKIT connectors

Camunda does not host third-party connector code, not even on SaaS, so the STACKIT connectors run in
a Connector Runtime you operate. There are three ways to get them into one:

| | What you deploy | Use it when |
| --- | --- | --- |
| **1. Mount into Camunda's bundle** | `stackit-connector-object-storage-<version>-with-dependencies.jar`, mounted into `camunda/connectors-bundle` | You already run `camunda/connectors-bundle` and want the STACKIT connectors next to Camunda's own. One runtime to operate |
| **2. Run this bundle** | An image built from `bundle/Dockerfile` | You want only the STACKIT connectors, isolated from Camunda's: separate scaling, restarts and logs. Also the simplest start with no runtime yet |
| **3. Camunda 8 Run** | The same jar, in `custom_connectors/` | Local development |

Never deploy the STACKIT connectors into two runtimes connected to the same cluster: both would
activate the same jobs and race for them.

This module, `stackit-connectors-bundle`, is option 2: a Spring Boot Connector Runtime that already
contains every STACKIT connector.

| Connector | Job type | Element template id |
| --- | --- | --- |
| STACKIT Object Storage | `de.envite:stackit-object-storage:1` | `de.envite.connectors.stackit.objectstorage.v1` |
| STACKIT Object Storage Management | `de.envite:stackit-object-storage-management:1` | `de.envite.connectors.stackit.objectstorage.management.v1` |
| STACKIT Workflows | `de.envite:stackit-workflows:1` | `de.envite.connectors.stackit.workflows.v1` |
| STACKIT Workflows DAG run (inbound) | `de.envite:stackit-workflows-dag-run:1` | `de.envite.connectors.stackit.workflows.dagrun.v1` |

**No container image is published.** Build it yourself as shown [below](#option-2-run-this-bundle).

## Option 1: mount the jar into `camunda/connectors-bundle`

Self-Managed:

```bash
docker run --rm --name stackit-connector \
  -v "$PWD/stackit-connector-object-storage-<version>-with-dependencies.jar:/opt/app/connector.jar" \
  -e CAMUNDA_CLIENT_MODE=self-managed \
  -e CAMUNDA_CLIENT_GRPCADDRESS=http://orchestration:26500 \
  -e CAMUNDA_CLIENT_RESTADDRESS=http://orchestration:8080 \
  -e SECRET_STACKIT_S3_ACCESS_KEY='<access key>' \
  -e SECRET_STACKIT_S3_SECRET_ACCESS_KEY='<secret access key>' \
  camunda/connectors-bundle:8.10.5
```

Camunda SaaS ([hybrid mode](#hybrid-mode-camunda-saas)): mount at `/opt/custom/connector.jar` and
add `LOADER_PATH=/opt/custom`; the cluster settings are those of the hybrid example below. Both
paths come from Camunda's
[Host custom connectors](https://docs.camunda.io/docs/components/connectors/custom-built-connectors/host-custom-connectors/);
the variable names are the 8.8+ ones from the
[properties reference](https://docs.camunda.io/docs/apis-tools/camunda-spring-boot-starter/properties-reference/)
(that page's own examples still show pre-8.8 names).

The Object Storage jar (about 11 MB) contains both Object Storage connectors, the AWS SDK and its
Apache HTTP client. The Workflows connector is a separate jar,
`stackit-connector-workflows-<version>-with-dependencies.jar` (about 140 KB, only this repository's
classes); mount it next to the first under a different file name in `/opt/app/`. Both leave out what
the runtime already provides (Connector SDK, Jackson, SLF4J) and Netty, including netty-tcnative's
native libraries, so they cannot shadow the runtime's own copies.

## Option 2: run this bundle

The build context is the **repository root**, because the bundle needs its sibling modules:

```bash
docker build -f bundle/Dockerfile -t envite/stackit-connectors:dev .   # Docker 23+ (BuildKit)

docker run --rm -p 8086:8080 \
  -e CAMUNDA_CLIENT_MODE=self-managed \
  -e CAMUNDA_CLIENT_GRPCADDRESS=http://host.docker.internal:26500 \
  -e CAMUNDA_CLIENT_RESTADDRESS=http://host.docker.internal:8080 \
  -e SECRET_STACKIT_S3_ACCESS_KEY='<access key>' \
  -e SECRET_STACKIT_S3_SECRET_ACCESS_KEY='<secret access key>' \
  envite/stackit-connectors:dev
```

Or without Docker, with Java 21:

```bash
./mvnw -B -DskipTests -pl bundle -am package
java -jar bundle/target/stackit-connectors-bundle-*-with-dependencies.jar
```

The image runs as UID/GID 1001, exposes 8080 and has a `HEALTHCHECK` on
`/actuator/health/readiness`. Arguments after the image name go to Spring Boot
(`--server.port=9090`). The defaults in [`application.yaml`](src/main/resources/application.yaml)
point at a Self-Managed cluster on `localhost`. [`examples/`](../examples) wires everything together
with Docker Compose.

To add a connector, add its module as a dependency in `bundle/pom.xml` and rebuild. This image does
not load jars from a drop-in directory; use option 1 for that.

## Option 3: Camunda 8 Run

Drop the jar into `custom_connectors/` in the Camunda 8 Run directory and restart it. Secrets go into
`connector-secrets.txt` there, one `NAME=VALUE` per line. Not verified against a running installation;
check the [Camunda 8 Run documentation](https://docs.camunda.io/docs/self-managed/quickstart/developer-quickstart/c8run/)
for your version.

## Secrets

A field written as `{{secrets.STACKIT_S3_ACCESS_KEY}}` is resolved by the **connector runtime** from
the environment variable `SECRET_STACKIT_S3_ACCESS_KEY` (the environment secret provider prefixes
`SECRET_`). In Kubernetes, use a `Secret` consumed with `envFrom`.

`=camunda.secrets.STACKIT_S3_ACCESS_KEY` in a FEEL input is the newer, recommended form. The
**cluster** resolves it before the job is activated, from a secret store configured on the cluster,
so the runtime never sees it in its environment. Names with `-` need backticks:
``=camunda.secrets.`my-key` ``.

- **A missing secret is not a startup error.** The first job that uses it fails with *"Secret with
  name '…' is not available"*. Check that `SECRET_<NAME>` is set in the container.
- **Never set `CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX` to empty.** That makes every
  environment variable a readable secret.

Sources: [Connectors configuration](https://docs.camunda.io/docs/self-managed/components/connectors/connectors-configuration/),
[Migrate secrets](https://docs.camunda.io/docs/components/connectors/use-connectors/migrate-secrets/).

## Hybrid mode: Camunda SaaS

A runtime you operate, attached to a SaaS cluster, is the only way a third-party connector reaches
Camunda SaaS. You need an API client with the **Orchestration Cluster REST API** scope (plus
**Secrets** to read Console-managed secrets), and the cluster id and region from Console
([Use connectors in hybrid mode](https://docs.camunda.io/docs/components/connectors/use-connectors-in-hybrid-mode/)).

```bash
docker run --rm --name stackit-connectors-hybrid \
  -e CAMUNDA_CLIENT_MODE=saas \
  -e CAMUNDA_CLIENT_CLOUD_CLUSTERID='<cluster id>' \
  -e CAMUNDA_CLIENT_CLOUD_REGION='<region>' \
  -e CAMUNDA_CLIENT_AUTH_CLIENTID='<client id>' \
  -e CAMUNDA_CLIENT_AUTH_CLIENTSECRET='<client secret>' \
  -e SECRET_STACKIT_S3_ACCESS_KEY='<access key>' \
  -e SECRET_STACKIT_S3_SECRET_ACCESS_KEY='<secret access key>' \
  envite/stackit-connectors:dev
```

In `saas` mode the gRPC and REST addresses are derived from cluster id and region; leave them unset.
For Console-managed secrets add `CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED=true`.

- **Never use `camunda/connectors-bundle-saas`**; it is Camunda-internal. For option 1 the image is
  `camunda/connectors-bundle`.
- **Leave `CAMUNDA_CONNECTOR_RUNTIME_SAAS` unset.** Connectors check its presence, not its value, so
  even `false` switches on SaaS-only validation (errors mentioning `defaultCredentialsChainUsedInSaaS`
  or `camunda.connector.auth.issuer`).
- **Ignore guides that set `CAMUNDA_OPERATE_CLIENT_URL`, `CAMUNDA_IDENTITY_*` or
  `ZEEBE_AUTHORIZATION_SERVER_URL`.** They predate Camunda 8.8.
- **Use the element templates unchanged.** Camunda's own connectors need a `CONNECTOR_<NAME>_TYPE`
  override in hybrid mode so a Camunda-managed runtime does not steal their jobs; no Camunda runtime
  knows the `de.envite:` job types, so the STACKIT connectors do not.

## Configuration reference

Every setting is a Spring Boot property with an environment-variable form: uppercase, drop the
dashes, dots to underscores (`camunda.client.grpc-address` → `CAMUNDA_CLIENT_GRPCADDRESS`).

| Environment variable | Meaning | Default |
| --- | --- | --- |
| `CAMUNDA_CLIENT_MODE` | `self-managed` or `saas` | `self-managed` |
| `CAMUNDA_CLIENT_GRPCADDRESS` | Gateway gRPC endpoint, with scheme | `http://localhost:26500` |
| `CAMUNDA_CLIENT_RESTADDRESS` | Orchestration Cluster REST endpoint (topology, document store) | `http://localhost:8080` |
| `CAMUNDA_CLIENT_AUTH_METHOD` | `none`, `basic` or `oidc` | `none` |
| `CAMUNDA_CLIENT_AUTH_USERNAME`, `..._PASSWORD` | Basic authentication | unset |
| `CAMUNDA_CLIENT_AUTH_CLIENTID`, `..._CLIENTSECRET`, `..._TOKENURL`, `..._AUDIENCE` | OIDC client credentials (token URL and audience are automatic in `saas` mode) | unset |
| `CAMUNDA_CLIENT_CLOUD_CLUSTERID`, `CAMUNDA_CLIENT_CLOUD_REGION` | SaaS cluster, required in `saas` mode | unset |
| `CAMUNDA_CLIENT_TENANTID`, `CAMUNDA_CLIENT_WORKER_DEFAULTS_TENANTIDS` | Tenant for cluster operations, tenants whose jobs are activated | `<default>` |
| `CAMUNDA_CLIENT_WORKER_DEFAULTS_MAXJOBSACTIVE` | In-flight jobs per connector worker | `100` (starter default: `32`) |
| `CAMUNDA_CLIENT_WORKER_DEFAULTS_STREAMENABLED` | Job streaming instead of polling only | `true` (starter default: `false`) |
| `CAMUNDA_CLIENT_WORKER_DEFAULTS_TIMEOUT` | How long an activated job stays locked | `PT5M` |
| `CONNECTOR_OUTBOUND_DISABLED` | Comma-separated connector types not to register | unset |
| `CAMUNDA_CONNECTOR_SECRETPROVIDER_ENVIRONMENT_PREFIX` | Prefix for environment secrets | `SECRET_` |
| `CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED` | Read Console-managed secrets (hybrid mode) | `false` |
| `CAMUNDA_CONNECTOR_POLLING_ENABLED` | Process-definition polling, needed by inbound connectors such as the STACKIT Workflows DAG run event. Set `false` only if no process uses an inbound connector | `true` |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | Served actuator endpoints | `health,metrics,loggers` |
| `LOGGING_LEVEL_DE_ENVITE_CAMUNDA_STACKIT` | Connector log level | `INFO` |
| `JAVA_TOOL_OPTIONS` | Extra JVM options, e.g. a trust store for a cluster behind a private CA: `-Djavax.net.ssl.trustStore=/certs/truststore.jks -Djavax.net.ssl.trustStorePassword=…`. `JAVAX_NET_SSL_TRUSTSTORE` does **not** work on this image; only `camunda/connectors-bundle`'s start script translates it | unset |

In YAML, the secret-provider properties are spelled `camunda.connector.secretprovider.…` (no dash);
`camunda.connector.secret-provider.…`, as some Camunda pages write it, has no effect. The
environment-variable forms work either way.

## Monitoring

```bash
curl -s localhost:8086/actuator/health/readiness
curl -s localhost:8086/actuator/metrics | jq '.names | map(select(startswith("camunda")))'
```

`readiness` is `DOWN` until the cluster answers a topology request; alert on it. Also served:
`/actuator/health`, `/health/liveness`, `/health/startup`, `/metrics`, `/loggers`. **None is
authenticated**; do not expose the port outside the deployment. There is no `/actuator/prometheus`
unless you add `io.micrometer:micrometer-registry-prometheus` to `bundle/pom.xml` and expose
`prometheus`.

## Development

```bash
./mvnw -pl bundle -am -B verify
```

`StackitConnectorsApplicationTest` starts the Spring context without a cluster and asserts that both
connectors are in the runtime's registry.
