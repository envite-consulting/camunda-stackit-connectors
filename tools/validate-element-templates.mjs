#!/usr/bin/env node
/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Validates every committed Camunda 8 element template against the official
 * Zeebe element-template JSON schema.
 *
 * Element templates in this repository are *generated build output*: the
 * element-template-generator-maven-plugin writes them into
 * `<module>/element-templates/*.json` during the `process-classes` phase.
 * The generator does not itself validate against the published JSON schema,
 * so a bad annotation can produce a template that Modeler silently rejects.
 * This script closes that gap.
 *
 * IMPORTANT: `validate()` from @bpmn-io/element-templates-validator is the
 * *Camunda 7* validator and reports false errors on Camunda 8 templates
 * (`engines`, `zeebe:*` bindings, ...). Always use `validateZeebe()`.
 *
 * Usage:
 *   node validate-element-templates.mjs              # scan the repository root
 *   node validate-element-templates.mjs --root DIR   # scan DIR instead
 *   node validate-element-templates.mjs FILE...      # validate explicit files
 *
 * Exit codes:
 *   0  all discovered templates are valid
 *   1  at least one template is invalid, or no templates were discovered
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import {
  validateZeebe,
  getZeebeSchemaVersion,
} from '@bpmn-io/element-templates-validator';

const TOOLS_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_ROOT = path.resolve(TOOLS_DIR, '..');

/**
 * Covers both repository layouts:
 *   - `<module>/element-templates/**` (e.g. `core/`, `bundle/`)
 *   - `<aggregator>/<module>/element-templates/**` (e.g. `connectors/object-storage/`)
 * `hybrid/` and `versioned/` templates are included on purpose: a pinned
 * snapshot that no longer validates is just as broken as a fresh template.
 */
const GLOB_PATTERNS = [
  '*/element-templates/**/*.json',
  '*/*/element-templates/**/*.json',
];

const IGNORED_DIRS = new Set([
  'node_modules',
  'target',
  '.git',
  '.idea',
  '.mvn',
]);

function parseArgs(argv) {
  const files = [];
  let root = DEFAULT_ROOT;

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];

    if (arg === '--root') {
      const value = argv[++i];
      if (!value) {
        throw new Error('--root requires a directory argument');
      }
      root = path.resolve(value);
    } else if (arg.startsWith('--root=')) {
      root = path.resolve(arg.slice('--root='.length));
    } else if (arg === '--help' || arg === '-h') {
      return { help: true, root, files };
    } else if (arg.startsWith('-')) {
      throw new Error(`Unknown option: ${arg}`);
    } else {
      files.push(path.resolve(arg));
    }
  }

  return { help: false, root, files };
}

/** Recursive fallback for runtimes without `fs.globSync` (pre Node 22). */
function walkForTemplates(root) {
  const found = [];

  const visit = (dir, depth) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }

    for (const entry of entries) {
      const full = path.join(dir, entry.name);

      if (entry.isDirectory()) {
        if (IGNORED_DIRS.has(entry.name)) {
          continue;
        }
        // `element-templates` only ever appears at depth 1 or 2 below the root;
        // below such a directory we descend without limit (hybrid/, versioned/).
        if (entry.name === 'element-templates') {
          visit(full, Number.POSITIVE_INFINITY);
        } else if (depth > 0) {
          visit(full, depth - 1);
        }
      } else if (entry.isFile() && entry.name.endsWith('.json')) {
        // Only collect files that actually live under an element-templates dir.
        if (path.relative(root, full).split(path.sep).includes('element-templates')) {
          found.push(full);
        }
      }
    }
  };

  // depth 2 => `<module>/` and `<aggregator>/<module>/`
  visit(root, 2);
  return found;
}

function discoverTemplates(root) {
  if (typeof fs.globSync === 'function') {
    const matches = [];
    for (const pattern of GLOB_PATTERNS) {
      for (const match of fs.globSync(pattern, { cwd: root })) {
        matches.push(path.resolve(root, match));
      }
    }
    return matches;
  }

  return walkForTemplates(root);
}

