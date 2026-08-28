/*
 * Swedish town boundaries, from Trafikverket's national road database.
 *
 * Sweden signs a town with E5 "Tättbebyggt område", and where that sign stands is not a matter of
 * taste: the built-up area is decided by the municipality, and the decision is recorded in NVDB
 * against the road network. So the boundary itself is public data, even though the signs are not.
 *
 * OpenStreetMap carries 492 town signs in Sweden's extract, and 164 of them stand more than a
 * kilometre from any built-up area - they are the plain white place-name signs at hamlets, mapped
 * under the same tag. What is left is a few hundred signs for a country of two thousand towns,
 * which is why the map alone cannot carry a Swedish pack.
 *
 * The data product is "Tättbebyggt område", ordered from Lastkajen and published as an asset on the
 * packs release: one GeoPackage layer, every stretch of road that lies inside a built-up area,
 * 98,436 km of it. It carries no place name - the only attribute is the municipal decision it rests
 * on, "1265 2024:28" - so the town is named later, from the nearest mapped place, exactly as an
 * unnamed Danish sign is.
 *
 * Finding the boundary is the whole job here, and it is done by asking the built-up area about the
 * map's own roads rather than by reading NVDB's topology. A boundary sometimes falls in the middle
 * of a road link and sometimes exactly on one of its ends, and telling the second case from a
 * cul-de-sac needs the roads outside the town, which this data product does not carry. Turned
 * around, the question is easy: the built-up area becomes a grid of eleven-metre cells, every node
 * of every rideable road is asked whether it stands in one, and a boundary is where the answer
 * changes from one node to the next. That also settles the direction into town - it is the way the
 * answer changes - which is the one thing the Danish signs never say outright.
 *
 * The licence is CC0: no attribution required, and the pack names the source anyway.
 *
 *   node tools/nvdb-sweden.mjs se-roads.osm.pbf > svenske-skilte.json
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
import { download } from './osm-dump.mjs';

/** The ordered extract, published as an asset on the packs release. */
const PACKAGE =
  'https://github.com/ttopholm/karoo-citylimit/releases/download/packs/karoo-citylimit_594332.gpkg';

const LAYER = 'NVDB_DK_O_40_TattbebyggtOmrade';

export const ATTRIBUTION = 'Tättbebyggt område från NVDB, Trafikverket (CC0 1.0).';

/*
 * The cell the built-up area is drawn into, about eleven metres each way.
 *
 * It has to be wide enough that the map's road and the road database's road land in the same cell -
 * they are surveyed apart and differ by a few metres - and narrow enough that the road next door
 * does not. A lookup checks the cell and its eight neighbours, so the real tolerance is 11-16 m.
 */
const LAT_STEP = 0.0001;
const LON_STEP = 0.0002;

/** The roads a town sign is put up on. A footpath crossing the boundary carries no sign. */
const RIDEABLE = /^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$/;

/** How finely the boundary is pinned down between the two nodes it falls between. */
const BISECTIONS = 12;

/*
 * Room in the identifier for the boundaries that share a road node.
 *
 * The node is what a boundary is named after, and a junction just outside town can be the outer end
 * of more than one of them. Eight is more arms than a junction has, and a node identifier times
 * eight is still a whole number a double can hold.
 */
const SIGNS_PER_NODE = 8;

/**
 * Every point where a rideable road enters a Swedish built-up area.
 *
 * @param roadsPbf the dump filtered to highways, which the caller has already made
 * @param workDir where the extract and the linework are kept
 * @returns signs shaped as the build's brought signs, each already standing on a road node and
 *   carrying the direction into town
 */
