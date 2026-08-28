/*
 * Norwegian place-name signs, from Statens vegvesen's national road database.
 *
 * Norway has no town-entry sign to map. "Tettbygd strøk" was withdrawn from the sign catalogue, and
 * NVDB holds exactly one of them in the whole country - which is why OpenStreetMap has 87 signs for
 * Norway against Denmark's 8,958. There is nothing there to find.
 *
 * What Norway does have is the plain white place-name sign, 727.1, and 11,650 of them are in NVDB
 * with a position and the name written on them. It carries no speed limit, so it is not a town-entry
 * sign in the legal sense, but it is what a rider actually sees on arriving somewhere - which is
 * what this extension is for.
 *
 * The signs are handed on as if they were OpenStreetMap nodes, so the rest of the build treats them
 * like any other: they are placed on the road they stand beside, the direction into town is worked
 * out from that road and its speed zone, and a sign whose name matches no mapped place is dropped -
 * which is also how a sign naming a fjord or a valley falls away.
 *
 * The data is NLOD: free for any purpose, and the pack credits it.
 *
 *   node tools/nvdb-signs.mjs > norske-skilte.json
 */

const API = 'https://nvdbapiles.atlas.vegvesen.no/vegobjekter/96';

/** Sign plate 727.1, the ordinary place-name sign, and 727.2, the one with a symbol. */
const PLACE_NAME_PLATES = [7756, 10683];

/** The attribute holding the sign number, and the one holding what is written on it. */
const SIGN_NUMBER = 5530;
const TEXT = 1910;

const PAGE = 1000;
const ATTEMPTS = 5;

export const ATTRIBUTION =
  'Inneholder data under norsk lisens for offentlige data (NLOD) tilgjengeliggjort av Statens vegvesen.';

const headers = {
  'X-Client': 'karoo-citylimit (+https://github.com/ttopholm/karoo-citylimit)',
  Accept: 'application/json',
};

/**
 * Every place-name sign in Norway, shaped like the OpenStreetMap nodes the build reads.
 *
 * The tag is what a Danish sign would carry, so the same classification applies: a boundary that is
 * an entry one way and an exit the other. Which way is which is settled later, from the road.
 */
export async function fetchNorwegianSigns(log = console.log) {
  const filter = PLACE_NAME_PLATES.map((value) => `${SIGN_NUMBER}=${value}`).join(' OR ');
  const query = new URLSearchParams({
    egenskap: `(${filter})`,
    inkluder: 'egenskaper,lokasjon',
    srid: '4326',
    antall: String(PAGE),
  });

  const signs = [];
  let start;
  for (;;) {
    if (start) query.set('start', start);
    const page = await ask(`${API}?${query}`, log);
    for (const object of page.objekter ?? []) {
      const sign = toSign(object);
      if (sign) signs.push(sign);
    }
    const next = page.metadata?.neste;
    if (!next?.start || (page.objekter?.length ?? 0) === 0) break;
    start = next.start;
    log(`  ${signs.length} skilte hentet …`);
  }
  log(`  ${signs.length} stedsnavnskilte fra NVDB`);
  return signs;
}

function toSign(object) {
  const text = object.egenskaper?.find((one) => one.id === TEXT)?.verdi;
  const name = String(text ?? '').trim();
  if (!name) return null;
  const point = /POINT Z? ?\(([-0-9.]+) ([-0-9.]+)/.exec(object.lokasjon?.geometri?.wkt ?? '');
  if (!point) return null;
  // NVDB answers in lat lon order for srid 4326, unlike the usual well-known text.
  const lat = Number(point[1]);
  const lon = Number(point[2]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  return { id: object.id, lat, lon, name };
}

async function ask(url, log) {
  let complaint = 'intet forsøg';
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    try {
      const response = await fetch(url, { headers, signal: AbortSignal.timeout(180_000) });
      if (response.ok) return await response.json();
      complaint = `HTTP ${response.status}`;
    } catch (error) {
      complaint = error.message;
    }
    log(`    NVDB: ${complaint}, forsøg ${attempt}/${ATTEMPTS}`);
    await new Promise((resolve) => setTimeout(resolve, 5_000 * attempt));
  }
  throw new Error(`NVDB svarede ikke: ${complaint}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const signs = await fetchNorwegianSigns((line) => process.stderr.write(`${line}\n`));
  process.stdout.write(`${JSON.stringify(signs)}\n`);
}
