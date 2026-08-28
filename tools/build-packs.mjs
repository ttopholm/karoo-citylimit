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
 *   node tools/build-packs.mjs [--region dk] [--out build/packs] [--work build/dumps]
 *                              [--source dump|overpass] [--bounds S,W,N,E] [--endpoint URL]
 *   node tools/build-packs.mjs --list       # regionernes id'er, som workflowet fordeler dem på
 *
 * By default the data comes from the country's OpenStreetMap dump, read with osmium: one download
 * and a minute of filtering, which is what the wiki asks a scheduled build of a whole country to do.
 * --source overpass takes the older route, querying the public instances tile by tile, and is there
 * for the day a dump is unavailable. --bounds and --endpoint only apply to that route: --bounds
 * narrows a region to a smaller area, handy for trying the pipeline out, and --endpoint sends the
 * queries somewhere other than the public instances.
 */

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { collectFromDump } from './osm-dump.mjs';
import { fetchNorwegianSigns, ATTRIBUTION as NVDB_ATTRIBUTION } from './nvdb-signs.mjs';
import { fetchSwedishBoundaries, ATTRIBUTION as SE_ATTRIBUTION } from './nvdb-sweden.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
vm.runInThisContext(
  fs.readFileSync(path.join(root, 'tools/verify-map.html'), 'utf8')
    .split('<script id="citylimit-logic">')[1]
    .split('</script>')[0],
);
const C = globalThis.CityLimit;

/**
 * Regions to build, and the mirrors each country's extract is fetched from, in order of preference.
 *
 * Which countries are here is a question about the data, not about the map. A town-entry sign only
 * helps where somebody has mapped it, and that varies enormously: Germany has 130,000 of them and
 * Sweden 503, though one is next door and the other is a bridge away. These are the five best
 * covered countries a rider from here is likely to reach, counted in OpenStreetMap in August 2026:
 *
 *   Germany 129,870 · France 45,449 · Poland 16,089 · Netherlands 15,200 · Austria 15,155
 *
 * Next in line are Italy (12,281) and Czechia (8,305). Sweden (503) and Norway (87) are the obvious
 * neighbours and the worst served; a pack for either would be a nearly empty file.
 *
 * `bounds`, `area` and `tile` belong to the older route through Overpass, kept behind
 * --source overpass for the day a dump is unavailable. Only Denmark carries them: for a country the
 * size of Germany that route is not a fallback, it is a week of queries.
 */
const REGIONS = [
  {
    id: 'dk',
    name: 'Danmark',
    dump: [
      'https://download.geofabrik.de/europe/denmark-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/denmark.osm.pbf',
    ],
    // The box reaches into Skåne and Schleswig, so signs are clipped to the country itself. Places
    // are not clipped: a sign near the border still has to find the town it names, wherever it is.
    bounds: { south: 54.50, west: 8.00, north: 57.80, east: 15.25 },
    area: '["ISO3166-1"="DK"][admin_level=2]',
    tile: { lat: 0.5, lng: 1.0 },
  },
  {
    id: 'de',
    name: 'Tyskland',
    dump: [
      'https://download.geofabrik.de/europe/germany-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/germany.osm.pbf',
    ],
  },
  {
    id: 'nl',
    name: 'Holland',
    dump: [
      'https://download.geofabrik.de/europe/netherlands-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/netherlands.osm.pbf',
    ],
  },
  {
    id: 'at',
    name: 'Østrig',
    dump: [
      'https://download.geofabrik.de/europe/austria-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/austria.osm.pbf',
    ],
  },
  {
    id: 'pl',
    name: 'Polen',
    dump: [
      'https://download.geofabrik.de/europe/poland-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/poland.osm.pbf',
    ],
  },
  {
    // Norway has no town-entry sign to map - "Tettbygd strøk" was withdrawn from the sign catalogue
    // and the national road database holds one of them - so OpenStreetMap has 87 signs for the whole
    // country. The place-name signs are there instead, from Statens vegvesen, and the roads they
    // stand beside still come from the dump.
    id: 'no',
    name: 'Norge',
    dump: [
      'https://download.geofabrik.de/europe/norway-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/norway.osm.pbf',
    ],
    signs: fetchNorwegianSigns,
    credit: NVDB_ATTRIBUTION,
  },
  {
    // Sweden's signs are not in the map either - of the 492 town signs the extract carries, 164
    // stand more than a kilometre from any built-up area and are plain place-name signs, mapped
    // under the same tag. What is public instead is
    // the boundary itself: every municipality's built-up area, recorded against the road network in
    // NVDB, from which the crossings are worked out.
    id: 'se',
    name: 'Sverige',
    dump: [
      'https://download.geofabrik.de/europe/sweden-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/sweden.osm.pbf',
    ],
    signs: fetchSwedishBoundaries,
    credit: SE_ATTRIBUTION,
  },
  {
    id: 'fr',
    name: 'Frankrig',
    dump: [
      'https://download.geofabrik.de/europe/france-latest.osm.pbf',
      'https://download.openstreetmap.fr/extracts/europe/france.osm.pbf',
    ],
  },
];