export async function fetchSwedishBoundaries({ roadsPbf, workDir, log = console.log }) {
  fs.mkdirSync(workDir, { recursive: true });
  const geopackage = path.join(workDir, 'se-tattbebyggt.gpkg');
  const linework = path.join(workDir, 'se-tattbebyggt.csv');

  await download(PACKAGE, geopackage, log);
  if (!fs.existsSync(linework)) await toLinework(geopackage, linework, log);

  const inZone = await readZone(linework, log);

  // The nodes come before the ways, so each node is answered as it is read and the ways can be
  // measured on the spot.
  const urbanNodes = new Set();
  const crossings = [];
  const seen = new Set();
  let nodes = 0;
  let ways = 0;
  await eachLine(roadsPbf, log, (line) => {
    const kind = line.charCodeAt(0);
    if (kind === 110 /* n */) {
      const node = readNode(line);
      nodes++;
      if (node && inZone(node.lat, node.lon)) urbanNodes.add(node.id);
      return;
    }
    if (kind !== 119 /* w */) return;
    const way = readWay(line);
    if (!way || !RIDEABLE.test(way.highway)) return;
    ways++;
    let was = urbanNodes.has(way.nodes[0]);
    for (let i = 1; i < way.nodes.length; i++) {
      const now = urbanNodes.has(way.nodes[i]);
      if (now === was) continue;
      // The sign stands outside the town facing in, so it belongs to the node on the outside.
      const crossing = now
        ? { outside: way.nodes[i - 1], inside: way.nodes[i] }
        : { outside: way.nodes[i], inside: way.nodes[i - 1] };
      // Two ways can be drawn along the same stretch, and the boundary would then be found twice.
      const edge = `${crossing.outside}/${crossing.inside}`;
      if (!seen.has(edge)) { seen.add(edge); crossings.push(crossing); }
      was = now;
    }
  });
  log(`  ${nodes} knuder, ${urbanNodes.size} i byzone, ${ways} veje at cykle på, ${crossings.length} overgange`);

  // A second pass picks up the coordinates of the nodes the crossings sit between.
  const wanted = new Set();
  for (const crossing of crossings) {
    wanted.add(crossing.outside);
    wanted.add(crossing.inside);
  }
  const at = new Map();
  await eachLine(roadsPbf, log, (line) => {
    if (line.charCodeAt(0) !== 110 /* n */) return;
    const node = readNode(line);
    if (node && wanted.has(node.id)) at.set(node.id, { lat: node.lat, lon: node.lon });
  });

  const signs = [];
  const perNode = new Map();
  let lost = 0;
  let crowded = 0;
  for (const crossing of crossings) {
    const outside = at.get(crossing.outside);
    const inside = at.get(crossing.inside);
    if (!outside || !inside) { lost++; continue; }
    const point = boundaryBetween(outside, inside, inZone);
    // A road forking just outside town crosses the boundary twice from the same node, and two signs
    // with one id are one sign by the time the pack is written.
    const nth = perNode.get(crossing.outside) ?? 0;
    perNode.set(crossing.outside, nth + 1);
    if (nth >= SIGNS_PER_NODE) { crowded++; continue; }
    signs.push({
      id: crossing.outside * SIGNS_PER_NODE + nth,
      lat: point.lat,
      lon: point.lon,
      roadNode: crossing.outside,
      entryHeading: bearing(outside, inside),
    });
  }
  if (lost > 0) log(`  ${lost} overgange manglede en koordinat og er udeladt`);
  if (crowded > 0) log(`  ${crowded} overgange delte knude med otte andre og er udeladt`);
  log(`  ${signs.length} bygrænser fra NVDB`);
  return signs;
}

/*
 * Where the boundary falls between the two nodes.
 *
 * Two nodes of a road can be a hundred metres apart, and putting the sign on the outer one puts it
 * that far out of town. Halving the gap twelve times settles it to within a metre - well inside the
 * cell the built-up area is drawn in, which is as close as this data can answer.
 */
function boundaryBetween(outside, inside, inZone) {
  let out = outside;
  let into = inside;
  for (let step = 0; step < BISECTIONS; step++) {
    const middle = { lat: (out.lat + into.lat) / 2, lon: (out.lon + into.lon) / 2 };
    if (inZone(middle.lat, middle.lon)) into = middle; else out = middle;
  }
  return out;
}

/*
 * The built-up area as a grid: a cell is marked when a stretch of urban road runs through it.
 *
 * Ninety-eight thousand kilometres of line is eight and a half million cells, kept as one sorted
 * array of longitudes per line of latitude. That is seventy megabytes and a binary search, where a
 * set of coordinate strings would be half a gigabyte.
 */
async function readZone(csv, log) {
  const bands = new Map();
  const mark = (lat, lon) => {
    const band = Math.round(lat / LAT_STEP);
    let list = bands.get(band);
    if (!list) bands.set(band, list = []);
    list.push(Math.round(lon / LON_STEP));
  };

  let lines = 0;
  const reader = readline.createInterface({ input: fs.createReadStream(csv), crlfDelay: Infinity });
  let heading = true;
  for await (const line of reader) {
    if (heading) { heading = false; continue; }
    const open = line.indexOf('(');
    const close = line.indexOf(')');
    if (open < 0 || close < 0) continue;
    let previous = null;
    for (const raw of line.slice(open + 1, close).split(',')) {
      const point = readPoint(raw);
      if (previous) {
        // Half a cell at a time, so no cell along the way is stepped over.
        const dLat = point.lat - previous.lat;
        const dLon = point.lon - previous.lon;
        const steps = Math.max(1, Math.ceil(
          Math.max(Math.abs(dLat) / LAT_STEP, Math.abs(dLon) / LON_STEP) * 2,
        ));
        for (let i = 1; i <= steps; i++) mark(previous.lat + dLat * i / steps, previous.lon + dLon * i / steps);
      } else {
        mark(point.lat, point.lon);
      }
      previous = point;
    }
    lines++;
  }

  let cells = 0;
  for (const [band, list] of bands) {
    const sorted = Int32Array.from(new Set(list)).sort();
    bands.set(band, sorted);
    cells += sorted.length;
  }
  log(`  byzonen: ${lines} strækninger, ${cells} celler`);

  return (lat, lon) => {
    const band = Math.round(lat / LAT_STEP);
    const column = Math.round(lon / LON_STEP);
    for (let i = -1; i <= 1; i++) {
      const sorted = bands.get(band + i);
      if (!sorted) continue;
      if (holds(sorted, column) || holds(sorted, column - 1) || holds(sorted, column + 1)) return true;
    }
    return false;
  };
}

