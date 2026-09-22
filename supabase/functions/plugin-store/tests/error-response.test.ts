import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PluginStoreContext } from "../types/context.ts"
import browse from "../routes/browse.ts"
import publish from "../routes/publish.ts"

Deno.test("browse masks unexpected database exceptions at the HTTP boundary", async () => {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const diagnostic = "private schema and connection details"
  const client = {
    rpc() { throw new Error(diagnostic) },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", browse)
  const response = await app.request("/list")
  assertEquals(response.status, 500)
  const body = await response.text()
  assertEquals(JSON.parse(body), { error: "Internal server error" })
  assertEquals(body.includes(diagnostic), false)
})

Deno.test("github/metadata masks a GitHub outage behind a fixed envelope", async () => {
  // POST /github/metadata gates on plugins.create before it touches GitHub, and
  // a JWT whose payload carries the permission satisfies that gate from the
  // claim alone (userHasPermission short-circuits, so no RPC has to be
  // stubbed). The first thing that can then fail is the repo-visibility probe:
  // a network call whose raw failure text (upstream hostnames, proxy details)
  // used to land in the response body (issue #770). The 502 must carry the
  // fixed envelope while the detail stays on the server-side log.
  const ownerId = "11111111-1111-1111-1111-111111111111"

  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  const token = `${b64({ alg: "HS256", typ: "JWT" })}.${
    b64({ sub: ownerId, is_admin: false, user_permissions: ["plugins.create"] })
  }.sig`

  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = {
    auth: {
      // getAuthenticatedUser verifies the token here; the RBAC claims it acts
      // on are decoded from the token string itself.
      getUser: (jwt?: string) =>
        Promise.resolve(
          jwt
            ? { data: { user: { id: ownerId, email: "owner@test" } }, error: null }
            : { data: { user: null }, error: new Error("bad token") },
        ),
    },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", publish)

  const diagnostic = "getaddrinfo ENOTFOUND api.github.com via internal proxy 10.0.0.1"
  const originalFetch = globalThis.fetch
  globalThis.fetch = (() => {
    throw new Error(diagnostic)
  }) as typeof fetch

  const logged: string[] = []
  const originalError = console.error
  console.error = ((...args: unknown[]) => {
    logged.push(args.map((arg) => Deno.inspect(arg)).join(" "))
  }) as typeof console.error

  try {
    const response = await app.request("/github/metadata", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        githubUrl: "https://github.com/example-owner/example-repo",
        sha256: "a".repeat(64),
      }),
    })

    assertEquals(response.status, 502)
    const body = await response.text()
    assertEquals(
      JSON.parse(body),
      { success: false, error: "Could not determine repository visibility" },
    )
    assertEquals(body.includes(diagnostic), false)
  } finally {
    globalThis.fetch = originalFetch
    console.error = originalError
  }

  // The raw failure detail belongs on the server-side log, and must be there.
  assertEquals(
    logged.some((line) => line.includes(diagnostic)),
    true,
    "the GitHub failure detail must reach the server-side log",
  )
})
// ---------------------------------------------------------------------------
// POST /github - curated manifest diagnostics must reach the publisher
// ---------------------------------------------------------------------------

