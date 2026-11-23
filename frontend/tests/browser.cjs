const assert = require("node:assert/strict")
const { chromium } = require("playwright-core")

async function withBrowser(run) {
  const url = (process.env.AIE_BROWSER_URL || "http://127.0.0.1:5173").replace(/\/$/, "")
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_EXECUTABLE_PATH })
  try {
    await run(browser, url)
  } finally {
    await browser.close()
  }
}

async function login(page) {
  const { AIE_BROWSER_USER, AIE_BROWSER_PASSWORD } = process.env
  assert.ok(AIE_BROWSER_USER && AIE_BROWSER_PASSWORD, "Set AIE_BROWSER_USER and AIE_BROWSER_PASSWORD for the isolated test environment")
  await page.getByLabel("用户名").fill(AIE_BROWSER_USER)
  await page.getByLabel("密码", { exact: true }).fill(AIE_BROWSER_PASSWORD)
  await page.getByRole("button", { name: "登录管理端" }).click()
}

async function upload(page, title, year) {
  const csrf = await page.request.get("/api/csrf")
  assert.ok(csrf.ok())
  const { headerName, token } = await csrf.json()
  const response = await page.request.post("/api/papers", { headers: { [headerName]: token }, multipart: {
    sourceId: "S123", year, title, authors: "Alice",
    file: { name: "paper.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n") },
  } })
  assert.equal(response.status(), 201, await response.text())
  return (await response.json()).paperId
}

module.exports = { login, upload, withBrowser }
