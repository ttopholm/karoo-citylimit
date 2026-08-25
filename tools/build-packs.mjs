/*
 * Builds downloadable region packs for the extension.
 *
 * A pack is the same sign data the extension would otherwise fetch cell by cell from Overpass while
 * riding, prepared once and published as static files. Downloading one leaves the device with a
 * whole country cached, so no connection is needed on the road, and Overpass is queried once per
 * release of the packs rather than once per rider per area.
 *
 * The signs are classified and tied to their towns with the same logic the extension uses - the copy
 * in tools/verify-map.html, which tools/verify-map-parity.mjs keeps in step with the Kotlin in core/.
 *
 *   node tools/build-packs.mjs [--region dk] [--out build/packs] [--bounds S,W,N,E] [--endpoint URL]
 *
 * --bounds narrows a region to a smaller area, which is handy for trying the pipeline out without
 * downloading a whole country. --endpoint sends the queries somewhere other than the public Overpass
 * instances, for instance the site's own /api/overpass, which is useful when the public ones have
 * put your address in the corner.
 */

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
vm.runInThisContext(
  fs.readFileSync(path.join(root, 'tools/verify-map.html'), 'utf8')
    .split('<script id="citylimit-logic">')[1]
    .split('</script>')[0],
);
const C = globalThis.CityLimit;

/**
 * Regions to build. `tiles` splits the area into Overpass queries small enough to answer reliably;
 * places are collected with a margin, so signs near a tile edge still find the town they name.
 */
const REGIONS = [
  {
    id: 'dk',
    name: 'Danmark',
    // The box reaches into Skåne and Schleswig, so signs are clipped to the country itself. Places
    // are not clipped: a sign near the border still has to find the town it names, wherever it is.
    bounds: { south: 54.50, west: 8.00, north: 57.80, east: 15.25 },
    area: '["ISO3166-1"="DK"][admin_level=2]',
    tile: { lat: 0.5, lng: 1.0 },
  },
];

const DEFAULT_ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
];

const USER_AGENT = 'karoo-citylimit-pack-builder/1.0 (+https://github.com/ttopholm/karoo-citylimit)';

/** Chunks stay under the 100 KB limit on requests made through the Karoo system. */
const MAX_CHUNK_BYTES = 80_000;

/** Matches Overpass.PLACE_SEARCH_MARGIN_METERS in core/. */
const PLACE_MARGIN_METERS = 5_000;

const REQUEST_SPACING_MS = 4_000;
const MAX_ATTEMPTS = 6;

/** The public Overpass instances queue requests; give up on one and try the next. */
const REQUEST_TIMEOUT_MS = 180_000;

const args = process.argv.slice(2);
const only = value(args, '--region');
const outDir = path.resolve(root, value(args, '--out') ?? 'build/packs');
const boundsOverride = value(args, '--bounds')?.split(',').map(Number);
const ENDPOINTS = value(args, '--endpoint')?.split(',') ?? DEFAULT_ENDPOINTS;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const host = (url) => new URL(url).host;

function value(list, flag) {
  const index = list.indexOf(flag);
  return index >= 0 ? list[index + 1] : undefined;
}

async function overpass(query) {
  let lastError = 'ingen forsøg';
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    for (const endpoint of ENDPOINTS) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
      const startedAt = Date.now();
      try {
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain; charset=utf-8', 'User-Agent': USER_AGENT },
          body: query,
          signal: controller.signal,
        });
        const text = await response.text();
        const seconds = ((Date.now() - startedAt) / 1000).toFixed(0);
        if (response.ok && text.trimStart().startsWith('{')) {
          process.stdout.write(`    ${host(endpoint)} svarede på ${seconds}s\n`);
          return JSON.parse(text);
        }
        lastError = `HTTP ${response.status} fra ${host(endpoint)} efter ${seconds}s`;
      } catch (error) {
        lastError = `${host(endpoint)}: ${error.name === 'AbortError' ? 'timeout' : error.message}`;
      } finally {
        clearTimeout(timer);
      }
      process.stdout.write(`    ${lastError}\n`);
      await sleep(REQUEST_SPACING_MS);
    }
    console.log(`    forsøg ${attempt}/${MAX_ATTEMPTS} mislykkedes (${lastError}), venter …`);
    await sleep(REQUEST_SPACING_MS * attempt);
  }
  throw new Error(`Overpass svarede ikke: ${lastError}`);
}

function tilesOf(region) {
  const tiles = [];
  for (let south = region.bounds.south; south < region.bounds.north; south += region.tile.lat) {
    for (let west = region.bounds.west; west < region.bounds.east; west += region.tile.lng) {
      tiles.push({
        south,
        west,
        north: Math.min(south + region.tile.lat, region.bounds.north),
        east: Math.min(west + region.tile.lng, region.bounds.east),
      });
    }
  }
  return tiles;
}

/**
 * Signs from within the region's own borders, places from a wider area around the tile so a sign
 * near the border can still be tied to the town it names.
 */
