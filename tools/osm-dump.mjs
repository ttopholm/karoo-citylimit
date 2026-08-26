/*
 * Reads a country's signs, roads and towns out of an OpenStreetMap dump.
 *
 * The pack builder used to ask Overpass for a country tile by tile. That is the wrong tool for the
 * job and the OpenStreetMap wiki says so: a scheduled build of a whole country is what the regional
 * dumps are for. Overpass is a donated service sized for small interactive queries, and asking it
 * for two hundred megabytes in an hour earned us rate limits, half-finished runs, and one country
 * published with tiles missing.
 *
 * A dump costs one download and about a minute of osmium. It also removes the tile seams: places
 * are no longer collected from a margin around each tile and hoped to be enough, because the whole
 * country is in hand at once, and signs are already clipped to the country by whoever cut the
 * extract.
 *
 * The output is shaped exactly like an Overpass answer, so everything downstream - classification,
 * town matching, road bearings, the speed zone - is the same code on the same shapes.
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream/promises';

/** The place values the extension looks for; matches PLACE_REGEX in the shared logic. */
const PLACE_VALUES = 'city,town,village,hamlet,suburb,borough,quarter';
const IS_PLACE = new Set(PLACE_VALUES.split(','));

/** Sign nodes carry traffic_sign, or the :forward/:backward/:both variants of it. */
const SIGN_KEYS = 'n/traffic_sign*';

const SIGN_KEY = /^traffic_sign(:(forward|backward|both))?$/;

/**
 * Reads the dump for a region and returns what collect() would have returned from Overpass.
 *
 * @param region a REGIONS entry, needing a `dump` URL
 * @param workDir where the dump and the files osmium writes are kept
 */
export async function collectFromDump(region, workDir, log = console.log) {
  fs.mkdirSync(workDir, { recursive: true });
  const file = (name) => path.join(workDir, `${region.id}-${name}`);

  const dump = file('dump.osm.pbf');
  await download(region.dump, dump, log);

  const roadsPbf = file('roads.osm.pbf');
  const roadsOpl = file('roads.opl');
  const placesPbf = file('places.osm.pbf');
  const placesJson = file('places.geojsonseq');

  await osmium(['tags-filter', '-o', roadsPbf, '--overwrite', dump, SIGN_KEYS, 'w/highway'], log);
  // Without the metadata each line is "n<id> T<tags> x<lon> y<lat>" or "w<id> T<tags> Nn<id>,…",
  // which is half the size and needs no parser worth the name.
  await osmium(['cat', '-f', 'opl,add_metadata=false', '-o', roadsOpl, '--overwrite', roadsPbf], log);
  await osmium(['tags-filter', '-o', placesPbf, '--overwrite', dump, `nwr/place=${PLACE_VALUES}`], log);
  // export assembles the multipolygons, so a town mapped as an area comes back as one shape rather
  // than a pile of member ways.
  await osmium(['export', '-f', 'geojsonseq', '--add-unique-id=type_id', '-o', placesJson, '--overwrite', placesPbf], log);

  const places = readPlaces(placesJson);
  log(`  ${places.length} byer`);

  // The dump is sorted: every node comes before every way, so one pass finds the sign nodes and,
  // still in the same pass, the roads they stand on.
  const signs = [];
  const signIds = new Set();
  const roads = new Map();
  await eachLine(roadsOpl, (line) => {
    if (line.charCodeAt(0) === 110 /* n */) {
      const node = readNode(line);
      if (!node || !Object.keys(node.tags).some((key) => SIGN_KEY.test(key))) return;
      // A handful of nodes come out of the extract twice; a sign announced twice is a sign
      // announced twice.
      if (signIds.has(node.id)) return;
      signs.push(node);
      signIds.add(node.id);
      return;
    }
    if (line.charCodeAt(0) !== 119 /* w */) return;
    const way = readWay(line);
    if (!way?.tags.highway) return;
    if (way.nodes.some((node) => signIds.has(node))) roads.set(way.id, way);
  });
  log(`  ${signs.length} skiltenoder på ${roads.size} veje`);

  // Now the roads are known, a second pass picks up the coordinates they are drawn from and the
  // roads that meet their ends - the neighbours the speed zone is read from.
  const wanted = new Set();
  const ends = new Set();
  for (const road of roads.values()) {
    for (const node of road.nodes) wanted.add(node);
    ends.add(road.nodes[0]);
    ends.add(road.nodes[road.nodes.length - 1]);
  }
  const coordinates = new Map();
  const joins = new Map();
  await eachLine(roadsOpl, (line) => {
    if (line.charCodeAt(0) === 110 /* n */) {
      const node = readNode(line, false);
      if (node && wanted.has(node.id)) coordinates.set(node.id, { lat: node.lat, lon: node.lon });
      return;
    }
    if (line.charCodeAt(0) !== 119 /* w */) return;
    const way = readWay(line);
    if (!way?.tags.highway) return;
    const touching = way.nodes.filter((node) => ends.has(node));
    // Only the nodes at the road ends are carried on; the full node lists would run to gigabytes.
    if (touching.length > 0) joins.set(way.id, { id: way.id, nodes: touching, tags: way.tags });
  });

  for (const road of roads.values()) {
    road.geometry = road.nodes.map((node) => coordinates.get(node)).filter(Boolean);
  }
  const drawn = [...roads.values()].filter((road) => road.geometry.length === road.nodes.length);
  if (drawn.length < roads.size) {
    log(`  ${roads.size - drawn.length} veje manglede knuder og er udeladt`);
  }
  log(`  ${joins.size} naboveje`);

  return { elements: [...signs, ...places], roads: drawn, joins: [...joins.values()] };
}

