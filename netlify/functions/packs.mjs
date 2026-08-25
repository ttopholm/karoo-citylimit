/*
 * Serves the region packs to the verification map.
 *
 * The packs live as assets on the "packs" release, and GitHub redirects those downloads to a host
 * that sends no CORS headers, so a browser cannot fetch them directly. This function follows the
 * redirect server-side and hands the file back from the site's own origin. The extension on the
 * Karoo has no such trouble and fetches from GitHub directly.
 */

const RELEASE = 'https://github.com/ttopholm/karoo-citylimit/releases/download/packs';

/** Only the files the pack builder writes; this must not become an open proxy. */
const FILE_PATTERN = /^[a-z0-9]+(-[0-9]{3})?\.json$/;

const TIMEOUT_MS = 30_000;

export default async (request) => {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: cors() });
  }
  if (request.method !== 'GET') {
    return text('Only GET is supported', 405);
  }

  const file = new URL(request.url).pathname.split('/').pop() ?? '';
  if (!FILE_PATTERN.test(file)) {
    return text('Unknown pack file', 400);
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(`${RELEASE}/${file}`, {
      headers: { Accept: 'application/octet-stream' },
      redirect: 'follow',
      signal: controller.signal,
    });
    if (!response.ok) {
      return text(`The pack release answered ${response.status} for ${file}`, response.status);
    }
    return new Response(await response.text(), {
      status: 200,
      headers: {
        ...cors(),
        'Content-Type': 'application/json; charset=utf-8',
        // Packs are rebuilt monthly, so they can sit in the browser for a while.
        'Cache-Control': 'public, max-age=3600',
      },
    });
  } catch (error) {
    const message = error.name === 'AbortError'
      ? `The pack release did not answer within ${TIMEOUT_MS / 1000} s`
      : `Could not reach the pack release: ${error.message}`;
    return text(message, 504);
  } finally {
    clearTimeout(timer);
  }
};

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
  };
}

function text(message, status) {
  return new Response(message, {
    status,
    headers: { ...cors(), 'Content-Type': 'text/plain; charset=utf-8' },
  });
}

export const config = { path: '/api/packs/:file' };