function queryFor(region, tile) {
  if (!region.area) return C.buildQuery(tile, 180);

  const format = (b) => [b.south, b.west, b.north, b.east].map((v) => v.toFixed(6)).join(',');
  const midLatitude = (tile.south + tile.north) / 2;
  const latDelta = PLACE_MARGIN_METERS / 111320;
  const lngDelta = PLACE_MARGIN_METERS / Math.max(1, 111320 * Math.cos((midLatitude * Math.PI) / 180));
  const placeBox = format({
    south: tile.south - latDelta,
    west: tile.west - lngDelta,
    north: tile.north + latDelta,
    east: tile.east + lngDelta,
  });
  const signBox = format(tile);
  return `[out:json][timeout:180];
area${region.area}->.region;
(
  node(area.region)(${signBox})[~"^traffic_sign(:(forward|backward|both))?$"~"${C.OVERPASS_VALUE_REGEX}",i];
  node(${placeBox})["place"~"${C.PLACE_REGEX}"];
  way(${placeBox})["place"~"${C.PLACE_REGEX}"];
  relation(${placeBox})["place"~"${C.PLACE_REGEX}"];
);
out center qt;`;
}

async function collect(region) {
  const elements = new Map();
  const tiles = tilesOf(region);
  console.log(`  ${tiles.length} felter at hente`);
  for (const [index, tile] of tiles.entries()) {
    const data = await overpass(queryFor(region, tile));
    let added = 0;
    for (const element of data.elements ?? []) {
      const key = `${element.type}/${element.id}`;
      if (!elements.has(key)) {
        elements.set(key, element);
        added++;
      }
    }
    console.log(`  felt ${index + 1}/${tiles.length}: ${data.elements?.length ?? 0} elementer (${added} nye)`);
    await sleep(REQUEST_SPACING_MS);
  }
  return [...elements.values()];
}

/** Group signs into the grid cells the extension caches in. */
function toCells(signs) {
  const cells = {};
  for (const sign of signs) {
    const id = C.cellIdFor(sign.position);
    (cells[id] ??= []).push({
      id: sign.id,
      position: { lat: round(sign.position.lat), lng: round(sign.position.lng) },
      ...(sign.name ? { name: sign.name } : {}),
      ...(sign.maxSpeed ? { maxSpeed: sign.maxSpeed } : {}),
      ...(sign.entryHeading == null ? {} : { entryHeading: round(sign.entryHeading, 1) }),
      ...(sign.townId == null ? {} : { townId: sign.townId }),
      ...(sign.genericBoundary ? { genericBoundary: true } : {}),
    });
  }
  return cells;
}

const round = (value, decimals = 7) => Number(value.toFixed(decimals));

/** Split the cells into files small enough to fetch through the Karoo system. */
function chunk(region, cells) {
  const chunks = [];
  let current = {};
  let size = 0;
  for (const [id, signs] of Object.entries(cells).sort(([a], [b]) => a.localeCompare(b))) {
    const entrySize = JSON.stringify({ [id]: signs }).length;
    if (size > 0 && size + entrySize > MAX_CHUNK_BYTES) {
      chunks.push(current);
      current = {};
      size = 0;
    }
    current[id] = signs;
    size += entrySize;
  }
  if (Object.keys(current).length > 0) chunks.push(current);
  return chunks.map((cells, index) => ({
    file: `${region.id}-${String(index).padStart(3, '0')}.json`,
    body: JSON.stringify({ region: region.id, chunk: index, cells }),
  }));
}

async function build(region, generatedAt) {
  console.log(`\n${region.name} (${region.id}):`);
  const elements = await collect(region);
  const { signs, dropped, places } = C.parseResponse({ elements });
  const withDirection = signs.filter((sign) => sign.entryHeading != null);
  const cells = toCells(withDirection);
  const files = chunk(region, cells);

  for (const file of files) {
    fs.writeFileSync(path.join(outDir, file.file), file.body);
  }
  const bytes = files.reduce((total, file) => total + file.body.length, 0);

  const index = {
    id: region.id,
    name: region.name,
    generatedAt,
    signs: withDirection.length,
    cells: Object.keys(cells).length,
    bytes,
    chunks: files.map((file) => file.file),
  };
  fs.writeFileSync(path.join(outDir, `${region.id}.json`), JSON.stringify(index, null, 1));

  console.log(
    `  ${withDirection.length} skilte i ${index.cells} celler, ${files.length} filer, ` +
    `${(bytes / 1024).toFixed(0)} kB ` +
    `(${signs.length - withDirection.length} uden retning og ${dropped.length} kun med streg over udeladt, ` +
    `${places.length} byer brugt)`,
  );
  return index;
}

fs.mkdirSync(outDir, { recursive: true });
const generatedAt = new Date().toISOString();
const regions = REGIONS
  .filter((region) => !only || region.id === only)
  .map((region) => (boundsOverride
    ? { ...region, bounds: { south: boundsOverride[0], west: boundsOverride[1], north: boundsOverride[2], east: boundsOverride[3] } }
    : region));
if (regions.length === 0) throw new Error(`Ukendt region: ${only}`);

const catalog = [];
for (const region of regions) {
  catalog.push(await build(region, generatedAt));
}
fs.writeFileSync(
  path.join(outDir, 'regions.json'),
  JSON.stringify({ generatedAt, regions: catalog.map(({ chunks, ...rest }) => rest) }, null, 1),
);
console.log(`\nSkrevet til ${outDir}`);