function holds(sorted, value) {
  let low = 0;
  let high = sorted.length - 1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if (sorted[middle] === value) return true;
    if (sorted[middle] < value) low = middle + 1; else high = middle - 1;
  }
  return false;
}

/** "11.9556205 57.7032234 3.53" - longitude, latitude, and a height nobody asked for. */
function readPoint(raw) {
  const text = raw.trim();
  const split = text.indexOf(' ');
  const second = text.indexOf(' ', split + 1);
  return {
    lon: Number(text.slice(0, split)),
    lat: Number(second < 0 ? text.slice(split + 1) : text.slice(split + 1, second)),
  };
}

/*
 * The layer as one line of well-known text per stretch of urban road.
 *
 * Written aside and renamed, so a run that dies halfway leaves nothing behind that the next one
 * would take for a finished file. The name aside still has to end in .csv: given anything else,
 * ogr2ogr reads the output as a datasource and writes a directory with the layer's file inside it,
 * which is a directory this then tries to read as a file.
 */
async function toLinework(geopackage, csv, log) {
  log('  ogr2ogr …');
  const partial = csv.replace(/\.csv$/, '.part.csv');
  fs.rmSync(partial, { force: true, recursive: true });
  await run('ogr2ogr', ['-f', 'CSV', partial, geopackage, LAYER, '-select', 'ELEMENT_ID', '-lco', 'GEOMETRY=AS_WKT'],
    'ogr2ogr blev ikke fundet (apt-get install gdal-bin)');
  fs.renameSync(partial, csv);
}

async function eachLine(pbf, log, onLine) {
  log('  osmium cat …');
  const child = spawn('osmium', ['cat', '-f', 'opl,add_metadata=false', '-o', '-', pbf], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let complaint = '';
  child.stderr.on('data', (chunk) => { complaint += chunk; });
  const finished = new Promise((resolve, reject) => {
    child.on('error', (error) => reject(new Error(
      error.code === 'ENOENT' ? 'osmium blev ikke fundet (apt-get install osmium-tool)' : error.message,
    )));
    child.on('close', (code) => (code === 0
      ? resolve()
      : reject(new Error(`osmium cat fejlede (${code}): ${complaint.trim().slice(0, 400)}`))));
  });
  const reader = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
  for await (const line of reader) if (line.length > 0) onLine(line);
  await finished;
}

function run(command, args, missing) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let complaint = '';
    child.stderr.on('data', (chunk) => { complaint += chunk; });
    child.on('error', (error) => reject(new Error(error.code === 'ENOENT' ? missing : error.message)));
    child.on('close', (code) => (code === 0
      ? resolve()
      : reject(new Error(`${command} fejlede (${code}): ${complaint.trim().slice(0, 400)}`))));
  });
}

function readNode(line) {
  const fields = line.split(' ');
  let lon = null;
  let lat = null;
  for (let i = 1; i < fields.length; i++) {
    if (fields[i].charCodeAt(0) === 120 /* x */) lon = Number(fields[i].slice(1));
    else if (fields[i].charCodeAt(0) === 121 /* y */) lat = Number(fields[i].slice(1));
  }
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return { id: Number(fields[0].slice(1)), lat, lon };
}

function readWay(line) {
  const fields = line.split(' ');
  let highway = '';
  let list = '';
  for (let i = 1; i < fields.length; i++) {
    if (fields[i].charCodeAt(0) === 84 /* T */) highway = highwayOf(fields[i]);
    else if (fields[i].charCodeAt(0) === 78 /* N */) list = fields[i];
  }
  if (!highway || list.length < 2) return null;
  const nodes = list.slice(1).split(',').map((node) => Number(node.slice(1)));
  return nodes.length < 2 ? null : { highway, nodes };
}

const unescape = (text) => (text.includes('%')
  ? text.replace(/%([0-9A-Fa-f]+)%/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
  : text);

function highwayOf(field) {
  for (const pair of field.slice(1).split(',')) {
    if (pair.startsWith('highway=')) return unescape(pair.slice(8));
  }
  return '';
}

function bearing(from, to) {
  const toRad = Math.PI / 180;
  const y = Math.sin((to.lon - from.lon) * toRad) * Math.cos(to.lat * toRad);
  const x = Math.cos(from.lat * toRad) * Math.sin(to.lat * toRad)
    - Math.sin(from.lat * toRad) * Math.cos(to.lat * toRad) * Math.cos((to.lon - from.lon) * toRad);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [roadsPbf, workDir = 'build/dumps'] = process.argv.slice(2);
  if (!roadsPbf) throw new Error('brug: node tools/nvdb-sweden.mjs <veje.osm.pbf> [arbejdsmappe]');
  const signs = await fetchSwedishBoundaries({
    roadsPbf, workDir, log: (line) => process.stderr.write(`${line}\n`),
  });
  process.stdout.write(`${JSON.stringify(signs)}\n`);
}