function formatErrors(errors) {
  if (!Array.isArray(errors) || errors.length === 0) {
    return ['    (validator reported no error details)'];
  }

  return errors.map((error) => {
    const at = error.instancePath && error.instancePath.length > 0 ? error.instancePath : '/';
    const extra = error.params && Object.keys(error.params).length > 0
      ? ` (${Object.entries(error.params)
          .map(([key, value]) => `${key}=${JSON.stringify(value)}`)
          .join(', ')})`
      : '';
    return `    ${at}: ${error.message ?? 'invalid'}${extra}`;
  });
}

/**
 * A template file normally holds a single template object, but the schema also
 * permits an array of templates. Validate each entry so neither shape slips by.
 */
function validateFile(file) {
  let parsed;

  try {
    parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    return { ok: false, lines: [`    /: not valid JSON: ${error.message}`] };
  }

  const templates = Array.isArray(parsed) ? parsed : [parsed];

  if (templates.length === 0) {
    return { ok: false, lines: ['    /: file contains an empty template array'] };
  }

  const lines = [];
  let ok = true;

  templates.forEach((template, index) => {
    const result = validateZeebe(template);
    if (!result.valid) {
      ok = false;
      const prefix = Array.isArray(parsed) ? `  [${index}]` : '';
      if (prefix) {
        lines.push(`  ${prefix}`);
      }
      lines.push(...formatErrors(result.errors));
    }
  });

  return { ok, lines };
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`error: ${error.message}`);
    return 2;
  }

  if (args.help) {
    console.log(
      [
        'Usage: node validate-element-templates.mjs [--root DIR] [FILE...]',
        '',
        'Validates Camunda 8 element templates against the official Zeebe',
        'element-template JSON schema. With no FILE arguments, templates are',
        'discovered by globbing the following patterns below DIR (default: the',
        'repository root):',
        ...GLOB_PATTERNS.map((pattern) => `  ${pattern}`),
      ].join('\n'),
    );
    return 0;
  }

  const { root, files } = args;
  const explicit = files.length > 0;
  const templateFiles = (explicit ? files : discoverTemplates(root))
    .map((file) => path.resolve(file))
    .filter((file, index, all) => all.indexOf(file) === index)
    .sort();

  const relative = (file) => {
    const rel = path.relative(explicit ? process.cwd() : root, file);
    return rel === '' || rel.startsWith('..') ? file : rel;
  };

  console.log(
    `Validating Camunda 8 element templates (Zeebe schema ${getZeebeSchemaVersion()})`,
  );
  console.log(`Root: ${root}`);
  console.log('');

  if (templateFiles.length === 0) {
    console.error('✖ No element templates found.');
    console.error('');
    console.error(
      explicit
        ? '  No files were given to validate.'
        : [
            '  Expected at least one template matching:',
            ...GLOB_PATTERNS.map((pattern) => `    ${pattern}`),
            '',
            "  Templates are generated build output - run './mvnw -DskipTests process-classes'",
            '  and commit the result. A validator that silently passes is worse than none,',
            '  so this is a failure.',
          ].join('\n'),
    );
    return 1;
  }

  const failures = [];

  for (const file of templateFiles) {
    const { ok, lines } = validateFile(file);

    if (ok) {
      console.log(`✔ ${relative(file)}`);
    } else {
      console.log(`✖ ${relative(file)}`);
      failures.push({ file, lines });
    }
  }

  console.log('');

  if (failures.length === 0) {
    console.log(
      `Summary: ${templateFiles.length} template file(s) checked, all valid.`,
    );
    return 0;
  }

  console.log(
    `Summary: ${templateFiles.length} template file(s) checked, ${failures.length} invalid.`,
  );
  console.log('');

  for (const { file, lines } of failures) {
    console.log(`✖ ${relative(file)}`);
    for (const line of lines) {
      console.log(line);
    }
    console.log('');
  }

  console.log(
    "Element templates are generated: fix the @ElementTemplate/@TemplateProperty annotations,",
  );
  console.log(
    "then re-run './mvnw -DskipTests process-classes' and commit the regenerated templates.",
  );

  return 1;
}

process.exitCode = main();
