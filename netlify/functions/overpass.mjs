/*
 * Fetches Overpass data on behalf of the verification map.
 *
 * Overpass answers ordinary server-to-server requests but rejects the same query when it arrives
 * with a browser's headers, so the page cannot call it directly - and a plain Netlify proxy rewrite
 * does not help either, since it forwards the browser's headers unchanged. This function makes its
 * own request with a small, fixed set of headers, and returns the result to the page from its own
 * origin.
 */

const SERVERS = {
  de: 'https://overpass-api.de/api/interpreter',
  kumi: 'https://overpass.kumi.systems/api/interpreter',
  coffee: 'https://overpass.private.coffee/api/interpreter',
};

const USER_AGENT = 'karoo-citylimit-verify-map/1.0 (+https://github.com/ttopholm/karoo-citylimit)';

/** Overpass QL for one grid cell is well under this; anything larger is not from the map. */
const MAX_QUERY_BYTES = 8_000;

const TIMEOUT_MS = 90_000;

export default async (request) => {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders() });
  }
  if (request.method !== 'POST') {
    return text('Only POST is supported', 405);
  }

  const url = new URL(request.url);
  const upstream = SERVERS[url.searchParams.get('server') ?? 'de'];
  if (!upstream) {
    return text('Unknown server', 400);
  }

  const query = await request.text();
  if (!query.trim()) {
    return text('Empty query', 400);
  }
  if (query.length > MAX_QUERY_BYTES) {
    return text('Query too large', 413);
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(upstream, {
      method: 'POST',
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        'User-Agent': USER_AGENT,
        Accept: 'application/json',
      },
      body: query,
      signal: controller.signal,
    });

    const body = await response.text();
    if (!response.ok) {
      return text(`Overpass answered ${response.status}: ${body.slice(0, 400)}`, response.status);
    }
    return new Response(body, {
      status: 200,
      headers: {
        ...corsHeaders(),
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=300',
      },
    });
  } catch (error) {
    const message = error.name === 'AbortError'
      ? `Overpass did not answer within ${TIMEOUT_MS / 1000} s`
      : `Could not reach Overpass: ${error.message}`;
    return text(message, 504);
  } finally {
    clearTimeout(timer);
  }
};

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
}

function text(message, status) {
  return new Response(message, {
    status,
    headers: { ...corsHeaders(), 'Content-Type': 'text/plain; charset=utf-8' },
  });
}

export const config = { path: '/api/overpass' };
