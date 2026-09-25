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
  commitReports,
  deriveReporterBucket,
  deriveReporterDevice,
  flushQueuedReports,
  nextFlushTime,
  recordDedup,
  ReportQueue,
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

/** In-memory Durable Object storage covering the calls ReportQueue makes. */
function createMockStorage() {
  const data = new Map();
  let alarm = null;
  return {
    data,
    get alarm() {
      return alarm;
    },
    async put(key, value) {
      data.set(key, value);
    },
    async list({ prefix = "", limit = Infinity } = {}) {
      const keys = [...data.keys()].filter((key) => key.startsWith(prefix)).sort().slice(0, limit);
      return new Map(keys.map((key) => [key, data.get(key)]));
    },
    async delete(keys) {
      if (keys.length > 128) throw new Error("Durable Object storage deletes at most 128 keys per call");
      for (const key of keys) data.delete(key);
      return keys.length;
    },
    async getAlarm() {
      return alarm;
    },
    async setAlarm(time) {
      alarm = time;
    },
  };
}

/** A REPORT_QUEUE binding whose one instance is a real ReportQueue over mock storage. */
function createMockQueue(env = { GITHUB_TOKEN: "test-token" }) {
  const storage = createMockStorage();
  const instance = new ReportQueue({ storage }, env);
  return {
    storage,
    instance,
    idFromName: (name) => name,
    get: () => ({ fetch: (url, init) => instance.fetch(new Request(url, init)) }),
  };
}

/** The reports a mock queue is holding, oldest key first. */
function queuedReports(queue) {
  return [...queue.storage.data.values()].map((content) => JSON.parse(content));
}

/** Bindings that pass validateReportEnvironment. */
function readyEnv(overrides = {}) {
  return {
    RATE_LIMIT: createMockKV(),
    GITHUB_TOKEN: "test-token",
    REPORTER_BUCKET_SECRET: "s".repeat(32),
    REPORT_QUEUE: createMockQueue(),
    ...overrides,
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
    readyEnv({ REPORT_LIMITER: { async limit() { return { success: false }; } } }),
  );
  assert.equal(response.status, 429);
  assert.ok(Number(response.headers.get("retry-after")) > 0);
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
  assert.deepEqual(missing.missing, ["RATE_LIMIT", "GITHUB_TOKEN", "REPORTER_BUCKET_SECRET", "REPORT_QUEUE"]);

  assert.deepEqual(validateReportEnvironment(readyEnv()), { ready: true, missing: [] });
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
    readyEnv({ RATE_LIMIT: kv }),
  );
  assert.equal(response.status, 400);
  assert.equal(kv._store.size, 0);
});

test("POST returns 503 for corrupt KV state and 400 for malformed JSON", async () => {
  const kv = createMockKV();
  kv._store.set("rl:203.0.113.41", { value: "not-json" });
  const env = readyEnv({ RATE_LIMIT: kv });
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
  // The report is already queued, so a 500 would make the app submit it again.
  const kv = createMockKV();
  const put = kv.put.bind(kv);
  kv.put = async (key, value, options) => {
    if (key.startsWith("dedup:")) throw new Error("KV write failed");
    return put(key, value, options);
  };
  const queue = createMockQueue();
  const originalError = console.error;
  const errors = [];
  console.error = (...args) => errors.push(args.join(" "));
  try {
    const response = await worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.60" },
        body: JSON.stringify({ number: "+12122340101", type: "spam" }),
      }),
      readyEnv({ RATE_LIMIT: kv, REPORT_QUEUE: queue }),
    );
    assert.equal(response.status, 200);
    assert.equal(queuedReports(queue).length, 1);
    assert.equal(errors.some((line) => line.includes("dedup marker")), true);
  } finally {
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
  const queue = createMockQueue();
  const response = await worker.fetch(
    new Request("https://reports.example", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": "203.0.113.7",
      },
      body: JSON.stringify({ number: "+12122340101", type: "spam" }),
    }),
    readyEnv({ REPORT_QUEUE: queue }),
  );
  assert.equal(response.status, 200);
  const [report] = queuedReports(queue);
  assert.match(report.reporter_bucket, /^[a-f0-9]{16}$/);
  assert.match(report.reporter_device, /^[a-f0-9]{16}$/);
  assert.equal([...queue.storage.data.values()].join("").includes("203.0.113.7"), false);
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

