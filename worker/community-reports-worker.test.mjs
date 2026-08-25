import assert from "node:assert/strict";
import test from "node:test";
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
  getClientIp,
  checkDedup,
  deriveReporterBucket,
  recordDedup,
  validateReportEnvironment,
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

  const first = await checkDedup("1.2.3.4", "+12125551234", env);
  assert.equal(first, false, "first report should not be a duplicate");

  // checkDedup is read-only: until recordDedup runs (i.e. the GitHub PUT
  // succeeded), a retry after a failed store must NOT be treated as a dupe.
  const retryAfterFailedStore = await checkDedup("1.2.3.4", "+12125551234", env);
  assert.equal(retryAfterFailedStore, false, "unrecorded report must be retryable");

  await recordDedup("1.2.3.4", "+12125551234", env);
  const second = await checkDedup("1.2.3.4", "+12125551234", env);
  assert.equal(second, true, "same IP + number should be a duplicate once recorded");
});

test("dedup allows same number from different IP", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await recordDedup("1.2.3.4", "+12125551234", env);
  const result = await checkDedup("5.6.7.8", "+12125551234", env);
  assert.equal(result, false, "different IP should not be a duplicate");
});

test("dedup allows same IP for different numbers", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await recordDedup("1.2.3.4", "+12125551234", env);
  const result = await checkDedup("1.2.3.4", "+14155551234", env);
  assert.equal(result, false, "different number should not be a duplicate");
});

test("dedup only permits missing KV behind the explicit local flag", async () => {
  const localEnv = { ALLOW_UNLIMITED_REPORTS: "true" };
  await recordDedup("1.2.3.4", "+12125551234", localEnv);
  const result = await checkDedup("1.2.3.4", "+12125551234", localEnv);
  assert.equal(result, false);
});

test("dedup fails closed when KV is not bound", async () => {
  await assert.rejects(checkDedup("1.2.3.4", "+12125551234", {}), /RATE_LIMIT/);
  await assert.rejects(recordDedup("1.2.3.4", "+12125551234", {}), /RATE_LIMIT/);
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
    assert.equal(JSON.stringify(report).includes("203.0.113.7"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
