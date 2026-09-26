import assert from "node:assert/strict";
import test from "node:test";
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import worker, {
  normalizePhoneNumberForReport,
  stripNationalTrunkPrefix,
  isPlausibleReportNumber,
  normalizeSmsDomain,
  sanitizeSmsDomains,
  sanitizeSmsReportFields,
  sanitizeSmsUrlIndicators,
  checkRateLimit,
  clientKey,
  getClientIp,
  checkDedup,
  deriveReporterBucket,
  deriveReporterDevice,
  recordDedup,
  validateReportEnvironment,
  validatedReportId,
} from "./community-reports-worker.js";

const FIXTURES = JSON.parse(
  readFileSync(fileURLToPath(new URL("../scripts/normalizer_fixtures.json", import.meta.url)), "utf8"),
);

test("agrees with the Kotlin and Python normalizers on the shared fixture table", () => {
  // scripts/normalizer_fixtures.json is the single truth table for all three
  // implementations. If this fails, the worker has drifted from the app and/or
  // the merge pipeline and reports will land on a different database key.
  for (const { input, why, expected } of FIXTURES.cases) {
    assert.equal(normalizePhoneNumberForReport(input), expected.worker, `${JSON.stringify(input)}: ${why}`);
  }
});

test("strips national trunk prefixes typed into international numbers", () => {
  // Issue #6: "+86 0558 646 8536" was stored as +8605586468536, which
  // formatNumberToE164 never produces, so the row could never match a call.
  assert.equal(stripNationalTrunkPrefix("8605586468536"), "865586468536"); // China
  assert.equal(stripNationalTrunkPrefix("4402071234567"), "442071234567"); // UK
  assert.equal(stripNationalTrunkPrefix("49030123456"), "4930123456"); // Germany
  assert.equal(stripNationalTrunkPrefix("390612345678"), "390612345678"); // Italy keeps its 0
  assert.equal(stripNationalTrunkPrefix("2250707123456"), "2250707123456"); // Cote d'Ivoire keeps its 0
  assert.equal(stripNationalTrunkPrefix("865586468536"), "865586468536"); // already E.164
  assert.equal(stripNationalTrunkPrefix("12122345678"), "12122345678"); // NANP untouched
});

test("normalizes ASCII phone numbers for reports", () => {
  assert.equal(normalizePhoneNumberForReport("+1 (212) 555-1234"), "+12125551234");
  assert.equal(normalizePhoneNumberForReport("212-555-1234"), "+12125551234");
  assert.equal(normalizePhoneNumberForReport("+442071234567"), "+442071234567");
});

test("never NANP-ifies an explicitly international number totalling 10 digits", () => {
  // +45 Denmark and +47 Norway are 8-digit national numbers: cc + national =
  // exactly 10 digits. The bare-10-digit -> "+1" heuristic must not apply,
  // or the worker fabricates a valid-looking US number owned by a stranger.
  assert.equal(normalizePhoneNumberForReport("+4536963010"), "+4536963010");
  assert.equal(normalizePhoneNumberForReport("+47 21 93 01 00"), "+4721930100");
  assert.equal(normalizePhoneNumberForReport("‎" + "+45 36 96 30 10"), "+4536963010");
  // Bare 10-digit input (no "+") is still assumed NANP-local.
  assert.equal(normalizePhoneNumberForReport("2125551234"), "+12125551234");
});

test("strips formatting control marks before report normalization", () => {
  assert.equal(normalizePhoneNumberForReport("\u200E+\u200F1 212\u200B-555\u200E-1234"), "+12125551234");
});

test("rejects Unicode digit spoofing and overlong report numbers", () => {
  assert.equal(normalizePhoneNumberForReport("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660"), null);
  assert.equal(normalizePhoneNumberForReport("\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10"), null);
  assert.equal(normalizePhoneNumberForReport("+1234567890123456"), null);
});

