import assert from "node:assert/strict";
import test from "node:test";
import {
  normalizePhoneNumberForReport,
  normalizeSmsDomain,
  sanitizeSmsDomains,
  sanitizeSmsReportFields,
  sanitizeSmsUrlIndicators,
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