const DOWNLOAD_ROUNDS = 3;
const DOWNLOAD_TIMEOUT_MS = 20 * 60_000;

/*
 * The dump is large and the pack build is long, so a download that is already there is reused.
 *
 * A region names more than one mirror, and every one of them is tried before the round is given up
 * on. Geofabrik is the one to prefer - it is the canonical cut and the boundaries are tight - but a
 * build that stops because one host will not answer is a build that does not run: the first attempt
 * from a GitHub runner never got a connection to it at all.
 */
async function download(mirrors, target, log) {
  const urls = [mirrors].flat().filter(Boolean);
  if (urls.length === 0) throw new Error('Regionen har ingen dump-adresse');
  if (fs.existsSync(target) && fs.statSync(target).size > 0) {
    log(`  bruger ${path.basename(target)} der allerede er hentet (${megabytes(target)} MB)`);
    return;
  }

  let complaint = 'intet forsøg';
  for (let round = 1; round <= DOWNLOAD_ROUNDS; round++) {
    for (const url of urls) {
      log(`  henter ${url}`);
      try {
        await fetchTo(url, target, log);
        return;
      } catch (error) {
        complaint = `${new URL(url).host}: ${error.message}`;
        log(`    ${complaint}`);
      }
    }
    if (round < DOWNLOAD_ROUNDS) {
      log(`  runde ${round}/${DOWNLOAD_ROUNDS} mislykkedes, venter …`);
      await new Promise((resolve) => setTimeout(resolve, 15_000 * round));
    }
  }
  throw new Error(`Ingen af spejlene svarede: ${complaint}`);
}

async function fetchTo(url, target, log) {
  const partial = `${target}.part`;
  fs.rmSync(partial, { force: true });
  const response = await fetch(url, { redirect: 'follow', signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS) });
  if (!response.ok || !response.body) throw new Error(`svarede ${response.status}`);
  await pipeline(response.body, fs.createWriteStream(partial));

  // A truncated body arrives as a perfectly good file, and osmium would then read a country with a
  // piece missing. The length the server promised is the cheapest way to catch it.
  const promised = Number(response.headers.get('content-length'));
  const written = fs.statSync(partial).size;
  if (Number.isFinite(promised) && promised > 0 && written !== promised) {
    fs.rmSync(partial, { force: true });
    throw new Error(`fik ${written} af ${promised} byte`);
  }
  fs.renameSync(partial, target);
  log(`  hentet, ${megabytes(target)} MB`);
}

const megabytes = (file) => (fs.statSync(file).size / 1024 / 1024).toFixed(0);