test("plausibility gate rejects fictional and malformed report numbers", () => {
  assert.equal(isPlausibleReportNumber("+12122345678"), true); // valid NANP
  assert.equal(isPlausibleReportNumber("+442071234567"), true); // valid UK
  assert.equal(isPlausibleReportNumber("+15551234567"), false); // NANP area code 555
  assert.equal(isPlausibleReportNumber("+12125550101"), false); // 555 exchange (fiction)
  assert.equal(isPlausibleReportNumber("+12119345678"), false); // N11 area code (211)
  assert.equal(isPlausibleReportNumber("+01145884697"), false); // leading-zero country code
  assert.equal(isPlausibleReportNumber("+1234567"), false); // too short
});

test("sanitizes SMS domain indicators without URL paths", () => {
  assert.equal(normalizeSmsDomain("HTTPS://Bad.Example/path?token=secret"), "bad.example");
  assert.equal(normalizeSmsDomain("bad.example/path/to/account"), "bad.example");
  assert.equal(normalizeSmsDomain("-bad.example"), null);
  assert.equal(normalizeSmsDomain("bad..example"), null);
});

test("deduplicates and limits SMS domain indicators", () => {
  const domains = sanitizeSmsDomains([
    "Bad.Example",
    "bad.example",
    "ok.example",
    "invalid",
    "x".repeat(254) + ".example",
  ]);
  assert.deepEqual(domains, ["bad.example", "ok.example"]);
});

test("sanitizes SMS URL indicator labels", () => {
  assert.deepEqual(
    sanitizeSmsUrlIndicators(["URL_PRESENT", "shortener", "shortener", "bad-path/secret", "x"]),
    ["url_present", "shortener"],
  );
});

test("drops raw SMS body fields from sanitized report fields", () => {
  const fields = sanitizeSmsReportFields({
    sms_body: "Your package is held. Visit https://bad.example/private",
    body: "raw message body",
    sms_domains: ["bad.example"],
    sms_url_indicators: ["url_present"],
  });
  assert.deepEqual(fields, {
    sms_domains: ["bad.example"],
    sms_url_indicators: ["url_present"],
  });
  assert.equal("sms_body" in fields, false);
  assert.equal("body" in fields, false);
});

// ── Rate-limit / dedup tests ──────────────────────────────────────────

/** In-memory KV stub for testing. */
function createMockKV() {
  const store = new Map();
  return {
    async get(key) {
      const entry = store.get(key);
      if (!entry) return null;
      if (entry.expiresAt && Date.now() >= entry.expiresAt) {
        store.delete(key);
        return null;
      }
      return entry.value;
    },
    async put(key, value, opts) {
      const expiresAt = opts?.expirationTtl
        ? Date.now() + opts.expirationTtl * 1000
        : undefined;
      store.set(key, { value, expiresAt });
    },
    _store: store,
  };
}

test("rate limiter allows requests within burst window", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  for (let i = 0; i < 5; i++) {
    const rl = await checkRateLimit("1.2.3.4", env);
    assert.equal(rl.allowed, true, `request ${i + 1} should be allowed`);
  }
  // 6th request should be blocked
  const rl = await checkRateLimit("1.2.3.4", env);
  assert.equal(rl.allowed, false);
  assert.ok(rl.retryAfter > 0);
});

test("rate limiter isolates different IPs", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  for (let i = 0; i < 5; i++) {
    await checkRateLimit("10.0.0.1", env);
  }
  // 10.0.0.1 is exhausted
  const blocked = await checkRateLimit("10.0.0.1", env);
  assert.equal(blocked.allowed, false);

  // 10.0.0.2 should still have its full quota
  const allowed = await checkRateLimit("10.0.0.2", env);
  assert.equal(allowed.allowed, true);
  assert.equal(kv._store.has("rl:unknown"), false);
});

test("missing client identity is rejected instead of entering a shared bucket", async () => {
  const kv = createMockKV();
  assert.equal(getClientIp(new Request("https://reports.example")), null);

  const rl = await checkRateLimit("", { RATE_LIMIT: kv });
  assert.equal(rl.allowed, false);
  assert.equal(rl.identityError, true);
  assert.equal(kv._store.size, 0);
});

