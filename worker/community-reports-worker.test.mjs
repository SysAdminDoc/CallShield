import assert from "node:assert/strict";
import test from "node:test";
import { normalizePhoneNumberForReport } from "./community-reports-worker.js";

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
