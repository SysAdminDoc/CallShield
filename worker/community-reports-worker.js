const FORMAT_CONTROL_CODES = new Set([0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF]);
const MAX_SMS_DOMAINS = 10;
const MAX_SMS_URL_INDICATORS = 10;
const DOMAIN_RE = /^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$/;
const URL_INDICATOR_RE = /^[a-z_]{3,40}$/;

export function normalizePhoneNumberForReport(number) {
  if (typeof number !== "string") return null;

  let cleaned = "";
  for (const ch of number) {
    if (!FORMAT_CONTROL_CODES.has(ch.charCodeAt(0))) cleaned += ch;
  }

  let digits = "";
  for (const ch of cleaned.trim()) {
    if (ch >= "0" && ch <= "9") digits += ch;
  }

  if (digits.length < 7 || digits.length > 15) return null;
  if (digits.length === 10) return `+1${digits}`;
  return `+${digits}`;
}

export function normalizeSmsDomain(value) {
  if (typeof value !== "string") return null;

  let domain = value.toLowerCase().trim();
  domain = domain.replace(/^https?:\/\//, "").replace(/^www\./, "");
  domain = domain.split("/")[0].split("?")[0].split("#")[0].split(":")[0];
  domain = domain.replace(/^\.+|\.+$/g, "");
  if (domain.length < 5 || domain.length > 253) return null;
  if (!domain.includes(".")) return null;
  if (!DOMAIN_RE.test(domain)) return null;

  const labels = domain.split(".");
  if (labels.some((label) => label.length < 1 || label.length > 63)) return null;
  if (labels.some((label) => label.startsWith("-") || label.endsWith("-"))) return null;
  return domain;
}

export function sanitizeSmsDomains(value) {
  if (!Array.isArray(value)) return [];

  const domains = [];
  for (const raw of value) {
    const domain = normalizeSmsDomain(raw);
    if (domain && !domains.includes(domain)) domains.push(domain);
    if (domains.length >= MAX_SMS_DOMAINS) break;
  }
  return domains;
}

export function sanitizeSmsUrlIndicators(value) {
  if (!Array.isArray(value)) return [];

  const indicators = [];
  for (const raw of value) {
    if (typeof raw !== "string") continue;
    const indicator = raw.toLowerCase().trim();
    if (URL_INDICATOR_RE.test(indicator) && !indicators.includes(indicator)) {
      indicators.push(indicator);
    }
    if (indicators.length >= MAX_SMS_URL_INDICATORS) break;
  }
  return indicators;
}

export function sanitizeSmsReportFields(body) {
  return {
    sms_domains: sanitizeSmsDomains(body?.sms_domains),
    sms_url_indicators: sanitizeSmsUrlIndicators(body?.sms_url_indicators),
  };
}

// ── Rate-limit constants ──────────────────────────────────────────────
// Per-IP burst window: at most RATE_LIMIT_MAX_REQUESTS reports within
// RATE_LIMIT_WINDOW_S seconds. The KV key stores a JSON counter with an
// expiration TTL equal to the window so stale keys self-clean.
const RATE_LIMIT_WINDOW_S = 60;
const RATE_LIMIT_MAX_REQUESTS = 5;

// Per-number dedup window: the same IP cannot re-report the same
// normalized number within this window. Prevents replay flooding.
const DEDUP_WINDOW_S = 300;

/**
 * Check the per-IP rate limit against KV.
 * Returns { allowed: boolean, remaining: number, retryAfter: number }.
 *
 * When `env.RATE_LIMIT` is not bound (local dev / test), the limiter is
 * permissive so the worker still functions without KV provisioned.
 */
export async function checkRateLimit(ip, env) {
  if (!env?.RATE_LIMIT) return { allowed: true, remaining: RATE_LIMIT_MAX_REQUESTS, retryAfter: 0 };

  const key = `rl:${ip}`;
  const raw = await env.RATE_LIMIT.get(key);

  if (raw === null) {
    // First request in window — initialize counter.
    await env.RATE_LIMIT.put(key, JSON.stringify({ count: 1, windowStart: Date.now() }), {
      expirationTtl: RATE_LIMIT_WINDOW_S,
    });
    return { allowed: true, remaining: RATE_LIMIT_MAX_REQUESTS - 1, retryAfter: 0 };
  }

  const state = JSON.parse(raw);
  const elapsed = (Date.now() - state.windowStart) / 1000;

  if (elapsed >= RATE_LIMIT_WINDOW_S) {
    // Window expired but KV TTL hasn't fired yet — reset.
    await env.RATE_LIMIT.put(key, JSON.stringify({ count: 1, windowStart: Date.now() }), {
      expirationTtl: RATE_LIMIT_WINDOW_S,
    });
    return { allowed: true, remaining: RATE_LIMIT_MAX_REQUESTS - 1, retryAfter: 0 };
  }

  if (state.count >= RATE_LIMIT_MAX_REQUESTS) {
    const retryAfter = Math.ceil(RATE_LIMIT_WINDOW_S - elapsed);
    return { allowed: false, remaining: 0, retryAfter };
  }

  state.count += 1;
  const ttl = Math.max(1, Math.ceil(RATE_LIMIT_WINDOW_S - elapsed));
  await env.RATE_LIMIT.put(key, JSON.stringify(state), { expirationTtl: ttl });
  return { allowed: true, remaining: RATE_LIMIT_MAX_REQUESTS - state.count, retryAfter: 0 };
}

/**
 * Check per-IP + per-number dedup against KV.
 * Returns true if this (IP, number) pair was already reported recently.
 */
export async function checkDedup(ip, normalizedNumber, env) {
  if (!env?.RATE_LIMIT) return false;

  const key = `dedup:${ip}:${normalizedNumber}`;
  const existing = await env.RATE_LIMIT.get(key);
  if (existing !== null) return true;

  await env.RATE_LIMIT.put(key, "1", { expirationTtl: DEDUP_WINDOW_S });
  return false;
}

/**
 * CallShield Community Reports Worker
 * Deploy to Cloudflare Workers (free tier: 100K requests/day)
 *
 * Setup:
 *   1. Create a Cloudflare account (free)
 *   2. Install wrangler: npm install -g wrangler
 *   3. wrangler login
 *   4. Create a fine-grained GitHub PAT with ONLY "Contents: Read and write" on this repo
 *   5. wrangler secret put GITHUB_TOKEN (paste the PAT)
 *   6. Create a KV namespace: wrangler kv namespace create RATE_LIMIT
 *   7. Update wrangler.toml with the returned namespace ID
 *   8. wrangler deploy
 *
 * The worker receives anonymous spam reports and creates files in data/reports/
 * via the GitHub API. A GitHub Action merges them into the main database daily.
 *
 * Rate limiting: per-IP burst limit (5 reports/60 s) and per-IP+number
 * dedup (same number cannot be re-reported from same IP within 5 min).
 * Both use Cloudflare KV with auto-expiring keys.
 */

export default {
  async fetch(request, env) {
    // CORS headers
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    if (request.method === "GET") {
      return new Response(`<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CallShield Community Reports</title>
<style>*{margin:0;padding:0;box-sizing:border-box}body{background:#000;color:#cdd6f4;font-family:-apple-system,system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.card{background:#1a1a1a;border-radius:20px;padding:40px;max-width:500px;text-align:center}
h1{color:#a6e3a1;font-size:28px;margin-bottom:8px}
.shield{font-size:64px;margin-bottom:16px}
p{color:#bac2de;font-size:14px;line-height:1.6;margin-bottom:16px}
a{color:#89b4fa;text-decoration:none}a:hover{text-decoration:underline}
.badge{display:inline-block;background:#a6e3a1;color:#000;padding:4px 12px;border-radius:8px;font-size:12px;font-weight:bold}
code{background:#252525;padding:2px 6px;border-radius:4px;font-size:12px;color:#fab387}
</style></head><body><div class="card">
<div class="shield">&#128737;</div>
<h1>CallShield</h1>
<p class="badge">Community Reports API</p>
<p style="margin-top:16px">This endpoint receives anonymous spam number reports from the CallShield Android app.</p>
<p>When users block a spam call or tap "Contribute to Community Database", the number is submitted here and merged into the open-source spam database on GitHub.</p>
<p><strong>How it works:</strong><br>
<code>POST</code> with <code>{"number":"+12125551234","type":"spam"}</code></p>
<p>SMS spam reports may include redacted <code>sms_domains</code> and <code>sms_url_indicators</code>; raw SMS bodies are ignored.</p>
<p><a href="https://github.com/SysAdminDoc/CallShield">View on GitHub</a> &middot; <a href="https://github.com/SysAdminDoc/CallShield/releases">Download APK</a></p>
<p style="color:#6c7086;font-size:11px;margin-top:16px">No accounts are used. SMS message text is not stored.</p>
</div></body></html>`, {
        status: 200, headers: { ...corsHeaders, "Content-Type": "text/html;charset=UTF-8" }
      });
    }

    if (request.method !== "POST") {
      return new Response(JSON.stringify({ error: "POST only" }), {
        status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    try {
      // Reject oversized payloads (10KB limit)
      const contentLength = parseInt(request.headers.get("content-length") || "0", 10);
      if (contentLength > 10000) {
        return new Response(JSON.stringify({ error: "Payload too large" }), {
          status: 413, headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Per-IP burst rate limit (KV-backed, persists across isolates)
      const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
      const rl = await checkRateLimit(clientIp, env);
      if (!rl.allowed) {
        return new Response(JSON.stringify({ error: "Rate limited, please retry later" }), {
          status: 429,
          headers: {
            ...corsHeaders,
            "Content-Type": "application/json",
            "Retry-After": String(rl.retryAfter),
          },
        });
      }

      const body = await request.json();
      const number = body.number;

      // Validate type against allowed values
      const VALID_TYPES = ["spam", "robocall", "scam", "telemarketer", "debt_collector", "sms_spam", "not_spam", "ai_voice", "unknown"];
      const type = VALID_TYPES.includes(body.type) ? body.type : "unknown";
      const smsReportFields = sanitizeSmsReportFields(body);

      const normalized = normalizePhoneNumberForReport(number);
      if (!normalized) {
        return new Response(JSON.stringify({ error: "Invalid phone number" }), {
          status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Per-IP + per-number dedup (prevents replaying the same report)
      const isDuplicate = await checkDedup(clientIp, normalized, env);
      if (isDuplicate) {
        return new Response(JSON.stringify({ error: "Duplicate report, already submitted" }), {
          status: 429,
          headers: {
            ...corsHeaders,
            "Content-Type": "application/json",
            "Retry-After": String(DEDUP_WINDOW_S),
          },
        });
      }

      // Create report file via GitHub API
      const timestamp = new Date().toISOString();
      const rand = crypto.randomUUID().substring(0, 8);
      const filename = `${normalized.replace("+", "")}_${Date.now()}_${rand}.json`;
      const report = {
        number: normalized,
        type: type,
        reported_at: timestamp,
        source: "community_app",
      };
      if (smsReportFields.sms_domains.length > 0) {
        report.sms_domains = smsReportFields.sms_domains;
      }
      if (smsReportFields.sms_url_indicators.length > 0) {
        report.sms_url_indicators = smsReportFields.sms_url_indicators;
      }
      const content = JSON.stringify(report, null, 2);

      const githubResponse = await fetch(
        `https://api.github.com/repos/SysAdminDoc/CallShield/contents/data/reports/${filename}`,
        {
          method: "PUT",
          headers: {
            "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
            "Content-Type": "application/json",
            "User-Agent": "CallShield-Worker",
          },
          body: JSON.stringify({
            message: `Community report: ${normalized}`,
            content: btoa(content),
            branch: "master"
          })
        }
      );

      if (!githubResponse.ok) {
        const err = await githubResponse.text();
        console.error("GitHub API error:", err);
        // Surface rate limiting to the client so it can back off
        if (githubResponse.status === 403 || githubResponse.status === 429) {
          return new Response(JSON.stringify({ error: "Rate limited, please retry later" }), {
            status: 429, headers: { ...corsHeaders, "Content-Type": "application/json", "Retry-After": "60" }
          });
        }
        return new Response(JSON.stringify({ error: "Failed to submit report" }), {
          status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      return new Response(JSON.stringify({ success: true, number: normalized }), {
        status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" }
      });

    } catch (e) {
      return new Response(JSON.stringify({ error: "Bad request" }), {
        status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }
  }
};