test("corrupt rate-limit state is logged, reset, and surfaced as a state failure", async () => {
  const kv = createMockKV();
  kv._store.set("rl:203.0.113.40", { value: "{not-json" });
  const errors = [];
  const originalError = console.error;
  console.error = (...args) => errors.push(args.join(" "));
  try {
    const rl = await checkRateLimit("203.0.113.40", { RATE_LIMIT: kv });
    assert.equal(rl.allowed, false);
    assert.equal(rl.stateError, true);
  } finally {
    console.error = originalError;
  }
  assert.equal(errors.length > 0, true);
  assert.equal(JSON.parse(kv._store.get("rl:203.0.113.40").value).count, 1);
});

test("atomic limiter binding is authoritative over the KV counter", async () => {
  const kv = createMockKV();
  const calls = [];
  const env = {
    RATE_LIMIT: kv,
    REPORT_LIMITER: {
      async limit({ key }) {
        calls.push(key);
        return { success: calls.length <= 5 };
      },
    },
  };

  for (let i = 0; i < 5; i++) {
    const rl = await checkRateLimit("1.2.3.4", env);
    assert.equal(rl.allowed, true, `request ${i + 1} should be allowed`);
  }
  const blocked = await checkRateLimit("1.2.3.4", env);
  assert.equal(blocked.allowed, false);
  assert.ok(blocked.retryAfter > 0);
  assert.deepEqual(calls, Array(6).fill("1.2.3.4"));
  // The non-atomic KV counter must not be consulted while the limiter is bound.
  assert.equal(kv._store.has("rl:1.2.3.4"), false);
});

test("atomic limiter failure fails closed as a state error", async () => {
  const errors = [];
  const originalError = console.error;
  console.error = (...args) => errors.push(args.join(" "));
  try {
    const rl = await checkRateLimit("1.2.3.4", {
      RATE_LIMIT: createMockKV(),
      REPORT_LIMITER: {
        async limit() {
          throw new Error("limiter unavailable");
        },
      },
    });
    assert.equal(rl.allowed, false);
    assert.equal(rl.stateError, true);
  } finally {
    console.error = originalError;
  }
  assert.equal(errors.length > 0, true);
});

test("POST returns 429 when the atomic limiter refuses the request", async () => {
  const response = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.50",
      },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    {
      RATE_LIMIT: createMockKV(),
      REPORT_LIMITER: { async limit() { return { success: false }; } },
      GITHUB_TOKEN: "test-token",
      REPORTER_BUCKET_SECRET: "s".repeat(32),
    },
  );
  assert.equal(response.status, 429);
  assert.ok(Number(response.headers.get("retry-after")) > 0);
});

test("one IPv6 /48 has a budget of its own across its /64s", async () => {
  const env = { RATE_LIMIT: createMockKV() };
  for (let i = 1; i <= 20; i++) {
    const rl = await checkRateLimit(`2001:db8:7:${i.toString(16)}::1`, env);
    assert.equal(rl.allowed, true, `/64 number ${i}`);
  }
  const refused = await checkRateLimit("2001:db8:7:ff::1", env);
  assert.equal(refused.allowed, false);
  assert.ok(refused.retryAfter > 0);
  // Another /48 has its own budget.
  assert.equal((await checkRateLimit("2001:db8:8:1::1", env)).allowed, true);
});

test("the Worker has one per-minute budget across every network", async () => {
  const env = { RATE_LIMIT: createMockKV() };
  for (let i = 1; i <= 30; i++) {
    assert.equal((await checkRateLimit(`198.51.100.${i}`, env)).allowed, true, `client ${i}`);
  }
  const refused = await checkRateLimit("203.0.113.200", env);
  assert.equal(refused.allowed, false);
  assert.ok(refused.retryAfter > 0);
});