/**
 * The free instances with global coverage listed on the OpenStreetMap wiki, in the order they are
 * asked. They all serve the same data and take no credentials. The rest of that list is either
 * behind an API key or holds one region only - and a region-only instance would answer a Danish
 * query with an empty result rather than an error, which is the one failure this build must not
 * have. overpass.kumi.systems is not a fourth instance: it is what private.coffee used to be
 * called, so asking both only asked the same busy server twice.
 *
 * https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances
 */
const DEFAULT_ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
];

const USER_AGENT = 'karoo-citylimit-pack-builder/1.0 (+https://github.com/ttopholm/karoo-citylimit)';

/** Chunks stay under the 100 KB limit on requests made through the Karoo system. */
const MAX_CHUNK_BYTES = 80_000;

/** Matches Overpass.PLACE_SEARCH_MARGIN_METERS in core/. */
const PLACE_MARGIN_METERS = 5_000;

const REQUEST_SPACING_MS = 4_000;
const MAX_ATTEMPTS = 12;

/**
 * Overpass hands out a couple of query slots per address, and GitHub runners share addresses with
 * whoever else is querying from them, so a rate limit can arrive through no fault of this build.
 * Leaving a gap between two queries to the same instance keeps us from being the cause.
 */
const SAME_HOST_GAP_MS = 12_000;

/** Longest wait honoured when an instance says when its next slot frees. */
const MAX_SLOT_WAIT_MS = 300_000;

/**
 * The usage policy asks for a 30 second pause after a 429 or a 406 before the next request. When
 * the instance says how long its next slot really is, that wins - it is never shorter than this.
 */
const TOLD_OFF_WAIT_MS = 30_000;

/** However patient the retries are, one query does not get to hold up the build all day. */
const MAX_QUERY_MILLIS = 15 * 60_000;

/**
 * The public Overpass instances queue requests; give up on one and try the next. An instance that
 * is going to answer does so in a few seconds, so a long wait here only buys time from one that has
 * already given up on the query.
 */
const REQUEST_TIMEOUT_MS = 90_000;

/**
 * The public instances take turns being broken. In one build private.coffee and kumi.systems
 * answered 500 or 502 to very nearly every request, several of them taking two minutes to say so,
 * and the country took three hours instead of one. After a few failures in a row an instance is
 * passed over for a while, so a rate limit on the good one is waited out rather than spent asking
 * two that are down.
 */
const SICK_AFTER_FAILURES = 3;
const RESTED_AFTER_REQUESTS = 20;