/** POSTs reports through the whole handler; `answer` says whether each queue write works (201) or fails. */
async function postReports(requests, answer) {
  const originalError = console.error;
  const queue = createMockQueue();
  let attempts = 0;
  const flakyQueue = {
    ...queue,
    get: (id) => ({
      fetch: async (url, init) =>
        answer(attempts++) === 201 ? queue.get(id).fetch(url, init) : new Response("queue failed", { status: 500 }),
    }),
  };
  console.error = () => {};
  const env = readyEnv({ REPORT_QUEUE: flakyQueue });
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
    return { responses, stored: queuedReports(queue) };
  } finally {
    console.error = originalError;
  }
}

test("a report whose store failed is stored when it's sent again", async () => {
  // The markers go in only after the report is queued. Written any earlier, the
  // resend would be answered "already stored" and the app would drop a report
  // the queue never took.
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
  const queue = createMockQueue();
  const env = readyEnv({ REPORT_QUEUE: queue });
  const send = (ip) =>
    worker.fetch(
      new Request("https://reports.example", {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": ip },
        body: JSON.stringify({ number: "+12122340101", type: "spam", report_id: "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d" }),
      }),
      env,
    );
  const first = await send("203.0.113.7");
  const resend = await send("198.51.100.9");

  assert.equal(first.status, 200);
  assert.equal(queuedReports(queue)[0].report_id, "3f1c9a52-7d4e-4b8a-9c1d-2e5f6a7b8c9d");
  assert.equal(resend.status, 429);
  assert.equal((await resend.json()).already_stored, true);
  assert.equal(queuedReports(queue).length, 1, "the resend is not stored again");
});

// ── Daily batch commit ────────────────────────────────────────────────

const json = (value) => new Response(JSON.stringify(value), { status: 200 });

/**
 * Plays GitHub's Git Data API for one repo whose master starts at "c0".
 * `fail(call)` may return a status to fail that call; `treeSha(body)` may
 * decide the sha a new tree gets.
 */
function mockGitHub({ fail = () => 0, treeSha } = {}) {
  const originalFetch = globalThis.fetch;
  const calls = [];
  let head = "c0";
  globalThis.fetch = async (url, options = {}) => {
    const method = options.method ?? "GET";
    const path = new URL(url).pathname.replace("/repos/SysAdminDoc/CallShield/", "");
    const call = { method, path, body: options.body ? JSON.parse(options.body) : undefined, auth: options.headers?.Authorization };
    calls.push(call);
    const status = fail(call, calls);
    if (status) return new Response("refused", { status });
    if (method === "GET" && path === "git/ref/heads/master") return json({ object: { sha: head } });
    if (method === "GET" && path.startsWith("git/commits/")) return json({ tree: { sha: `tree-of-${path.slice(12)}` } });
    if (method === "POST" && path === "git/trees") return json({ sha: treeSha?.(call.body) ?? `t${calls.length}` });
    if (method === "POST" && path === "git/commits") return json({ sha: `c${calls.length}` });
    if (method === "PATCH" && path === "git/refs/heads/master") {
      head = call.body.sha;
      return json({ object: { sha: head } });
    }
    return new Response("unexpected call", { status: 404 });
  };
  return {
    calls,
    get head() {
      return head;
    },
    moveHead(sha) {
      head = sha;
    },
    restore() {
      globalThis.fetch = originalFetch;
    },
  };
}

/** Fire the queue's alarm the way the runtime does: the alarm is cleared first. */
async function fireAlarm(queue) {
  await queue.storage.setAlarm(null);
  await queue.instance.alarm();
}