Deno.test("github surfaces a curated manifest diagnostic instead of a generic 500", async () => {
  // The /github route re-hosts the JAR: it downloads the release asset into
  // memory and extracts the manifest there. A release asset that is not a
  // valid archive is the publisher's input error - pre-fix it fell into the
  // outer generic catch and came back as "Internal server error" (500),
  // while the identical failure on /github/metadata returned a useful 400.
  const ownerId = "22222222-2222-2222-2222-222222222222"

  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  const token = `${b64({ alg: "HS256", typ: "JWT" })}.${
    b64({ sub: ownerId, is_admin: false, user_permissions: ["plugins.create"] })
  }.sig`

  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = {
    auth: {
      getUser: (jwt?: string) =>
        Promise.resolve(
          jwt
            ? { data: { user: { id: ownerId, email: "owner@test" } }, error: null }
            : { data: { user: null }, error: new Error("bad token") },
        ),
    },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", publish)

  // A release whose single JAR asset is not a ZIP at all.
  const notAjar = new TextEncoder().encode("this is not a zip archive at all")
  const release = {
    tag_name: "v1.0.0",
    name: "v1.0.0",
    body: "",
    published_at: "2026-01-01T00:00:00Z",
    assets: [
      {
        name: "plugin.jar",
        browser_download_url: "https://objects.githubusercontent.com/example/plugin.jar",
        url: "https://api.github.com/repos/example-owner/example-repo/releases/assets/1",
        size: notAjar.length,
        content_type: "application/java-archive",
      },
    ],
  }

  // Force the unauthenticated public-CDN download path.
  const savedToken = Deno.env.get("GITHUB_TOKEN")
  try {
    Deno.env.delete("GITHUB_TOKEN")
  } catch { /* not set */ }

  const originalFetch = globalThis.fetch
  globalThis.fetch = ((url: string | URL | Request, _init?: RequestInit) => {
    const u = String(url)
    if (u.includes("/releases/latest") || u.includes("/releases/tags/")) {
      return Promise.resolve(
        new Response(JSON.stringify(release), { status: 200, headers: { "content-type": "application/json" } }),
      )
    }
    return Promise.resolve(
      new Response(notAjar, { status: 200, headers: { "content-type": "application/octet-stream" } }),
    )
  }) as typeof fetch

  const logged: string[] = []
  const originalError = console.error
  console.error = ((...args: unknown[]) => {
    logged.push(args.map((arg) => Deno.inspect(arg)).join(" "))
  }) as typeof console.error

  try {
    const response = await app.request("/github", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ githubUrl: "https://github.com/example-owner/example-repo" }),
    })
    // A curated, publisher-actionable 400 - not the opaque 500 the same
    // failure produced before the PublishInputError branch existed.
    assertEquals(response.status, 400)
    const body = await response.text()
    assertEquals(JSON.parse(body), {
      success: false,
      error:
        "Plugin manifest not found at META-INF/boss-plugin/plugin.json. " +
        "Make sure your plugin JAR contains a valid plugin.json.",
    })
  } finally {
    globalThis.fetch = originalFetch
    console.error = originalError
    if (savedToken !== undefined) Deno.env.set("GITHUB_TOKEN", savedToken)
  }
})

// ---------------------------------------------------------------------------
// POST /github/metadata - an upstream 5xx is NOT a publisher input error
// ---------------------------------------------------------------------------

/**
 * A minimal valid STORED (uncompressed) single-entry ZIP. Enough of the
 * format for the tail-range manifest extractor to walk: local header +
 * central directory entry + EOCD.
 */
function buildStoredZip(entryPath: string, content: string): Uint8Array {
  const enc = new TextEncoder()
  const nameBytes = enc.encode(entryPath)
  const contentBytes = enc.encode(content)

  const localHeader = new Uint8Array(30 + nameBytes.length + contentBytes.length)
  const lv = new DataView(localHeader.buffer)
  lv.setUint32(0, 0x04034b50, true)
  lv.setUint16(4, 20, true) // version needed
  lv.setUint16(8, 0, true) // method: stored
  lv.setUint32(18, contentBytes.length, true) // compressed size
  lv.setUint32(22, contentBytes.length, true) // uncompressed size
  lv.setUint16(26, nameBytes.length, true)
  localHeader.set(nameBytes, 30)
  localHeader.set(contentBytes, 30 + nameBytes.length)

  const cdEntry = new Uint8Array(46 + nameBytes.length)
  const cv = new DataView(cdEntry.buffer)
  cv.setUint32(0, 0x02014b50, true)
  cv.setUint16(6, 20, true) // version needed
  cv.setUint16(10, 0, true) // method: stored
  cv.setUint32(20, contentBytes.length, true) // compressed size
  cv.setUint32(24, contentBytes.length, true) // uncompressed size
  cv.setUint16(28, nameBytes.length, true)
  cv.setUint32(42, 0, true) // local header offset
  cdEntry.set(nameBytes, 46)

  const eocd = new Uint8Array(22)
  const ev = new DataView(eocd.buffer)
  ev.setUint32(0, 0x06054b50, true)
  ev.setUint16(8, 1, true) // entries on this disk
  ev.setUint16(10, 1, true) // total entries
  ev.setUint32(12, cdEntry.length, true)
  ev.setUint32(16, localHeader.length, true) // central directory offset

  const out = new Uint8Array(localHeader.length + cdEntry.length + eocd.length)
  out.set(localHeader, 0)
  out.set(cdEntry, localHeader.length)
  out.set(eocd, localHeader.length + cdEntry.length)
  return out
}