/** An instance's own account of when it will take another query, from /api/status. */
async function slotWaitMillis(endpoint) {
  try {
    const response = await fetch(endpoint.replace('/api/interpreter', '/api/status'), {
      headers: { 'User-Agent': USER_AGENT },
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) return 0;
    const seconds = /in (\d+) seconds/.exec(await response.text())?.[1];
    return seconds ? Math.min(MAX_SLOT_WAIT_MS, (Number(seconds) + 2) * 1000) : 0;
  } catch {
    return 0;
  }
}

const args = process.argv.slice(2);
const only = value(args, '--region');
const outDir = path.resolve(root, value(args, '--out') ?? 'build/packs');
const boundsOverride = value(args, '--bounds')?.split(',').map(Number);
const ENDPOINTS = value(args, '--endpoint')?.split(',') ?? DEFAULT_ENDPOINTS;
const source = value(args, '--source') ?? 'dump';
const workDir = path.resolve(root, value(args, '--work') ?? 'build/dumps');
if (source !== 'dump' && source !== 'overpass') throw new Error(`Ukendt kilde: ${source}`);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const host = (url) => new URL(url).host;

function value(list, flag) {
  const index = list.indexOf(flag);
  return index >= 0 ? list[index + 1] : undefined;
}

const health = new Map(ENDPOINTS.map((endpoint) => [endpoint, { failures: 0, restedAt: 0, readyAt: 0 }]));
let requestCount = 0;

/**
 * The instances worth asking now: the ones not resting, or the least sick if they all are, soonest
 * free first. The order matters - an instance waiting out a rate limit must not hold up one that is
 * free this second.
 */
function endpointsToTry() {
  const awake = ENDPOINTS.filter((endpoint) => health.get(endpoint).restedAt <= requestCount);
  const worth = awake.length > 0
    ? awake
    : [...ENDPOINTS].sort((a, b) => health.get(a).failures - health.get(b).failures).slice(0, 1);
  return [...worth].sort((a, b) => health.get(a).readyAt - health.get(b).readyAt);
}

function noteSuccess(endpoint) {
  health.set(endpoint, { failures: 0, restedAt: 0, readyAt: Date.now() + SAME_HOST_GAP_MS });
}

function noteFailure(endpoint) {
  const state = health.get(endpoint);
  state.failures++;
  if (state.failures >= SICK_AFTER_FAILURES) {
    state.restedAt = requestCount + RESTED_AFTER_REQUESTS;
    state.failures = SICK_AFTER_FAILURES - 1;
    process.stdout.write(`    ${host(endpoint)} springes over indtil videre\n`);
  }
}

async function overpass(query) {
  let lastError = 'ingen forsøg';
  requestCount++;
  const giveUpAt = Date.now() + MAX_QUERY_MILLIS;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS && Date.now() < giveUpAt; attempt++) {
    for (const endpoint of endpointsToTry()) {
      const state = health.get(endpoint);
      const gap = state.readyAt - Date.now();
      if (gap > 0) await sleep(gap);
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
          noteSuccess(endpoint);
          return JSON.parse(text);
        }
        lastError = `HTTP ${response.status} fra ${host(endpoint)} efter ${seconds}s`;
        if (response.status === 429 || response.status === 406) {
          // The instance knows when it will take another query; asking sooner only spends its time.
          const wait = Math.max(TOLD_OFF_WAIT_MS, await slotWaitMillis(endpoint));
          state.readyAt = Date.now() + wait;
          lastError += `, spørger igen om ${Math.round(wait / 1000)}s`;
        }
      } catch (error) {
        lastError = `${host(endpoint)}: ${error.name === 'AbortError' ? 'timeout' : error.message}`;
      } finally {
        clearTimeout(timer);
      }
      noteFailure(endpoint);
      state.readyAt = Math.max(state.readyAt, Date.now() + SAME_HOST_GAP_MS);
      process.stdout.write(`    ${lastError}\n`);
      await sleep(REQUEST_SPACING_MS);
    }
    const pause = Math.min(120_000, 10_000 * attempt);
    console.log(`    forsøg ${attempt}/${MAX_ATTEMPTS} mislykkedes (${lastError}), venter ${pause / 1000}s …`);
    await sleep(pause);
  }
  throw new Error(`Overpass svarede ikke: ${lastError}`);
}