test("POST returns 429 when the /48 or the Worker-wide limiter refuses", async () => {
  for (const refusing of ["REPORT_GROUP_LIMITER", "REPORT_GLOBAL_LIMITER"]) {
    const keys = { REPORT_LIMITER: [], REPORT_GROUP_LIMITER: [], REPORT_GLOBAL_LIMITER: [] };
    const limiter = (name) => ({
      async limit({ key }) {
        keys[name].push(key);
        return { success: name !== refusing };
      },
    });
    const response = await worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": "2001:db8:7:1::1" },
        body: JSON.stringify({ number: "+12122340101", type: "spam" }),
      }),
      {
        RATE_LIMIT: createMockKV(),
        REPORT_LIMITER: limiter("REPORT_LIMITER"),
        REPORT_GROUP_LIMITER: limiter("REPORT_GROUP_LIMITER"),
        REPORT_GLOBAL_LIMITER: limiter("REPORT_GLOBAL_LIMITER"),
        GITHUB_TOKEN: "test-token",
        REPORTER_BUCKET_SECRET: "s".repeat(32),
      },
    );
    assert.equal(response.status, 429, refusing);
    assert.ok(Number(response.headers.get("retry-after")) > 0, refusing);
    assert.deepEqual(keys.REPORT_LIMITER, [clientKey("2001:db8:7:1::1", 64)]);
    assert.deepEqual(keys.REPORT_GROUP_LIMITER, [clientKey("2001:db8:7:1::1", 48)]);
  }
});

test("an oversized body without a Content-Length is refused before it is all read", async () => {
  let pulled = 0;
  const chunk = new TextEncoder().encode(" ".repeat(4096));
  const body = new ReadableStream({
    pull(controller) {
      pulled += 1;
      if (pulled > 100) controller.close();
      else controller.enqueue(chunk);
    },
  });
  const request = new Request("https://reports.example", {
    method: "POST",
    headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.60" },
    body,
    duplex: "half",
  });
  assert.equal(request.headers.get("content-length"), null);
  const response = await worker.fetch(request, {
    RATE_LIMIT: createMockKV(),
    GITHUB_TOKEN: "test-token",
    REPORTER_BUCKET_SECRET: "s".repeat(32),
  });
  assert.equal(response.status, 413);
  assert.ok(pulled < 10, `read ${pulled} of 100 chunks`);
});

test("rate limiter fails closed when KV is not bound", async () => {
  const rl = await checkRateLimit("1.2.3.4", {});
  assert.equal(rl.allowed, false);
  assert.equal(rl.configurationError, true);

  const rl2 = await checkRateLimit("1.2.3.4", null);
  assert.equal(rl2.allowed, false);
  assert.equal(rl2.configurationError, true);

  const localOnly = await checkRateLimit("1.2.3.4", { ALLOW_UNLIMITED_REPORTS: "true" });
  assert.equal(localOnly.allowed, true);
});

test("dedup rejects same IP + number only after the report is recorded", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  const first = await checkDedup("1.2.3.4", "+12125551234", "spam", env);
  assert.equal(first, false, "first report should not be a duplicate");

  // checkDedup is read-only: until recordDedup runs (i.e. the GitHub PUT
  // succeeded), a retry after a failed store must NOT be treated as a dupe.
  const retryAfterFailedStore = await checkDedup("1.2.3.4", "+12125551234", "spam", env);
  assert.equal(retryAfterFailedStore, false, "unrecorded report must be retryable");

  await recordDedup("1.2.3.4", "+12125551234", "spam", env);
  const second = await checkDedup("1.2.3.4", "+12125551234", "spam", env);
  assert.equal(second, true, "same IP + number should be a duplicate once recorded");
});

test("dedup allows same number from different IP", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await recordDedup("1.2.3.4", "+12125551234", "spam", env);
  const result = await checkDedup("5.6.7.8", "+12125551234", "spam", env);
  assert.equal(result, false, "different IP should not be a duplicate");
});

test("dedup allows same IP for different numbers", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await recordDedup("1.2.3.4", "+12125551234", "spam", env);
  const result = await checkDedup("1.2.3.4", "+14155551234", "spam", env);
  assert.equal(result, false, "different number should not be a duplicate");
});

test("a corrective not_spam report is not a duplicate of the report it corrects", async () => {
  const env = { RATE_LIMIT: createMockKV() };

  await recordDedup("1.2.3.4", "+12125551234", "spam", env);

  assert.equal(await checkDedup("1.2.3.4", "+12125551234", "spam", env), true);
  assert.equal(await checkDedup("1.2.3.4", "+12125551234", "not_spam", env), false);
});

