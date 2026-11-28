import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const modules = join(root, 'backend', 'node_modules');
const args = process.argv.slice(2);
const targets = ['backend/{src,tests}/**/*.{ts,js,cjs,mjs}', 'backend/jest*.config.js', 'scripts/**/*.{js,cjs,mjs,ts}'];

// The course config lives at the root and refers to security-node rules by their bare names.
const lint = spawnSync(process.execPath, [
  join(modules, 'eslint/bin/eslint.js'),
  '--resolve-plugins-relative-to', modules,
  '--rulesdir', join(modules, 'eslint-plugin-security-node/lib/rules'),
  ...(args.length ? args : targets),
], { cwd: root, env: { ...process.env, NODE_PATH: modules }, stdio: 'inherit' });

if (lint.error) throw lint.error;
process.exitCode = lint.status ?? 1;