/** Queue `count` reports straight into a mock queue, as the handler would. */
async function queueReports(queue, count, start = 0) {
  for (let i = start; i < start + count; i += 1) {
    const number = `1212234${String(i).padStart(4, "0")}`;
    const response = await queue.get().fetch("https://report-queue/", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        filename: `${number}_17588000000${String(i % 100).padStart(2, "0")}_0badf00d.json`,
        content: JSON.stringify({ number: `+${number}`, type: "spam" }),
      }),
    });
    assert.equal(response.status, 204);
  }
}

test("the daily flush is the next 06:00 UTC", () => {
  assert.equal(nextFlushTime(Date.parse("2026-09-25T16:30:00Z")), Date.parse("2026-09-26T06:00:00Z"));
  assert.equal(nextFlushTime(Date.parse("2026-09-25T05:59:59Z")), Date.parse("2026-09-25T06:00:00Z"));
  assert.equal(nextFlushTime(Date.parse("2026-09-25T06:00:00Z")), Date.parse("2026-09-26T06:00:00Z"));
  assert.equal(nextFlushTime(Date.parse("2026-12-31T23:00:00Z")), Date.parse("2027-01-01T06:00:00Z"));
});

test("a queued report sets the daily alarm once, and later reports leave it alone", async () => {
  const queue = createMockQueue();
  const before = Date.now();
  await queueReports(queue, 1);
  const alarm = queue.storage.alarm;
  assert.equal(alarm, nextFlushTime(before));

  queue.storage.setAlarm(alarm + 1); // tell a re-set alarm apart from the original
  await queueReports(queue, 3, 1);
  assert.equal(queue.storage.alarm, alarm + 1);
  assert.equal(queue.storage.data.size, 4);
});

test("the queue refuses entries that aren't a report file", async () => {
  const queue = createMockQueue();
  for (const body of [
    { filename: "../../README.md", content: "{}" },
    { filename: "data/reports/12122340101_1758800000000_0badf00d.json", content: "{}" },
    { filename: "12122340101_1758800000000_0badf00d.json", content: 42 },
    "not json",
  ]) {
    const response = await queue.get().fetch("https://report-queue/", {
      method: "POST",
      body: typeof body === "string" ? body : JSON.stringify(body),
    });
    assert.equal(response.status, 400, JSON.stringify(body));
  }
  assert.equal(queue.storage.data.size, 0);
  assert.equal(queue.storage.alarm, null);
});

test("the alarm commits every queued report as one commit and empties the queue", async () => {
  const queue = createMockQueue();
  await queueReports(queue, 200);
  const github = mockGitHub();
  try {
    await fireAlarm(queue);
  } finally {
    github.restore();
  }

  const writes = github.calls.filter((call) => call.method !== "GET");
  assert.deepEqual(writes.map((call) => `${call.method} ${call.path}`), [
    "POST git/trees",
    "POST git/commits",
    "PATCH git/refs/heads/master",
  ]);
  const [tree, commit, ref] = writes;
  assert.equal(tree.body.base_tree, "tree-of-c0");
  assert.equal(tree.body.tree.length, 200);
  assert.ok(tree.body.tree.every((entry) => /^data\/reports\/\d+_\d{13}_[0-9a-f]{8}\.json$/.test(entry.path)));
  assert.deepEqual(JSON.parse(tree.body.tree[0].content), { number: "+12122340000", type: "spam" });
  assert.deepEqual(commit.body.parents, ["c0"]);
  assert.match(commit.body.message, /^Community reports: 200 new\n\n\+12122340000\n/);
  assert.equal(ref.body.force, false);
  assert.ok(github.calls.every((call) => call.auth === "Bearer test-token"));
  assert.notEqual(github.head, "c0");
  assert.equal(ref.body.sha, github.head);
  assert.equal(queue.storage.data.size, 0);
  assert.equal(queue.storage.alarm, null, "an empty queue needs no alarm");
});

