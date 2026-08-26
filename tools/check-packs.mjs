/*
 * Refuses to publish a region pack that has quietly lost signs.
 *
 * A build that fails is easy to see. A build that half-works is not: a truncated download, an
 * extract cut to the wrong boundary, a tile that answered with nothing. The result is a pack that
 * looks fine, installs fine, and is silent where a town used to be announced.
 *
 * So each new pack is held up against the one already published, and a drop of more than a few per
 * cent stops the run. Signs do disappear from OpenStreetMap, but not by the hundred in a month.
 *
 *   node tools/check-packs.mjs build/packs [--allow-drop 5]
 */

import fs from 'node:fs';
import path from 'node:path';

const RELEASE = 'https://github.com/ttopholm/karoo-citylimit/releases/download/packs';

const args = process.argv.slice(2);
const dir = args.find((arg) => !arg.startsWith('--')) ?? 'build/packs';
const allowedDrop = Number(value(args, '--allow-drop') ?? 5) / 100;

function value(list, flag) {
  const index = list.indexOf(flag);
  return index >= 0 ? list[index + 1] : undefined;
}

const built = JSON.parse(fs.readFileSync(path.join(dir, 'regions.json'), 'utf8'));
const published = await fetchPublished();

if (!published) {
  console.log('Ingen udgivet pakke at sammenligne med; alt godkendt.');
  for (const region of built.regions) console.log(`  ${region.name}: ${region.signs} skilte`);
  process.exit(0);
}

const before = new Map(published.regions.map((region) => [region.id, region]));
const complaints = [];

for (const region of built.regions) {
  const old = before.get(region.id);
  if (!old) {
    console.log(`  ${region.name}: ${region.signs} skilte (ny region)`);
    continue;
  }
  const change = region.signs - old.signs;
  const share = old.signs > 0 ? change / old.signs : 0;
  const line = `  ${region.name}: ${old.signs} → ${region.signs} skilte (${change >= 0 ? '+' : ''}${change}, ` +
    `${(share * 100).toFixed(1)} %)`;
  console.log(line);
  if (-share > allowedDrop) {
    complaints.push(`${region.name} har mistet ${-change} skilte, ${(-share * 100).toFixed(1)} % - ` +
      `mere end de ${(allowedDrop * 100).toFixed(0)} % der regnes for normal udvikling`);
  }
}

for (const [id, old] of before) {
  if (!built.regions.some((region) => region.id === id)) {
    console.log(`  ${old.name}: ikke bygget denne gang, den udgivne pakke bliver stående`);
  }
}

if (complaints.length > 0) {
  console.error('\nPakkerne udgives ikke:');
  for (const complaint of complaints) console.error(`  ${complaint}`);
  process.exit(1);
}

async function fetchPublished() {
  try {
    const response = await fetch(`${RELEASE}/regions.json`, { redirect: 'follow' });
    if (!response.ok) {
      console.log(`Den udgivne oversigt svarede ${response.status}; intet at sammenligne med.`);
      return null;
    }
    return await response.json();
  } catch (error) {
    console.log(`Kunne ikke hente den udgivne oversigt (${error.message}); intet at sammenligne med.`);
    return null;
  }
}
