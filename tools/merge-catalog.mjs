/*
 * Writes the catalogue the app reads, regions.json, from the region indexes at hand.
 *
 * Each country is built on its own runner, so a run only ever holds the indexes for the countries
 * it built. Writing the catalogue from those alone would drop every other country from the app's
 * list - the packs would still be on the release, but nothing would offer them. So the published
 * catalogue is read first, and a country that was not built this time keeps the entry it had.
 *
 *   node tools/merge-catalog.mjs build/packs
 */

import fs from 'node:fs';
import path from 'node:path';

const RELEASE = 'https://github.com/ttopholm/karoo-citylimit/releases/download/packs';

const dir = process.argv[2] ?? 'build/packs';

/** A region index is the file with a chunk list; the chunks themselves are named <id>-000.json. */
function builtRegions() {
  return fs.readdirSync(dir)
    .filter((name) => name.endsWith('.json') && name !== 'regions.json' && !/-\d{3}\.json$/.test(name))
    .map((name) => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')))
    .filter((index) => index.id && Array.isArray(index.chunks))
    .map(({ chunks, ...rest }) => rest);
}

async function publishedRegions() {
  try {
    const response = await fetch(`${RELEASE}/regions.json`, { redirect: 'follow' });
    if (!response.ok) {
      console.log(`Den udgivne oversigt svarede ${response.status}; starter forfra.`);
      return [];
    }
    return (await response.json()).regions ?? [];
  } catch (error) {
    console.log(`Kunne ikke hente den udgivne oversigt (${error.message}); starter forfra.`);
    return [];
  }
}

const built = builtRegions();
if (built.length === 0) throw new Error(`Ingen regionsindeks i ${dir}`);

const kept = (await publishedRegions()).filter((old) => !built.some((one) => one.id === old.id));
const regions = [...built, ...kept].sort((a, b) => a.id.localeCompare(b.id));

fs.writeFileSync(
  path.join(dir, 'regions.json'),
  JSON.stringify({ generatedAt: new Date().toISOString(), regions }, null, 1),
);

for (const region of regions) {
  const fresh = built.some((one) => one.id === region.id);
  console.log(`  ${region.id} ${region.name}: ${region.signs} skilte${fresh ? '' : ' (uændret, bygget tidligere)'}`);
}
console.log(`Skrevet ${path.join(dir, 'regions.json')} med ${regions.length} regioner`);
