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
check('places parsed', places.length === 17, `${places.length}`);
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
