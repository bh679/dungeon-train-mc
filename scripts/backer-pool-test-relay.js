'use strict';
/*
 * backer-pool-test-relay.js — a stand-in for the relay's GET /<CAP>/backers/pool, for testing the
 * backer name pool in a dev client without touching the real relay.
 *
 * The mod is pointed at this by setting DUNGEONTRAIN_RELAY_BASE_URL to
 * http://127.0.0.1:<port>/<cap> (see DungeonTrain.relayBaseUrl). Every other relay endpoint 404s,
 * which is harmless — each of DT's fetchers is independently no-throw.
 *
 *   node scripts/backer-pool-test-relay.js [port] [cap] [populated|empty]
 *
 * While it is running you can flip between a populated and an empty pool WITHOUT restarting
 * Minecraft — the mod re-fetches on every player login, so just visit:
 *
 *   http://127.0.0.1:<port>/mode/empty        then rejoin the world
 *   http://127.0.0.1:<port>/mode/populated    then rejoin the world
 *
 * The empty case is the one that matters most: it must leave naming byte-identical to a build
 * without this feature.
 */
const http = require('node:http');

const port = Number(process.argv[2] || 8899);
const cap = process.argv[3] || 'localtestcap';
let mode = process.argv[4] === 'empty' ? 'empty' : 'populated';

// Deliberately distinctive so they cannot be confused with AIN's own shipped community names.
const POPULATED = {
  ok: true,
  names: ['Backer Wolfy', 'Zizzle Vantablack', 'Mx Testcase'],
  phrases: ['keeps the lamps lit', 'never misses a stop'],
};
const EMPTY = { ok: true, names: [], phrases: [] };

/**
 * Test data for the death screen's "support the line" page (DonationSummaryClient reads
 * /donations/summary — that is the path shipped jars hardcode, and the real relay keeps it
 * forever as an alias for /backers/summary).
 *
 * Deliberately shaped to exercise the page's edge cases in one shot:
 *  - a long-enough leaderboard to make the supporter list SCROLL (the page eases a scroll offset)
 *  - both `source` values, so revolut and patreon rows render side by side
 *  - a name long enough to test truncation, and one with non-latin characters
 *  - percentCovered OVER 100, which is the "we're funded this month" branch
 *  - a `you` block, so "your contribution" renders (only sent when the name query arrives)
 */
const LEADERBOARD = [
  { name: 'Backer Wolfy', amountUsd: 120, source: 'revolut' },
  { name: 'Zizzle Vantablack', amountUsd: 90, source: 'patreon' },
  { name: 'Mx Testcase', amountUsd: 75, source: 'revolut' },
  { name: '老本願', amountUsd: 60, source: 'patreon' },
  { name: 'A Very Long Supporter Name Indeed', amountUsd: 45, source: 'revolut' },
  { name: 'Koli Drath', amountUsd: 30, source: 'patreon' },
  { name: 'Train Wolfy', amountUsd: 25, source: 'revolut' },
  { name: '-Phoenix-', amountUsd: 20, source: 'patreon' },
  { name: 'Q (She/They)', amountUsd: 15, source: 'revolut' },
  { name: 'whoelsebutbino', amountUsd: 10, source: 'patreon' },
];

function summary(name) {
  const s = {
    ok: true,
    period: new Date().toISOString().slice(0, 7),
    monthlyRaisedUsd: 490,
    totalRaisedUsd: 1830,
    monthlyCostUsd: 400,
    percentCovered: 123,          // >100 exercises the "funded" branch
    patrons: { count: 12, monthlyUsd: 240 },
    monthly: LEADERBOARD,
    allTime: LEADERBOARD,
  };
  // The mod only sends ?name= when network consent is granted; mirror the real relay and only
  // include `you` when it does, so both states are reachable in testing.
  if (name) s.you = { monthlyUsd: 25, totalUsd: 85 };
  return s;
}

function json(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

http.createServer((req, res) => {
  const t = new Date().toISOString().slice(11, 19);

  if (req.url === `/${cap}/backers/pool`) {
    const pool = mode === 'empty' ? EMPTY : POPULATED;
    console.log(`[${t}] SERVED backers/pool  mode=${mode}  names=${pool.names.length} phrases=${pool.phrases.length}`);
    return json(res, 200, pool);
  }

  // Official links. `payment_cn` is what gates the China payment route: PaymentLinks.useChinaPayment()
  // returns false when it is absent, so WITHOUT this endpoint a Chinese client silently falls back to
  // Patreon and the route can't be tested at all.
  if (req.url === `/${cap}/links`) {
    console.log(`[${t}] SERVED links (payment_cn present)`);
    return json(res, 200, {
      ok: true,
      links: {
        discord: 'https://discord.gg/dungeontrain',
        patreon: 'https://www.patreon.com/brennanhatton',
        payment: 'https://revolut.me/brennanhatton?note=',
        affiliate: 'https://example.invalid/affiliate',
        // The real deployed value, stored WITHOUT ?locale= so the mod appends a per-client one.
        payment_cn: 'https://buy.stripe.com/00w5kDgTL9M31TDc6d8N202',
      },
    });
  }

  // The death screen's "support the line" ledger. Both paths, matching the real relay.
  const sum = /^\/([^/]+)\/(?:backers|donations)\/summary(?:\?(.*))?$/.exec(req.url || '');
  if (sum && sum[1] === cap) {
    const q = new URLSearchParams(sum[2] || '');
    const who = q.get('name');
    console.log(`[${t}] SERVED summary  name=${who || '(none — no consent)'}`);
    return json(res, 200, summary(who));
  }

  const m = /^\/mode\/(populated|empty)$/.exec(req.url || '');
  if (m) {
    mode = m[1];
    console.log(`[${t}] MODE -> ${mode}   (rejoin the world to re-fetch)`);
    return json(res, 200, { ok: true, mode });
  }

  if (req.url === '/mode') return json(res, 200, { ok: true, mode });

  // Everything else: DT's other fetchers, which degrade silently.
  return json(res, 404, { error: 'not_found' });
}).listen(port, '127.0.0.1', () => {
  console.log(`[backer-pool-test-relay] http://127.0.0.1:${port}/${cap}/backers/pool  mode=${mode}`);
  console.log(`[backer-pool-test-relay] toggle: http://127.0.0.1:${port}/mode/empty | /mode/populated`);
});