test("an empty queue makes no commit", async () => {
  const github = mockGitHub();
  try {
    assert.deepEqual(await flushQueuedReports(createMockStorage(), "test-token"), { committed: 0, full: false, more: false });
  } finally {
    github.restore();
  }
  assert.equal(github.calls.length, 0);
});

test("a failed commit keeps every report queued and retries in an hour", async () => {
  const queue = createMockQueue();
  await queueReports(queue, 3);
  const github = mockGitHub({ fail: (call) => (call.path === "git/trees" ? 502 : 0) });
  const originalError = console.error;
  const errors = [];
  console.error = (...args) => errors.push(args.join(" "));
  const before = Date.now();
  try {
    await fireAlarm(queue);
  } finally {
    github.restore();
    console.error = originalError;
  }

  assert.equal(queue.storage.data.size, 3);
  assert.ok(queue.storage.alarm >= before + 60 * 60 * 1000 && queue.storage.alarm <= Date.now() + 60 * 60 * 1000);
  assert.equal(errors.some((line) => line.includes("HTTP 502")), true);
});

test("a commit beaten to master by another push is rebuilt on the new head", async () => {
  let refused = false;
  const github = mockGitHub({
    fail: (call) => {
      if (call.method === "PATCH" && !refused) {
        refused = true;
        github.moveHead("pushed-meanwhile");
        return 422;
      }
      return 0;
    },
  });
  try {
    const sha = await commitReports([{ filename: "12122340101_1758800000000_0badf00d.json", content: "{}" }], "test-token");
    assert.equal(sha, github.head);
  } finally {
    github.restore();
  }
  const commits = github.calls.filter((call) => call.path === "git/commits");
  assert.deepEqual(commits.map((call) => call.body.parents), [["c0"], ["pushed-meanwhile"]]);
});

test("reports an earlier flush already committed are not committed twice", async () => {
  // The tree comes back unchanged when every file is already on master.
  const github = mockGitHub({ treeSha: (body) => body.base_tree });
  try {
    const storage = createMockStorage();
    await storage.put("report:12122340101_1758800000000_0badf00d.json", "{}");
    const result = await flushQueuedReports(storage, "test-token");
    assert.equal(result.committed, 1);
    assert.equal(storage.data.size, 0);
  } finally {
    github.restore();
  }
  assert.equal(github.calls.some((call) => call.path === "git/commits" && call.method === "POST"), false);
});

test("more than one commit's worth goes out in batches a minute apart", async () => {
  const queue = createMockQueue();
  await queueReports(queue, 1001);
  const github = mockGitHub();
  const before = Date.now();
  try {
    await fireAlarm(queue);
    assert.equal(queue.storage.data.size, 1);
    assert.ok(queue.storage.alarm >= before + 60 * 1000 && queue.storage.alarm <= Date.now() + 60 * 1000);

    await fireAlarm(queue);
    assert.equal(queue.storage.data.size, 0);
  } finally {
    github.restore();
  }
  assert.deepEqual(
    github.calls.filter((call) => call.path === "git/trees").map((call) => call.body.tree.length),
    [1000, 1],
  );
});

test("a report queued while a commit is in flight waits for the next day's commit", async () => {
  const queue = createMockQueue();
  await queueReports(queue, 2);
  const github = mockGitHub({
    fail: (call) => {
      // The report arrives between the tree and the commit.
      if (call.path === "git/trees") void queue.storage.put("report:19998887777_1758800000000_0badf00d.json", "{}");
      return 0;
    },
  });
  try {
    await fireAlarm(queue);
  } finally {
    github.restore();
  }
  assert.deepEqual([...queue.storage.data.keys()], ["report:19998887777_1758800000000_0badf00d.json"]);
  assert.equal(queue.storage.alarm, nextFlushTime(Date.now()));
  assert.equal(github.calls.filter((call) => call.method === "PATCH").length, 1);
});
