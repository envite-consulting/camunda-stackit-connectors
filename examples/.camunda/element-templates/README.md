# `.camunda/element-templates/`

Camunda **Desktop Modeler** discovers element templates from a `.camunda/element-templates/`
directory in, quoting
[Configuring templates](https://docs.camunda.io/docs/components/modeler/desktop-modeler/element-templates/configuring-templates/),
"the diagrams parent directory or any of their parent directories" - so a single directory high up
covers everything below it. This one therefore serves every `.bpmn` file under `examples/`.

It is intentionally empty apart from this file.

## Why there is no template committed here

`connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json` is
**generated** by `io.camunda.connector:element-template-generator-maven-plugin` from the
`@ElementTemplate` and `@TemplateProperty` annotations in the connector's Java sources, and it is
regenerated on every `./mvnw process-classes`. A second copy in this directory would be a snapshot
that drifts the moment a property changes - and because drift here is silent, you would find out
when a modeller configures a field the connector no longer reads.

One generated file, one source of truth. Link to it.

## Linking the template

From the repository root:

```bash
ln -sf ../../../connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json \
       examples/.camunda/element-templates/
```

The target is relative, so the link keeps working wherever the repository is checked out. The
symlink itself is ignored by Git - see the `.gitignore` entry in this directory.

As connectors are added, link theirs too:

```bash
# from the repository root - links every generated (non-hybrid, non-versioned) template
for t in connectors/*/element-templates/*.json; do
  ln -sf "$(realpath --relative-to=examples/.camunda/element-templates "$t")" \
         examples/.camunda/element-templates/
done
```

## If symlinks are not an option

On Windows without developer mode, or with an editor that does not follow links, copy instead:

```bash
cp connectors/object-storage/element-templates/*.json examples/.camunda/element-templates/
```

Then re-copy after every `./mvnw -DskipTests process-classes`, and do not commit the copy.

## Alternatives

* **Globally, for every diagram you open** - put the template in Modeler's user data directory
  (`~/.config/camunda-modeler/resources/element-templates/` on Linux; see `examples/README.md`
  for the other platforms).
* **Web Modeler** - it runs server-side and cannot see your filesystem. Upload the JSON into the
  Web Modeler project, or publish it to Camunda Hub.

## Which file to use

| File | Use |
| --- | --- |
| `element-templates/stackit-object-storage-outbound-connector.json` | Always. This is the one to link. |
| `element-templates/hybrid/...-hybrid.json` | Only if you deliberately want to pin a local job type. STACKIT connectors do **not** need the hybrid variant - their `de.envite:` job types are not known to any Camunda-managed runtime, so nothing will steal their jobs. |
| `element-templates/versioned/...` | Pinned historical snapshots. Never link these; Modeler resolves old versions through the `id` + `version` pair on its own. |