test("dedup covers every address in one IPv6 /64 and no other /64", async () => {
  const env = { RATE_LIMIT: createMockKV() };

  await recordDedup("2001:db8:1:2::a", "+12125551234", "spam", env);

  assert.equal(await checkDedup("2001:db8:1:2:ffff::b", "+12125551234", "spam", env), true);
  assert.equal(await checkDedup("2001:db8:1:3::a", "+12125551234", "spam", env), false);
});

test("dedup only permits missing KV behind the explicit local flag", async () => {
  const localEnv = { ALLOW_UNLIMITED_REPORTS: "true" };
  await recordDedup("1.2.3.4", "+12125551234", "spam", localEnv);
  const result = await checkDedup("1.2.3.4", "+12125551234", "spam", localEnv);
  assert.equal(result, false);
});

test("dedup fails closed when KV is not bound", async () => {
  await assert.rejects(checkDedup("1.2.3.4", "+12125551234", "spam", {}), /RATE_LIMIT/);
  await assert.rejects(recordDedup("1.2.3.4", "+12125551234", "spam", {}), /RATE_LIMIT/);
});

// ── IPv6 prefix keying ────────────────────────────────────────────────
// One IPv6 subscriber holds a /64 at least, so a full-address key let one
// line act as endless distinct clients and "reporters".

test("an IPv6 client is keyed by its /64 and bucketed by its /48", () => {
  assert.equal(clientKey("2001:db8:abcd:12::1", 64), clientKey("2001:0DB8:abcd:0012:ffff:ffff:ffff:ffff", 64));
  assert.notEqual(clientKey("2001:db8:abcd:12::1", 64), clientKey("2001:db8:abcd:13::1", 64));
  assert.equal(clientKey("2001:db8:abcd:12::1", 48), clientKey("2001:db8:abcd:ff00::1", 48));
  assert.notEqual(clientKey("2001:db8:abcd::1", 48), clientKey("2001:db8:abce::1", 48));
  assert.equal(clientKey("2001:db8::", 64), clientKey("2001:0db8:0000:0000:0000:0000:0000:0000", 64));
  assert.equal(clientKey("2001:db8:abcd:12::1", 64), "2001:0db8:abcd:0012::/64");
});

test("IPv4 keys are unchanged, including an IPv4 address in IPv6-mapped form", () => {
  assert.equal(clientKey("203.0.113.7", 64), "203.0.113.7");
  assert.equal(clientKey(" 203.0.113.7 ", 48), "203.0.113.7");
  assert.equal(clientKey("::ffff:203.0.113.7", 64), "203.0.113.7");
  assert.equal(clientKey("::ffff:cb00:7107", 64), "203.0.113.7");
});

test("two addresses in one IPv6 /64 share a rate-limit key", async () => {
  const calls = [];
  const limiterEnv = {
    REPORT_LIMITER: {
      async limit({ key }) {
        calls.push(key);
        return { success: true };
      },
    },
  };
  await checkRateLimit("2001:db8:1:2::a", limiterEnv);
  await checkRateLimit("2001:db8:1:2:ffff:ffff:ffff:b", limiterEnv);
  assert.equal(calls.length, 2);
  assert.equal(calls[0], calls[1]);

  // The KV fallback counts them together too.
  const kvEnv = { RATE_LIMIT: createMockKV() };
  for (let i = 1; i <= 5; i++) {
    assert.equal((await checkRateLimit(`2001:db8:1:2::${i}`, kvEnv)).allowed, true);
  }
  assert.equal((await checkRateLimit("2001:db8:1:2::99", kvEnv)).allowed, false);
  assert.equal((await checkRateLimit("2001:db8:1:3::1", kvEnv)).allowed, true);
});

