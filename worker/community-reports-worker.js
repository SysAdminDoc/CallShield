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

    if (request.method !== "POST") {
      return new Response(JSON.stringify({ error: "POST only" }), {
        status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    try {
      const body = await request.json();
      const number = body.number;
      const type = body.type || "unknown";

      // Validate phone number (must be 7-15 digits, optionally with +)
      const digits = number?.replace(/\D/g, "") || "";
      if (digits.length < 7 || digits.length > 15) {
        return new Response(JSON.stringify({ error: "Invalid phone number" }), {
          status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Normalize to +1XXXXXXXXXX
      let normalized = digits;
      if (normalized.length === 10) normalized = "1" + normalized;
      normalized = "+" + normalized;

      // Rate limit by IP (simple: reject if same IP submitted in last 10s)
      // In production, use Cloudflare KV for proper rate limiting

      // Create report file via GitHub API
      const timestamp = new Date().toISOString();
      const filename = `${normalized.replace("+", "")}_${Date.now()}.json`;
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