function osmium(args, log) {
  log(`  osmium ${args[0]} …`);
  return new Promise((resolve, reject) => {
    const child = spawn('osmium', args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let complaint = '';
    child.stderr.on('data', (chunk) => { complaint += chunk; });
    child.on('error', (error) => reject(new Error(
      error.code === 'ENOENT' ? 'osmium blev ikke fundet (apt-get install osmium-tool)' : error.message,
    )));
    child.on('close', (code) => (code === 0
      ? resolve()
      : reject(new Error(`osmium ${args[0]} fejlede (${code}): ${complaint.trim().slice(0, 400)}`))));
  });
}

async function eachLine(file, onLine) {
  const reader = readline.createInterface({
    input: fs.createReadStream(file, { highWaterMark: 1 << 20 }),
    crlfDelay: Infinity,
  });
  for await (const line of reader) if (line.length > 0) onLine(line);
}

/*
 * OPL escapes anything awkward as %<hex>%, spaces included, so a line splits on spaces and a tag
 * list splits on commas without any further care.
 */
const unescape = (text) => (text.includes('%')
  ? text.replace(/%([0-9A-Fa-f]+)%/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
  : text);

function readTags(field) {
  const tags = {};
  if (!field || field.length < 2) return tags;
  for (const pair of field.slice(1).split(',')) {
    const split = pair.indexOf('=');
    if (split > 0) tags[unescape(pair.slice(0, split))] = unescape(pair.slice(split + 1));
  }
  return tags;
}

function readNode(line, withTags = true) {
  const fields = line.split(' ');
  const id = Number(fields[0].slice(1));
  let lon = null;
  let lat = null;
  let tags = '';
  for (let i = 1; i < fields.length; i++) {
    const field = fields[i];
    if (field.charCodeAt(0) === 120 /* x */) lon = Number(field.slice(1));
    else if (field.charCodeAt(0) === 121 /* y */) lat = Number(field.slice(1));
    else if (withTags && field.charCodeAt(0) === 84 /* T */) tags = field;
  }
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return { type: 'node', id, lat, lon, tags: withTags ? readTags(tags) : {} };
}

function readWay(line) {
  const fields = line.split(' ');
  const id = Number(fields[0].slice(1));
  let tags = '';
  let refs = '';
  for (let i = 1; i < fields.length; i++) {
    const field = fields[i];
    if (field.charCodeAt(0) === 84 /* T */) tags = field;
    else if (field.charCodeAt(0) === 78 /* N */) refs = field;
  }
  if (refs.length < 2) return null;
  const nodes = refs.slice(1).split(',').map((ref) => Number(ref.slice(1)));
  return { type: 'way', id, tags: readTags(tags), nodes };
}

/*
 * osmium export writes one GeoJSON feature per line; a town's position is the middle of its shape.
 *
 * A town mapped as a closed way comes back twice: once as the way it is drawn as, once as the area
 * it encloses, under osmium's own area id. Keeping both would put two identical towns of the same
 * name in front of matchPlace, which answers a tie with no match rather than a guess - and the sign
 * would lose its direction and be dropped. So the area wins, and the way is kept only when it was
 * never assembled into one. Area ids are the source doubled, odd for a relation.
 */
function readPlaces(file) {
  const features = [];
  const assembled = new Set();
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (line.length < 2) continue;
    const feature = JSON.parse(line.charCodeAt(0) === 0x1e ? line.slice(1) : line);
    // tags-filter brings along everything a kept way or relation refers to, so the file also holds
    // ordinary tagged nodes - sign nodes among them. Passing one of those on would hand the same
    // sign to the parser twice, once as itself and once as a "place".
    if (!IS_PLACE.has(feature.properties?.place)) continue;
    const marker = String(feature.id ?? '');
    const area = marker[0] === 'a';
    const number = Number(marker.slice(1));
    if (!Number.isFinite(number)) continue;
    const type = marker[0] === 'n' ? 'node' : (area && number % 2 === 1) ? 'relation' : 'way';
    const id = area ? Math.floor(number / 2) : number;
    if (area && type === 'way') assembled.add(id);
    features.push({ id, type, area, middle: centre(feature.geometry), tags: feature.properties ?? {} });
  }

  const places = [];
  const kept = new Set();
  for (const feature of features) {
    if (!feature.middle) continue;
    if (feature.type === 'way' && !feature.area && assembled.has(feature.id)) continue;
    if (kept.has(`${feature.type}/${feature.id}`)) continue;
    kept.add(`${feature.type}/${feature.id}`);
    places.push(feature.type === 'node'
      ? { type: 'node', id: feature.id, lat: feature.middle.lat, lon: feature.middle.lon, tags: feature.tags }
      : { type: feature.type, id: feature.id, center: feature.middle, tags: feature.tags });
  }
  return places;
}

function centre(geometry) {
  if (!geometry) return null;
  if (geometry.type === 'Point') return { lat: geometry.coordinates[1], lon: geometry.coordinates[0] };
  let west = Infinity;
  let east = -Infinity;
  let south = Infinity;
  let north = -Infinity;
  const walk = (coordinates) => {
    if (typeof coordinates[0] === 'number') {
      west = Math.min(west, coordinates[0]);
      east = Math.max(east, coordinates[0]);
      south = Math.min(south, coordinates[1]);
      north = Math.max(north, coordinates[1]);
      return;
    }
    coordinates.forEach(walk);
  };
  walk(geometry.coordinates);
  if (west === Infinity) return null;
  return { lat: (south + north) / 2, lon: (west + east) / 2 };
}