test("two /64s in one /48 share a reporter bucket and another /48 does not", async () => {
  const secret = "s".repeat(32);
  const at = "2026-08-01T08:00:00Z";
  const first = await deriveReporterBucket("2001:db8:abcd:1::1", at, secret);
  const second = await deriveReporterBucket("2001:db8:abcd:2::1", at, secret);
  const elsewhere = await deriveReporterBucket("2001:db8:abce:1::1", at, secret);

  assert.equal(first, second);
  assert.notEqual(first, elsewhere);
});

test("devices on one carrier /48 get their own device bucket but share the group", async () => {
  const secret = "s".repeat(32);
  const at = "2026-08-01T08:00:00Z";
  const phone = await deriveReporterDevice("2001:db8:abcd:1::1", at, secret);
  const samePhone = await deriveReporterDevice("2001:db8:abcd:1::ff", at, secret);
  const otherPhone = await deriveReporterDevice("2001:db8:abcd:2::1", at, secret);

  assert.match(phone, /^[a-f0-9]{16}$/);
  assert.equal(phone, samePhone);
  assert.notEqual(phone, otherPhone);
  assert.equal(
    await deriveReporterBucket("2001:db8:abcd:1::1", at, secret),
    await deriveReporterBucket("2001:db8:abcd:2::1", at, secret),
  );
});

test("a device bucket never equals a group bucket, even for an IPv4 address", async () => {
  const secret = "s".repeat(32);
  const at = "2026-08-01T08:00:00Z";
  assert.notEqual(await deriveReporterDevice("203.0.113.7", at, secret), await deriveReporterBucket("203.0.113.7", at, secret));
  assert.notEqual(
    await deriveReporterDevice("2001:db8:abcd:1::1", at, secret),
    await deriveReporterBucket("2001:db8:abcd:1::1", at, secret),
  );
  assert.notEqual(
    await deriveReporterDevice("203.0.113.7", at, secret),
    await deriveReporterDevice("203.0.113.7", "2026-08-02T08:00:00Z", secret),
  );
});

test("an IPv4 reporter bucket is the one it has always been", async () => {
  // A deploy that changed IPv4 buckets would split one day's reporters in two.
  const secret = "s".repeat(32);
  const expected = createHmac("sha256", secret).update("v1:2026-08-01:203.0.113.7").digest("hex").slice(0, 16);

  assert.equal(await deriveReporterBucket("203.0.113.7", "2026-08-01T08:00:00Z", secret), expected);
});

test("report environment requires every production abuse-control binding", () => {
  const missing = validateReportEnvironment({});
  assert.equal(missing.ready, false);
  assert.deepEqual(missing.missing, ["RATE_LIMIT", "GITHUB_TOKEN", "REPORTER_BUCKET_SECRET"]);

  const ready = validateReportEnvironment({
    RATE_LIMIT: createMockKV(),
    GITHUB_TOKEN: "test-token",
    REPORTER_BUCKET_SECRET: "s".repeat(32),
  });
  assert.deepEqual(ready, { ready: true, missing: [] });
});

test("reporter buckets are stable within a day and rotate across days", async () => {
  const secret = "s".repeat(32);
  const morning = await deriveReporterBucket("203.0.113.7", "2026-08-01T08:00:00Z", secret);
  const evening = await deriveReporterBucket("203.0.113.7", "2026-08-01T22:00:00Z", secret);
  const nextDay = await deriveReporterBucket("203.0.113.7", "2026-08-02T08:00:00Z", secret);
  const otherReporter = await deriveReporterBucket("203.0.113.8", "2026-08-01T08:00:00Z", secret);

  assert.match(morning, /^[a-f0-9]{16}$/);
  assert.equal(morning, evening);
  assert.notEqual(morning, nextDay);
  assert.notEqual(morning, otherReporter);
});

test("POST rejects browser and non-JSON submissions before touching storage", async () => {
  const env = {
    RATE_LIMIT: createMockKV(),
    GITHUB_TOKEN: "test-token",
    REPORTER_BUCKET_SECRET: "s".repeat(32),
  };
  const browserResponse = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: { "content-type": "application/json", origin: "https://attacker.example" },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    env,
  );
  assert.equal(browserResponse.status, 403);
  assert.equal(browserResponse.headers.get("access-control-allow-origin"), null);

  const textResponse = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    env,
  );
  assert.equal(textResponse.status, 415);
});

