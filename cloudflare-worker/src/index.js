/**
 * Dispatches the OCI instance workflow from a Cloudflare cron trigger.
 *
 * GitHub's own `on.schedule` has been unreliable for this repository, so the
 * cron lives here and calls `workflow_dispatch` instead. The workflow file
 * keeps its `schedule` block as a fallback; the concurrency group in
 * create-oci-instance.yml keeps the two paths from racing.
 *
 * Required secret: GITHUB_TOKEN, a fine-grained PAT scoped to this repository
 * with Actions: read and write.
 * Optional secret: TRIGGER_KEY, required by POST /dispatch.
 * Optional vars: OWNER, REPO, WORKFLOW_FILE, REF.
 */
const API = "https://api.github.com";

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(dispatch(env, "cron"));
  },

  async fetch(request, env) {
    const { pathname } = new URL(request.url);

    if (pathname === "/dispatch" && request.method === "POST") {
      if (!env.TRIGGER_KEY || request.headers.get("x-trigger-key") !== env.TRIGGER_KEY) {
        return json({ error: "unauthorized" }, 401);
      }
      const result = await dispatch(env, "manual");
      return json(result.body, result.status);
    }

    return json({
      ok: true,
      workflow: `${env.OWNER}/${env.REPO}/${env.WORKFLOW_FILE}`,
      ref: ref(env),
      cron: "*/10 * * * *",
    });
  },
};

async function dispatch(env, reason) {
  const target = `${env.OWNER}/${env.REPO}`;
  const url = `${API}/repos/${target}/actions/workflows/${env.WORKFLOW_FILE}/dispatches`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "Content-Type": "application/json",
      "User-Agent": "oci-instance-cron-worker",
    },
    body: JSON.stringify({ ref: ref(env) }),
  });

  if (response.status === 204) {
    console.log(`dispatched ${env.WORKFLOW_FILE}@${ref(env)} (${reason})`);
    return {
      status: 202,
      body: { dispatched: true, workflow: env.WORKFLOW_FILE, ref: ref(env), reason },
    };
  }

  const detail = (await response.text()).slice(0, 500);
  console.error(`dispatch failed (${reason}): status=${response.status} body=${detail}`);
  return {
    status: 502,
    body: { dispatched: false, status: response.status, error: detail, reason },
  };
}

function ref(env) {
  return env.REF || "main";
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
