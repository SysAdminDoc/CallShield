import assert from "node:assert/strict";
import test from "node:test";
import {
  normalizePhoneNumberForReport,
  normalizeSmsDomain,
  sanitizeSmsDomains,
  sanitizeSmsReportFields,
  sanitizeSmsUrlIndicators,
  checkRateLimit,
  checkDedup,
} from "./community-reports-worker.js";

test("normalizes ASCII phone numbers for reports", () => {
  assert.equal(normalizePhoneNumberForReport("+1 (212) 555-1234"), "+12125551234");
  assert.equal(normalizePhoneNumberForReport("212-555-1234"), "+12125551234");
  assert.equal(normalizePhoneNumberForReport("+442071234567"), "+442071234567");
});

test("strips formatting control marks before report normalization", () => {
  assert.equal(normalizePhoneNumberForReport("\u200E+\u200F1 212\u200B-555\u200E-1234"), "+12125551234");
});

test("rejects Unicode digit spoofing and overlong report numbers", () => {
  assert.equal(normalizePhoneNumberForReport("\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660"), null);
  assert.equal(normalizePhoneNumberForReport("\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10"), null);
  assert.equal(normalizePhoneNumberForReport("+1234567890123456"), null);
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
});

test("rate limiter is permissive when KV is not bound", async () => {
  const rl = await checkRateLimit("1.2.3.4", {});
  assert.equal(rl.allowed, true);

  const rl2 = await checkRateLimit("1.2.3.4", null);
  assert.equal(rl2.allowed, true);
});

test("dedup rejects same IP + number within window", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  const first = await checkDedup("1.2.3.4", "+12125551234", env);
  assert.equal(first, false, "first report should not be a duplicate");

  const second = await checkDedup("1.2.3.4", "+12125551234", env);
  assert.equal(second, true, "same IP + number should be a duplicate");
});

test("dedup allows same number from different IP", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await checkDedup("1.2.3.4", "+12125551234", env);
  const result = await checkDedup("5.6.7.8", "+12125551234", env);
  assert.equal(result, false, "different IP should not be a duplicate");
});

test("dedup allows same IP for different numbers", async () => {
  const kv = createMockKV();
  const env = { RATE_LIMIT: kv };

  await checkDedup("1.2.3.4", "+12125551234", env);
  const result = await checkDedup("1.2.3.4", "+14155551234", env);
  assert.equal(result, false, "different number should not be a duplicate");
});

test("dedup is permissive when KV is not bound", async () => {
  const result = await checkDedup("1.2.3.4", "+12125551234", {});
  assert.equal(result, false);
});