test("POST fails closed when production bindings are missing", async () => {
  const response = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    {},
  );
  assert.equal(response.status, 503);
});

test("POST rejects missing client identity before touching KV", async () => {
  const kv = createMockKV();
  const response = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    {
      RATE_LIMIT: kv,
      GITHUB_TOKEN: "test-token",
      REPORTER_BUCKET_SECRET: "s".repeat(32),
    },
  );
  assert.equal(response.status, 400);
  assert.equal(kv._store.size, 0);
});

test("POST returns 503 for corrupt KV state and 400 for malformed JSON", async () => {
  const kv = createMockKV();
  kv._store.set("rl:203.0.113.41", { value: "not-json" });
  const env = {
    RATE_LIMIT: kv,
    GITHUB_TOKEN: "test-token",
    REPORTER_BUCKET_SECRET: "s".repeat(32),
  };
  const stateFailure = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.41",
      },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    env,
  );
  assert.equal(stateFailure.status, 503);

  const malformedRequest = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.42",
      },
      body: "{not-json",
    }),
    env,
  );
  assert.equal(malformedRequest.status, 400);
});

test("a dedup-write failure after the report is stored still returns success", async () => {
  // The report is already committed, so a 500 would make the app submit it again.
  const kv = createMockKV();
  const put = kv.put.bind(kv);
  kv.put = async (key, value, options) => {
    if (key.startsWith("dedup:")) throw new Error("KV write failed");
    return put(key, value, options);
  };
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  let commits = 0;
  const errors = [];
  globalThis.fetch = async () => {
    commits += 1;
    return new Response("{}", { status: 201 });
  };
  console.error = (...args) => errors.push(args.join(" "));
  try {
    const response = await worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.60" },
        body: JSON.stringify({ number: "+12122340101", type: "spam" }),
      }),
      { RATE_LIMIT: kv, GITHUB_TOKEN: "test-token", REPORTER_BUCKET_SECRET: "s".repeat(32) },
    );
    assert.equal(response.status, 200);
    assert.equal(commits, 1);
    assert.equal(errors.some((line) => line.includes("dedup marker")), true);
  } finally {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  }
});

test("the landing page example is a number the plausibility check accepts", async () => {
  // A copied 555 example is refused as invalid, and the fictional 555-01XX
  // range is refused with every other 555 exchange.
  const page = await (await worker.fetch(new Request("https://reports.example"), {})).text();
  const example = page.match(/"number":"(\+\d+)"/)?.[1];

  assert.ok(example, "the landing page shows an example request");
  assert.equal(isPlausibleReportNumber(normalizePhoneNumberForReport(example)), true);
});

