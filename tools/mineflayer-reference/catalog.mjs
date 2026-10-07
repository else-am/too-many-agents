import ts from 'typescript';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';

const require = createRequire(import.meta.url);
const coverage = JSON.parse(await readFile(new URL('./coverage.json', import.meta.url), 'utf8'));
const pins = JSON.parse(await readFile(new URL('./upstream.json', import.meta.url), 'utf8'));
const declarations = new Map();
const documents = [];
const inputs = [];

for (const [name, pin] of [['mineflayer', pins.mineflayer], ['mineflayer-pathfinder', pins.pathfinder]]) {
  const root = dirname(require.resolve(name + '/package.json'));
  const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  if (manifest.version !== pin.version) throw new Error(`Expected ${name} ${pin.version}, found ${manifest.version}`);
  const path = join(root, 'index.d.ts');
  const source = await readFile(path, 'utf8');
  inputs.push({ name, version: pin.version, commit: pin.commit, declarationsSha256: createHash('sha256').update(source).digest('hex') });
  const file = ts.createSourceFile(path, source, ts.ScriptTarget.Latest, true);
  function add(key, node, kind) {
    const entry = declarations.get(key) ?? { key, kind, signatures: [], status: 'pending' };
    entry.signatures.push(node.getText(file));
    declarations.set(key, entry);
  }
  function visit(node, prefix) {
    if (ts.isModuleDeclaration(node)) {
      // String modules augment a package; named namespaces qualify its members.
      const next = ts.isStringLiteral(node.name) ? node.name.text : `${prefix}.${node.name.text}`;
      if (node.body) visit(node.body, next);
    } else if (ts.isInterfaceDeclaration(node) || ts.isClassDeclaration(node)) {
      const next = `${prefix}.${node.name.text}`;
      add(next, node, ts.isInterfaceDeclaration(node) ? 'interface' : 'class');
      for (const member of node.members) {
        if (member.modifiers?.some(modifier => modifier.kind === ts.SyntaxKind.PrivateKeyword || modifier.kind === ts.SyntaxKind.ProtectedKeyword)) continue;
        const key = ts.isConstructorDeclaration(member) ? 'constructor' : member.name?.getText(file).replace(/^['"]|['"]$/g, '');
        if (key) add(`${next}.${key}`, member, 'member');
      }
    } else if (ts.isFunctionDeclaration(node) && node.name) add(`${prefix}.${node.name.text}`, node, 'function');
    else if (ts.isTypeAliasDeclaration(node) || ts.isEnumDeclaration(node)) add(`${prefix}.${node.name.text}`, node, 'type');
    else ts.forEachChild(node, child => visit(child, prefix));
  }
  visit(file, name);
  const docPath = name === 'mineflayer' ? 'docs/api.md' : 'readme.md';
  let doc;
  try { doc = await readFile(join(root, docPath), 'utf8'); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    doc = await readFile(join(root, 'README.md'), 'utf8');
  }
  for (const match of doc.matchAll(/^#{2,6}\s+(.+)$/gm)) documents.push({ package: name, heading: match[1], status: 'pending-review' });
}
for (const [key, evidence] of Object.entries(coverage.entries)) {
  const entry = declarations.get(key);
  if (!entry) throw new Error(`Coverage entry is absent from the pinned declarations: ${key}`);
  if (!['supported', 'pending', 'inapplicable'].includes(evidence.status)) throw new Error(`Invalid status: ${key}`);
  if (evidence.status === 'supported' && (!evidence.evidence?.length || !evidence.scenarios?.length))
    throw new Error(`Supported API needs evidence and executable scenarios: ${key}`);
  if (evidence.status === 'inapplicable' && !evidence.reason) throw new Error(`Missing scope reason: ${key}`);
  Object.assign(entry, evidence);
}
const output = new URL('../../run/mineflayer-reference/', import.meta.url);
await mkdir(output, { recursive: true });
const counts = { supported: 0, pending: 0, inapplicable: 0 };
for (const entry of declarations.values()) counts[entry.status]++;
await writeFile(new URL('catalog.json', output), JSON.stringify({
  inputs, counts, declarations: [...declarations.values()], documentationReview: documents,
  note: 'Inventory and evidence index, not a conformance pass. Inherited/dependency APIs and documentation-only behavior require additional review.',
}, null, 2) + '\n');
console.log(JSON.stringify({ ...counts, documentationHeadingsToReview: documents.length, catalog: new URL('catalog.json', output).pathname }, null, 2));
