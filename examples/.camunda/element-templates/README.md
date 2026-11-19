# `.camunda/element-templates/`

Desktop Modeler loads element templates from a `.camunda/element-templates/` directory next to a
diagram or in any parent directory
([Configuring templates](https://docs.camunda.io/docs/components/modeler/desktop-modeler/element-templates/configuring-templates/)),
so this one serves every diagram under `examples/`.

It is empty on purpose. The templates are generated into `connectors/*/element-templates/` on every
`./mvnw process-classes`, and a committed copy here would silently drift. Link them instead (the
links are gitignored):

```bash
# from the repository root
for t in connectors/*/element-templates/*.json; do
  ln -sf "$(realpath --relative-to=examples/.camunda/element-templates "$t")" \
         examples/.camunda/element-templates/
done
```

Without symlinks, copy `connectors/object-storage/element-templates/*.json` here and refresh the
copies after each build; do not commit them. Do not use the `hybrid/` or `versioned/` variants.