test("stored reports carry only a daily reporter bucket, never an IP", async () => {
  const originalFetch = globalThis.fetch;
  let githubPayload;
  globalThis.fetch = async (_url, options) => {
    githubPayload = JSON.parse(options.body);
    return new Response("{}", { status: 201 });
  };
  try {
    const response = await worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "cf-connecting-ip": "203.0.113.7",
        },
        body: JSON.stringify({ number: "+12122340101", type: "spam" }),
      }),
      {
        RATE_LIMIT: createMockKV(),
        GITHUB_TOKEN: "test-token",
        REPORTER_BUCKET_SECRET: "s".repeat(32),
      },
    );
    assert.equal(response.status, 200);
    const report = JSON.parse(atob(githubPayload.content));
    assert.match(report.reporter_bucket, /^[a-f0-9]{16}$/);
    assert.match(report.reporter_device, /^[a-f0-9]{16}$/);
    assert.equal(JSON.stringify(report).includes("203.0.113.7"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("report ids are checked and lowercased", () => {
  assert.equal(validatedReportId("3F1C9A52-7D4E-4B8A-9C1D-2E5F6A7B8C9D"), "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d");
  assert.equal(validatedReportId("not-an-id"), "");
  assert.equal(validatedReportId(42), "");
});

test("a report resent under its id from another network is a duplicate", async () => {
  const env = { RATE_LIMIT: createMockKV() };
  const id = "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d";

  await recordDedup("2001:db8:1:2::a", "+12122340101", "spam", env, id);

  // Wi-Fi to cellular is another /64, so only the id can recognise the resend.
  assert.equal(await checkDedup("198.51.100.9", "+12122340101", "spam", env, id), true);
  assert.equal(await checkDedup("198.51.100.9", "+12122340101", "spam", env, "0b1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"), false);
  assert.equal(await checkDedup("198.51.100.9", "+12122340101", "spam", env), false);
});

/** POSTs one report through the whole handler, with GitHub played by `answer`. */
async function postReports(requests, answer) {
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  const stored = [];
  globalThis.fetch = async (_url, options) => {
    const status = answer(stored.length);
    if (status === 201) stored.push(JSON.parse(atob(JSON.parse(options.body).content)));
    return new Response("{}", { status });
  };
  console.error = () => {};
  const env = { RATE_LIMIT: createMockKV(), GITHUB_TOKEN: "test-token", REPORTER_BUCKET_SECRET: "s".repeat(32) };
  try {
    const responses = [];
    for (const { ip, body } of requests) {
      const response = await worker.fetch(
        new Request("https://reports.example", {
          method: "POST",
          headers: { "content-type": "application/json", "cf-connecting-ip": ip },
          body: JSON.stringify(body),
        }),
        env,
      );
      responses.push({ status: response.status, body: await response.json() });
    }
    return { responses, stored };
  } finally {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  }
}

test("a report whose store failed is stored when it's sent again", async () => {
  // The markers go in only after the PUT. Written any earlier, the resend would
  // be answered "already stored" and the app would drop a report GitHub never took.
  const report = { number: "+12122340101", type: "spam", report_id: "5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f" };
  let calls = 0;
  const { responses, stored } = await postReports(
    [
      { ip: "203.0.113.70", body: report },
      { ip: "203.0.113.70", body: report },
    ],
    () => (calls++ === 0 ? 500 : 201),
  );

  assert.equal(responses[0].status, 500);
  assert.equal(responses[1].status, 200);
  assert.equal(stored.length, 1);
  assert.equal(stored[0].report_id, report.report_id);
});

test("a second report of a number from a shared address isn't answered as already stored", async () => {
  // A household or carrier NAT shares one client key. Only the report's own id
  // may say it's stored; anything else is a plain "try later", which the app retries.
  const report = (id) => ({ number: "+12122340101", type: "spam", report_id: id });
  const { responses, stored } = await postReports(
    [
      { ip: "203.0.113.80", body: report("6d2e3f4a-5b6c-4d7e-8f9a-0b1c2d3e4f5a") },
      { ip: "203.0.113.80", body: report("7e3f4a5b-6c7d-4e8f-9a0b-1c2d3e4f5a6b") },
    ],
    () => 201,
  );

  assert.equal(responses[0].status, 200);
  assert.equal(responses[1].status, 429);
  assert.equal(responses[1].body.already_stored, undefined);
  assert.equal(stored.length, 1);
});

test("a stored report keeps its id, and its resend is answered as already stored", async () => {
  const originalFetch = globalThis.fetch;
  const payloads = [];
  globalThis.fetch = async (_url, options) => {
    payloads.push(JSON.parse(options.body));
    return new Response("{}", { status: 201 });
  };
  const env = { RATE_LIMIT: createMockKV(), GITHUB_TOKEN: "test-token", REPORTER_BUCKET_SECRET: "s".repeat(32) };
  const send = (ip) =>
    worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": ip },
        body: JSON.stringify({ number: "+12122340101", type: "spam", report_id: "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d" }),
      }),
      env,
    );
  try {
    const first = await send("203.0.113.7");
    const resend = await send("198.51.100.9");

    assert.equal(first.status, 200);
    assert.equal(JSON.parse(atob(payloads[0].content)).report_id, "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d");
    assert.equal(resend.status, 429);
    assert.equal((await resend.json()).already_stored, true);
    assert.equal(payloads.length, 1, "the resend is not stored again");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