function tilesOf(region) {
  if (!region.bounds || !region.tile) {
    throw new Error(`${region.name} har ingen felter at dele op i; den bygges fra en dump (--source dump)`);
  }
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

/**
 * The roads the signs stand on, and the roads that meet their ends, in one query.
 *
 * The road a sign stands on is what tells a sign on the road you are riding from one on a side road
 * a few metres away. Its neighbours then say which side of the sign the town is on: a town sign is
 * where the 50 begins, and OpenStreetMap writes that limit on the roads themselves - in Denmark as
 * source:maxspeed=DK:urban and DK:rural - which is the question the town centre cannot answer when
 * the road runs across it.
 *
 * The sign roads come back with geometry and the neighbours with node lists only. Asking for both
 * in one query matters: three requests per tile instead of two was enough to tip the public
 * instances into rate limiting the whole build.
 */
function roadQueryFor(region, tile) {
  const box = [tile.south, tile.west, tile.north, tile.east].map((v) => v.toFixed(6)).join(',');
  const area = region.area ? `area${region.area}->.region;` : '';
  const signs = region.area
    ? `node(area.region)(${box})`
    : `node(${box})`;
  return `[out:json][timeout:180];
${area}
${signs}[~"^traffic_sign(:(forward|backward|both))?$"~"${C.OVERPASS_VALUE_REGEX}",i]->.s;
way(bn.s)["highway"]->.roads;
.roads out geom;
node(w.roads)->.ends;
way(bn.ends)["highway"];
out body;`;
}

async function collect(region) {
  const elements = new Map();
  const roads = new Map();
  const joins = new Map();
  const tiles = tilesOf(region);
  console.log(`  ${tiles.length} felter at hente`);

  async function fetchTile(tile, label) {
    const data = await overpass(queryFor(region, tile));
    let added = 0;
    for (const element of data.elements ?? []) {
      const key = `${element.type}/${element.id}`;
      if (!elements.has(key)) {
        elements.set(key, element);
        added++;
      }
    }
    await sleep(REQUEST_SPACING_MS);

    const roadData = await overpass(roadQueryFor(region, tile));
    const ways = (roadData.elements ?? []).filter((way) => way.type === 'way' && way.nodes?.length);
    const ends = new Set();
    for (const way of ways) {
      if (!way.geometry) continue;
      roads.set(way.id, way);
      ends.add(way.nodes[0]);
      ends.add(way.nodes[way.nodes.length - 1]);
    }
    let joined = 0;
    for (const way of ways) {
      const touching = way.nodes.filter((node) => ends.has(node));
      if (touching.length === 0) continue;
      // Only the nodes at the road ends are carried on; the full node lists would run to gigabytes
      // over a country. The sign roads come back twice, with geometry and without; either carries
      // the tags the speed zone is read from.
      const kept = joins.get(way.id);
      joins.set(way.id, {
        id: way.id,
        nodes: kept ? [...new Set([...kept.nodes, ...touching])] : touching,
        tags: way.tags ?? kept?.tags ?? {},
      });
      joined++;
    }
    console.log(`  felt ${label}: ${data.elements?.length ?? 0} elementer (${added} nye), ` +
      `${roads.size} veje i alt, ${joined} naboveje her`);
    await sleep(REQUEST_SPACING_MS);
  }

  // A tile that will not answer now often answers half an hour later, when whatever was wrong with
  // the instance has passed. Losing an hour of a country to one bad minute is not worth it, so the
  // failures are gathered and asked again at the end - and only then allowed to stop the build.
  const missed = [];
  for (const [index, tile] of tiles.entries()) {
    try {
      await fetchTile(tile, `${index + 1}/${tiles.length}`);
    } catch (error) {
      console.log(`  felt ${index + 1}/${tiles.length} sprang over: ${error.message}`);
      missed.push([index, tile]);
    }
  }
  if (missed.length > 0) {
    console.log(`  ${missed.length} felter gav op; prøver dem igen`);
    for (const [index, tile] of missed) {
      await fetchTile(tile, `${index + 1}/${tiles.length} (anden runde)`);
    }
  }
  return { elements: [...elements.values()], roads: [...roads.values()], joins: [...joins.values()] };
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
      ...(sign.roadBearing == null ? {} : { roadBearing: round(sign.roadBearing, 1) }),
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

/** The same test parseResponse makes: a node that is a town sign, entry or exit. */
function isTownSign(tags) {
  const sides = C.classify(tags);
  return sides.entry || sides.exit;
}

async function build(region, generatedAt) {
  console.log(`\n${region.name} (${region.id}):`);
  const { elements, roads, joins } = source === 'dump'
    ? await collectFromDump(region, workDir, { isSign: isTownSign, speedZone: C.speedZone })
    : await collect(region);
  const { signs, dropped, places } = C.parseResponse({ elements });
  C.attachRoadBearings(signs, roads);
  C.alignEntryHeadings(signs);
  const byCentre = new Map(signs.map((sign) => [sign.id, sign.entryHeading]));
  C.orientBySpeedZone(signs, roads, joins);
  const turned = signs.filter((sign) => sign.entryHeading != null
    && C.bearingDifference(byCentre.get(sign.id), sign.entryHeading) > 1).length;
  const withDirection = signs.filter((sign) => sign.entryHeading != null);
  const withRoad = withDirection.filter((sign) => sign.roadBearing != null).length;
  const cells = toCells(withDirection);
  const files = chunk(region, cells);

  for (const file of files) {
    fs.writeFileSync(path.join(outDir, file.file), file.body);
  }
  const bytes = files.reduce((total, file) => total + file.body.length, 0);

  const index = {
    id: region.id,
    name: region.name,
    ...(region.credit ? { credit: region.credit } : {}),
    // What a sign's id is. A sign read from the map is a node of the map and can be looked up; a
    // sign a region brought with it is not - Norway's id is NVDB's object number and Sweden's is
    // worked out from the road node - and looking one of those up finds an unrelated node on the
    // other side of the world. The verification map asks before it makes a link of it.
    signIds: region.signs ? 'source' : 'osm',
    generatedAt,
    signs: withDirection.length,
    signsWithRoad: withRoad,
    cells: Object.keys(cells).length,
    bytes,
    chunks: files.map((file) => file.file),
  };
  fs.writeFileSync(path.join(outDir, `${region.id}.json`), JSON.stringify(index, null, 1));

  console.log(
    `  ${withDirection.length} skilte i ${index.cells} celler, ${files.length} filer, ` +
    `${(bytes / 1024).toFixed(0)} kB ` +
    `(${signs.length - withDirection.length} uden retning og ${dropped.length} kun med streg over udeladt, ` +
    `${places.length} byer brugt, ${withRoad} med kendt vejretning, ${turned} rettet efter fartzonen)`,
  );
  return index;
}

// The workflow builds one country per runner, and asks here which ones there are.
if (args.includes('--list')) {
  process.stdout.write(`${JSON.stringify(REGIONS.map((region) => region.id))}\n`);
  process.exit(0);
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
