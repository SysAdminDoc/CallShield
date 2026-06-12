const FORMAT_CONTROL_CODES = new Set([0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF]);

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
 *   6. wrangler deploy
 *
 * The worker receives anonymous spam reports and creates files in data/reports/
 * via the GitHub API. A GitHub Action merges them into the main database daily.
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
<p><a href="https://github.com/SysAdminDoc/CallShield">View on GitHub</a> &middot; <a href="https://github.com/SysAdminDoc/CallShield/releases">Download APK</a></p>
<p style="color:#6c7086;font-size:11px;margin-top:16px">No personal data is collected. Only phone numbers reported as spam are stored.</p>
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

      const body = await request.json();
      const number = body.number;

      // Validate type against allowed values
      const VALID_TYPES = ["spam", "robocall", "scam", "telemarketer", "debt_collector", "sms_spam", "not_spam", "unknown"];
      const type = VALID_TYPES.includes(body.type) ? body.type : "unknown";

      const normalized = normalizePhoneNumberForReport(number);
      if (!normalized) {
        return new Response(JSON.stringify({ error: "Invalid phone number" }), {
          status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Rate limit by IP (simple: reject if same IP submitted in last 10s)
      // In production, use Cloudflare KV for proper rate limiting

      // Create report file via GitHub API
      const timestamp = new Date().toISOString();
      const rand = crypto.randomUUID().substring(0, 8);
      const filename = `${normalized.replace("+", "")}_${Date.now()}_${rand}.json`;
      const content = JSON.stringify({
        number: normalized,
        type: type,
        reported_at: timestamp,
        source: "community_app"
      }, null, 2);

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