Deno.test("github/metadata keeps the 502 envelope when the JAR host is down (503)", async () => {
  // The manifest extraction (HEAD + range requests) succeeds; the full-body
  // hash stream then hits an upstream 503. That is GitHub/CDN trouble, not
  // the publisher's asset - so the response must stay the fixed 502
  // envelope, not a 400 claiming the asset is missing or renamed.
  const ownerId = "33333333-3333-3333-3333-333333333333"

  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  const token = `${b64({ alg: "HS256", typ: "JWT" })}.${
    b64({ sub: ownerId, is_admin: false, user_permissions: ["plugins.create"] })
  }.sig`

  const manifest = {
    pluginId: "com.example.test",
    displayName: "Test",
    version: "1.0.0",
    apiVersion: "1.0.62",
    mainClass: "com.example.TestPlugin",
  }
  const jar = buildStoredZip("META-INF/boss-plugin/plugin.json", JSON.stringify(manifest))
  const assetUrl = "https://objects.githubusercontent.com/example/plugin.jar"
  const release = {
    tag_name: "v1.0.0",
    name: "v1.0.0",
    body: "",
    published_at: "2026-01-01T00:00:00Z",
    assets: [
      {
        name: "plugin.jar",
        browser_download_url: assetUrl,
        url: "https://api.github.com/repos/example-owner/example-repo/releases/assets/1",
        size: jar.length,
        content_type: "application/java-archive",
      },
    ],
  }

  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>()
  const client = {
    auth: {
      getUser: (jwt?: string) =>
        Promise.resolve(
          jwt
            ? { data: { user: { id: ownerId, email: "owner@test" } }, error: null }
            : { data: { user: null }, error: new Error("bad token") },
        ),
    },
  } as unknown as SupabaseClient
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client)
    await next()
  })
  app.route("/", publish)

  const originalFetch = globalThis.fetch
  globalThis.fetch = ((url: string | URL | Request, init?: RequestInit) => {
    const u = String(url)
    if (u.includes("/repos/example-owner/example-repo") && !u.includes("/releases/")) {
      // repo visibility probe
      return Promise.resolve(
        new Response(JSON.stringify({ private: false }), { status: 200 }),
      )
    }
    if (u.includes("/releases/latest")) {
      return Promise.resolve(
        new Response(JSON.stringify(release), { status: 200, headers: { "content-type": "application/json" } }),
      )
    }
    if (u === assetUrl) {
      const range = init?.headers
        ? (init.headers as Record<string, string>).Range ?? ""
        : ""
      const method = (init?.method ?? "GET").toUpperCase()
      if (method === "HEAD") {
        return Promise.resolve(
          new Response(null, { status: 200, headers: { "Content-Length": String(jar.length) } }),
        )
      }
      const m = range.match(/^bytes=(\d+)-(\d+)$/)
      if (m) {
        const start = Number(m[1])
        const end = Math.min(Number(m[2]), jar.length - 1)
        const chunk = new Uint8Array(end - start + 1)
        chunk.set(jar.subarray(start, end + 1))
        return Promise.resolve(
          new Response(chunk, { status: 206 }),
        )
      }
      // The full-body hash stream: this is the upstream outage under test.
      return Promise.resolve(new Response(null, { status: 503, statusText: "Service Unavailable" }))
    }
    throw new Error(`unexpected fetch: ${u}`)
  }) as typeof fetch

  const logged: string[] = []
  const originalError = console.error
  console.error = ((...args: unknown[]) => {
    logged.push(args.map((arg) => Deno.inspect(arg)).join(" "))
  }) as typeof console.error

  try {
    const response = await app.request("/github/metadata", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        githubUrl: "https://github.com/example-owner/example-repo",
        sha256: "a".repeat(64),
      }),
    })
    assertEquals(response.status, 502, "an upstream 5xx keeps the fixed 502 envelope")
    const body = await response.text()
    assertEquals(JSON.parse(body), { success: false, error: "Failed to compute JAR hash" })
    // The 503 must not be dressed up as the publisher's fault.
    assertEquals(body.includes("asset missing or renamed?"), false)
  } finally {
    globalThis.fetch = originalFetch
    console.error = originalError
  }

  // The upstream status is still logged server-side for operators.
  assertEquals(
    logged.some((line) => line.includes("HTTP 503")),
    true,
    "the upstream 503 must reach the server-side log",
  )
})
