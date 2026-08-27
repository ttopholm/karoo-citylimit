/*
 * Kontrollerer at logikken i tools/verify-map.html svarer til core/ i udvidelsen:
 * samme skilteklassifikation, samme retning ind i byen og samme beslutning om beskeder,
 * afprøvet på de samme data som Kotlin-testene bruger.
 *
 *   node tools/verify-map-parity.mjs
 */
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const html = fs.readFileSync(path.join(root, 'tools/verify-map.html'), 'utf8');
const script = html.split('<script id="citylimit-logic">')[1].split('</script>')[0];
vm.runInThisContext(script);
const C = globalThis.CityLimit;

const body = fs.readFileSync(path.join(root, 'core/src/test/resources/overpass-noerre-herlev.json'), 'utf8');
const { signs, dropped, places } = C.parseResponse(body);

const fail = [];
const check = (name, ok, detail = '') => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' -> ' + detail : ''}`); if (!ok) fail.push(name); };

check('signs parsed', signs.length === 18, `${signs.length}`);
check('no dropped in fixture', dropped.length === 0, `${dropped.length}`);
// The fixture now carries places from a wider area than the signs, matching what the code asks for.
check('places parsed', places.length >= 100, `${places.length}`);
check('all signs have a direction', signs.every(s => s.entryHeading !== null));

// Same fixture nodes as the Kotlin ride simulation test.
const north = { lat: 55.8955018, lng: 12.2774175 };
const south = { lat: 55.8872001, lng: 12.2765744 };
const extend = (point, towards, meters) => C.destination(point, meters, C.bearing(point, towards) + 180);

const southbound = C.simulateRide([extend(north, south, 800), extend(south, north, 800)], signs, {});
const northbound = C.simulateRide([extend(south, north, 800), extend(north, south, 800)], signs, {});
check('southbound announces once', southbound.length === 1, southbound.map(a => `${a.sign.name} @${Math.round(a.distanceMeters)}m`).join(', '));
check('southbound names the village', southbound[0]?.sign.name === 'Nørre Herlev');
check('alert comes before the sign', southbound[0]?.distanceMeters > 100 && southbound[0]?.distanceMeters <= 200, `${Math.round(southbound[0]?.distanceMeters)}m`);
check('northbound announces once', northbound.length === 1, northbound.map(a => `${a.sign.name} @${Math.round(a.distanceMeters)}m`).join(', '));
check('northbound names the village', northbound[0]?.sign.name === 'Nørre Herlev');
check('southbound and northbound use different signs', southbound[0]?.sign.id !== northbound[0]?.sign.id, `${southbound[0]?.sign.id} vs ${northbound[0]?.sign.id}`);

// Classification parity with TrafficSignCodesTest.
check('DK:E55 is entry', C.classify({ traffic_sign: 'DK:E55' }).entry && !C.classify({ traffic_sign: 'DK:E55' }).exit);
check('DK:E56 is exit only', !C.classify({ traffic_sign: 'DK:E56' }).entry);
check('generic city_limit is both', (g => g.entry && g.exit && !g.directional)(C.classify({ traffic_sign: 'city_limit' })));
check('city_limit=end is exit only', !C.classify({ traffic_sign: 'city_limit', city_limit: 'end' }).entry);
check('DE:310[Berlin] is entry', C.classify({ traffic_sign: 'DE:310[Berlin];DE:1000-30' }).entry);
check('DK:C55 is ignored', !C.classify({ traffic_sign: 'DK:C55' }).entry && !C.classify({ traffic_sign: 'DK:C55' }).exit);
check('forward/backward pair', (s => s.entry && s.exit && s.directional)(C.classify({ 'traffic_sign:forward': 'DK:E55', 'traffic_sign:backward': 'DK:E56' })));

// Geometry parity.
check('bearing north', Math.abs(C.bearing({lat:55.9339,lng:12.3010},{lat:56.0,lng:12.3010})) < 0.5);
check('distance matches Kotlin', Math.abs(C.distance({lat:55.9339,lng:12.3010},{lat:55.6761,lng:12.5683}) - 33180) < 200);
check('bearing difference wraps', C.bearingDifference(350, 10) === 20);
// A named sign must not borrow a neighbour's direction (the "Lyngen" case from Odsherred).
const lyngen = C.parseResponse(JSON.stringify({ elements: [
  { type:'node', id: 6348603353, lat: 55.883, lon: 11.54, tags: { traffic_sign: 'city_limit', name: 'Lyngen' } },
  { type:'node', id: 10, lat: 55.8809, lon: 11.5384, tags: { name: 'Ellinge Kongepart', place: 'hamlet' } },
  { type:'node', id: 11, lat: 55.8764, lon: 11.5426, tags: { name: 'Ellinge Lyng', place: 'hamlet' } },
]}));
check('named sign without a matching place has no direction',
  lyngen.signs.length === 1 && lyngen.signs[0].entryHeading === null && lyngen.signs[0].townId === null);
check('unnamed sign still falls back to the nearest place',
  C.parseResponse(JSON.stringify({ elements: [
    { type:'node', id: 1, lat: 55.883, lon: 11.54, tags: { traffic_sign: 'city_limit' } },
    { type:'node', id: 10, lat: 55.8809, lon: 11.5384, tags: { name: 'Ellinge Kongepart', place: 'hamlet' } },
  ]})).signs[0].entryHeading !== null);
check('places are queried from a wider box than signs', (() => {
  const q = C.buildQuery({ south: 55.85, west: 12.25, north: 55.90, east: 12.35 });
  const placeLines = q.split('\n').filter(l => l.includes('"place"'));
  if (placeLines.length !== 3) return false;
  return placeLines.every(line => {
    const box = line.slice(line.indexOf('(') + 1, line.indexOf(')')).split(',').map(Number);
    return box[0] < 55.85 && box[1] < 12.25 && box[2] > 55.90 && box[3] > 12.35;
  });
})());

// A sign belongs to the road it stands on. Real case: Kulhuse in Hornsherred, where signs on side
// roads running 58 degrees sit metres from Kulhusvej, which runs 314.
check('line difference ignores which way round', C.lineDifference(134, 314) === 0 && C.lineDifference(330, 58) === 88);
check('a sign across your road is skipped', (() => {
  const sign = { id: 1, position: {lat:55.91813,lng:11.92277}, name: 'Kulhuse', entryHeading: 333, roadBearing: 58 };
  const from = C.destination(sign.position, 400, 145), to = C.destination(sign.position, 200, 325);
  return C.simulateRide([from, to], [sign], {}).length === 0;
})());
check('a sign on your own road still fires', (() => {
  const sign = { id: 2, position: {lat:55.93532,lng:11.90776}, name: 'Kulhuse', entryHeading: 134, roadBearing: 314 };
  const from = C.destination(sign.position, 400, 314), to = C.destination(sign.position, 200, 134);
  return C.simulateRide([from, to], [sign], {}).length === 1;
})());
check('entry heading follows the road towards the town', (() => {
  const sign = { position: {lat:55.9103,lng:11.9340}, roadBearing: 64.9,
                 place: { position: {lat:55.9165,lng:11.9210} }, entryHeading: 326 };
  C.alignEntryHeadings([sign]);
  return Math.round(sign.entryHeading) === 245;
})(), 'aligned');
check('a road running past the town keeps the town bearing', (() => {
  // Both directions along the road end up equally far from the centre: no honest answer.
  const sign = { position: {lat:55.9000,lng:12.0000}, roadBearing: 90,
                 place: { position: {lat:55.9100,lng:12.0000} }, entryHeading: 0 };
  C.alignEntryHeadings([sign]);
  return sign.entryHeading === 0;
})());

// The town sign is where the 50 begins, so the speed limit on the roads says which side the town is
// on. Real case: the Kulhuse signs stand on short link roads between Gammel Kulhusvej and Kulhusvej,
// with the village centre off to the north-west, so the bearing to the centre pointed across the
// road the rider is on and the signs never fired.
check('a town road is urban, a country road is not',
  C.speedZone({ maxspeed: '50', 'source:maxspeed': 'DK:urban' }) === 'urban'
  && C.speedZone({ maxspeed: '80', 'source:maxspeed': 'DK:rural' }) === 'rural'
  && C.speedZone({ maxspeed: '40' }) === 'urban'
  && C.speedZone({ highway: 'secondary' }) === 'rural'
  && C.speedZone({ highway: 'residential' }) === 'urban'
  && C.speedZone({ highway: 'track' }) === null);
check('the speed limit beats the road class', C.speedZone({ highway: 'secondary', maxspeed: '50' }) === 'urban');

check('a sign in a town road is entered away from the country road', (() => {
  // Solsortevej (way 845017444) runs 59 m from Gammel Kulhusvej to Kulhusvej with the sign 8 m from
  // the Kulhusvej end; the rider turns off Kulhusvej heading 238.
  const sign = { id: 7996794555, position: { lat: 55.9181292, lng: 11.9227711 }, entryHeading: 333, roadBearing: 57.9 };
  const road = { id: 845017444, tags: { highway: 'residential', maxspeed: '50', 'source:maxspeed': 'DK:urban' },
    nodes: [112090557, 10285565809, 7996794555, 1531070730],
    geometry: [{ lat: 55.917887, lon: 11.922083 }, { lat: 55.917902, lon: 11.922126 },
      { lat: 55.918129, lon: 11.922771 }, { lat: 55.918167, lon: 11.922879 }] };
  const joins = [
    { id: 12350420, tags: { highway: 'unclassified', maxspeed: '50', 'source:maxspeed': 'DK:urban' }, nodes: [112090557] },
    { id: 656811012, tags: { highway: 'secondary', maxspeed: '80', 'source:maxspeed': 'DK:rural' }, nodes: [1531070730] },
  ];
  C.orientBySpeedZone([sign], [road], joins);
  return Math.round(sign.entryHeading) === 238;
})(), 'Kulhuse');

check('a sign where the limit changes points into the town', (() => {
  // Vollerupvej: 50 to the west of the sign, 80 to the east.
  const sign = { id: 1049177615, position: { lat: 55.7230925, lng: 11.0694762 }, entryHeading: 264, roadBearing: 78 };
  const urban = { id: 133487813, tags: { highway: 'unclassified', maxspeed: '50', 'source:maxspeed': 'DK:urban' },
    nodes: [11119128079, 1049177615], geometry: [{ lat: 55.72304, lon: 11.06904 }, { lat: 55.7230925, lon: 11.0694762 }] };
  const rural = { id: 1198655642, tags: { highway: 'unclassified', maxspeed: '80', 'source:maxspeed': 'DK:rural' },
    nodes: [1049177615, 11119128080], geometry: [{ lat: 55.7230925, lon: 11.0694762 }, { lat: 55.72449, lon: 11.07886 }] };
  C.orientBySpeedZone([sign], [urban, rural], []);
  return C.bearingDifference(sign.entryHeading, 258) < 10;
})(), 'Vollerup');

check('no answer leaves the direction alone', (() => {
  // Both sides of the sign are town roads: the speed limit says nothing, so the town centre stands.
  const sign = { id: 1, position: { lat: 55.9181292, lng: 11.9227711 }, entryHeading: 333, roadBearing: 57.9 };
  const road = { id: 2, tags: { highway: 'residential', maxspeed: '50' }, nodes: [10, 1, 11],
    geometry: [{ lat: 55.91800, lon: 11.92240 }, { lat: 55.9181292, lon: 11.9227711 }, { lat: 55.91820, lon: 11.92300 }] };
  const joins = [{ id: 3, tags: { highway: 'residential', maxspeed: '50' }, nodes: [10, 11] }];
  C.orientBySpeedZone([sign], [road], joins);
  return sign.entryHeading === 333;
})());

// The grid the extension caches in; the pack builder groups signs by the same cells.
check('cell id matches the extension grid', C.cellIdFor({lat:55.9339,lng:12.3010}) === '1118/123', C.cellIdFor({lat:55.9339,lng:12.3010}));
check('cell id handles negative coordinates', C.cellIdFor({lat:-33.9,lng:-70.7}) === '-678/-707', C.cellIdFor({lat:-33.9,lng:-70.7}));

check('spacing in a name does not matter', C.matchPlace({lat:55.93,lng:11.64}, 'Vesterlyng', [
  { id: 10038782603, position: {lat:55.9348,lng:11.6438}, name: 'Vester Lyng', kind: 'hamlet', isArea: false },
  { id: 2512942163, position: {lat:55.9312,lng:11.6967}, name: 'Øster Lyng', kind: 'village', isArea: false },
]).id === 10038782603);

// Signs drop a town's regional qualifier: "Nykøbing" for Nykøbing Sjælland.
check('qualifier match picks the significant place', C.matchPlace({lat:55.9140,lng:11.6530}, 'Nykøbing', [
  { id: 21686563, position: {lat:55.9233,lng:11.6690}, name: 'Nykøbing Sjælland', kind: 'village', isArea: false },
  { id: 4607335659, position: {lat:55.9416,lng:11.6782}, name: 'Nykøbing Lyng', kind: 'hamlet', isArea: false },
]).id === 21686563);
check('ambiguous qualifier match is no match', C.matchPlace({lat:55.8850,lng:11.5400}, 'Ellinge', [
  { id: 1, position: {lat:55.8764,lng:11.5426}, name: 'Ellinge Lyng', kind: 'hamlet', isArea: false },
  { id: 2, position: {lat:55.8809,lng:11.5384}, name: 'Ellinge Kongepart', kind: 'hamlet', isArea: false },
]) === null);
check('exact name beats a qualifier match', C.matchPlace({lat:55.8700,lng:11.5500}, 'Hønsinge', [
  { id: 1, position: {lat:55.8600,lng:11.5300}, name: 'Hønsinge Lyng', kind: 'hamlet', isArea: false },
  { id: 2, position: {lat:55.8648,lng:11.5562}, name: 'Hønsinge', kind: 'hamlet', isArea: false },
]).id === 2);

// A sign and a place do not always spell a name the same way. Real cases from the Danish data,
// mirrored by PlaceSpellingTest in core/.
const at = (name, lat, lng, kind = 'hamlet', id = 1) =>
  ({ id, position: { lat, lng }, name, kind, isArea: false });

check('the definite form is the same town', (() => {
  const sign = { lat: 55.8764611, lng: 11.6705286 };   // node 11112564951, 373 m from Strandhuse
  return C.matchPlace(sign, 'Strandhusene', [at('Strandhuse', 55.8790, 11.6690)])?.id === 1
    && C.matchPlace(sign, 'Øer', [at('Øerne', 55.8790, 11.6690)])?.id === 1;
})());
check('an abbreviation on the sign is written out',
  C.matchPlace({ lat: 55.5900, lng: 11.8600 }, 'Kr. Hvalsø',
    [at('Kirke Hvalsø', 55.5905, 11.8610, 'village')])?.id === 1);
check('one letter wrong is still the same town', (() => {
  const sign = { lat: 56.2000, lng: 10.5000 };
  return [['Feldbulle', 'Feldballe'], ['Ganløsev', 'Ganløse'], ['Slaglunde', 'Slagslunde']]
    .every(([name, town]) => C.matchPlace(sign, name, [at(town, 56.2010, 10.5010, 'village')])?.id === 1);
})());
check('letters that sound alike count as one',
  C.matchPlace({ lat: 56, lng: 10 }, 'Åes', [at('Ås', 56.0010, 10.0010)])?.id === 1);
check('a direction word is the name, not a slip',
  C.matchPlace({ lat: 54.96, lng: 9.68 }, 'Øster Sottrup',
    [at('Vester Sottrup', 54.9605, 9.6810, 'village')]) === null);
check('two towns spelled equally close are no answer',
  C.matchPlace({ lat: 56, lng: 10 }, 'Hover',
    [at('Hoven', 56.0010, 10.0010), at('Hoves', 56.0012, 10.0012, 'hamlet', 2)]) === null);
check('a town too far away is not the one on the sign',
  C.matchPlace({ lat: 56, lng: 10 }, 'Feldbulle', [at('Feldballe', 56.0500, 10, 'village')]) === null);
check('a short name is left alone',
  C.matchPlace({ lat: 56, lng: 10 }, 'Hem', [at('Hee', 56.0010, 10.0010)]) === null);
check('an exact match is never overruled',
  C.matchPlace({ lat: 56, lng: 10 }, 'Hørning',
    [at('Hørning', 56.0060, 10, 'village'), at('Hørninge', 56.0005, 10, 'hamlet', 2)])?.id === 1);

check('query collects place nodes, ways and relations', (q => q.includes('node(') && q.includes('way(') && q.includes('relation(') && q.includes('out center qt;'))(C.buildQuery({south:55.85,west:12.25,north:55.90,east:12.35})));

// Areas as a fallback for towns without a place node, mirroring OverpassTest.
const areaOnly = C.parseResponse(JSON.stringify({ elements: [
  { type:'node', id: 1, lat: 55.91, lon: 12.28, tags: { traffic_sign: 'city_limit', name: 'Arealby' } },
  { type:'way', id: 500, center: { lat: 55.90, lon: 12.28 }, tags: { name: 'Arealby', place: 'village' } },
]}));
check('area centre gives a direction', Math.abs(areaOnly.signs[0]?.entryHeading - 180) < 2, `${Math.round(areaOnly.signs[0]?.entryHeading)}°`);
check('area is not parsed as a sign', C.parseResponse(JSON.stringify({ elements: [
  { type:'way', id: 600, center: { lat: 55.9, lon: 12.28 }, tags: { traffic_sign: 'city_limit', name: 'Vejby' } },
]})).signs.length === 0);
check('node beats area centre for an unnamed sign', C.matchPlace({lat:55.90,lng:12.28}, null, [
  { id: 1, position: {lat:55.896,lng:12.28}, name: 'Nodeby', kind: 'village', isArea: false },
  { id: 2, position: {lat:55.899,lng:12.28}, name: 'Arealby', kind: 'suburb', isArea: true },
]).id === 1);
check('named area beats unrelated node', C.matchPlace({lat:55.90,lng:12.28}, 'Skiltby', [
  { id: 1, position: {lat:55.898,lng:12.28}, name: 'Nabolandsby', kind: 'village', isArea: false },
  { id: 2, position: {lat:55.895,lng:12.28}, name: 'Skiltby', kind: 'town', isArea: true },
]).id === 2);

check('query has bbox and filters', (q => q.includes('55.850000,12.250000,55.900000,12.350000') && q.includes('traffic_sign') && q.includes('place'))(C.buildQuery({south:55.85,west:12.25,north:55.90,east:12.35})));

// Exit-only nodes must be dropped, mirroring OverpassTest.
const withExit = C.parseResponse(JSON.stringify({ elements: [
  { type:'node', id: 1, lat: 55.883, lon: 12.27, tags: { traffic_sign: 'DK:E56', name: 'Nørre Herlev' } },
  { type:'node', id: 2, lat: 55.8889703, lon: 12.2740223, tags: { name: 'Nørre Herlev', place: 'village' } },
]}));
check('exit-only node is dropped', withExit.signs.length === 0 && withExit.dropped.length === 1);

console.log(fail.length === 0 ? '\nALL PARITY CHECKS PASSED' : `\n${fail.length} FAILED`);
process.exit(fail.length === 0 ? 0 : 1);
