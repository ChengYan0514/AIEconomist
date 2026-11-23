const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

async function write(page, path, data, expected = 200, method = "POST") {
  const csrf = await page.request.get("/api/csrf")
  assert.ok(csrf.ok())
  const { headerName, token } = await csrf.json()
  const response = await page.request.fetch("/api" + path, { method, data, headers: { [headerName]: token } })
  assert.equal(response.status(), expected, await response.text())
  if (expected !== 204) return response.json()
}

test("从总览计数进入论文库后，刷新及浏览器返回可恢复状态筛选", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage()
  page.setDefaultTimeout(5000)
  await page.goto(url)
  await login(page)
  await page.getByRole("heading", { name: "总览", exact: true }).waitFor()
  for (const label of ["待解析", "待导入全文", "处理失败", "已发布全文"]) {
    await page.getByRole("link", { name: new RegExp("^" + label + " [\\d,]+$") }).waitFor()
  }
  const published = page.getByRole("link", { name: /已发布全文/ })
  await published.click()
  const status = page.getByLabel("处理状态")
  assert.equal(new URL(page.url()).searchParams.get("status"), "PUBLISHED")
  await status.waitFor()
  assert.equal(await status.inputValue(), "PUBLISHED")
  const csv = page.getByRole("link", { name: "导出 CSV" })
  assert.equal(new URL(await csv.getAttribute("href"), url).searchParams.get("status"), "PUBLISHED")
  const paper = page.getByRole("link", { name: "Legacy paper", exact: true })
  await paper.waitFor()
  await page.reload()
  await paper.click()
  await page.getByRole("heading", { name: "Legacy paper", exact: true }).waitFor()
  await page.goBack()
  await status.waitFor()
  assert.equal(await status.inputValue(), "PUBLISHED")
  await page.getByRole("button", { name: "重置", exact: true }).click()
  assert.equal(new URL(page.url()).searchParams.has("status"), false)
  assert.equal(await status.inputValue(), "")
  await page.getByRole("link", { name: "AI Economist 首页" }).click()
  await page.getByRole("heading", { name: "总览", exact: true }).waitFor()
}))

test("近期任务保留创建顺序并按角色开放详情", () => withBrowser(async (browser, url) => {
  const admin = await browser.newPage({ baseURL: url })
  admin.setDefaultTimeout(10000)
  await admin.goto(url + "/overview")
  await login(admin)
  await admin.getByRole("heading", { name: "总览", exact: true }).waitFor()
  const task = (yearFrom, yearTo) => write(admin, "/jobs", { kind: "METADATA", sourceId: "S123", yearFrom, yearTo }, 202)
  const earlier = await task(2022, 2023)
  const later = await task(2024, 2025)
  await admin.reload()
  const recent = admin.getByRole("region", { name: "近期任务" })
  const latest = 'a[href="/jobs/' + later.jobId + '"]'
  const latestLink = recent.locator(latest)
  await latestLink.waitFor()
  const links = await recent.locator('a[href^="/jobs/"]').evaluateAll((nodes) => nodes.map((link) => link.href))
  assert.ok(links.indexOf(url + "/jobs/" + later.jobId) < links.indexOf(url + "/jobs/" + earlier.jobId))
  const row = recent.getByRole("row").filter({ has: admin.locator(latest) })
  await row.getByText("OpenAlex 元数据导入", { exact: true }).waitFor()
  await row.getByText("S123 · 2024–2025", { exact: true }).waitFor()
  await row.getByText(/^(排队中|处理中|已完成)$/).waitFor()
  await row.getByRole("progressbar", { name: "处理进度" }).waitFor()
  await latestLink.click()
  await admin.getByRole("heading", { name: "OpenAlex 元数据导入", exact: true }).waitFor()

  const username = "overview.browser.reader"
  const displayName = "总览权限验收账号"
  const { temporaryPassword } = await write(admin, "/users", { username, displayName, role: "READ_ONLY" }, 201)
  const viewer = await browser.newPage({ baseURL: url })
  viewer.setDefaultTimeout(10000)
  await viewer.goto(url)
  await write(viewer, "/session", { username, password: temporaryPassword })
  const password = "overview-browser-password"
  await write(viewer, "/session/password", { currentPassword: temporaryPassword, newPassword: password }, 204)
  await write(viewer, "/session", { username, password })
  const requests = []
  viewer.on("request", (request) => requests.push(new URL(request.url()).pathname))
  await viewer.reload()
  await viewer.getByRole("heading", { name: "总览", exact: true }).waitFor()
  await viewer.getByRole("link", { name: /已发布全文/ }).waitFor()
  assert.equal(await viewer.getByRole("heading", { name: "近期任务" }).count(), 0)
  assert.equal(await viewer.getByRole("link", { name: "导出 CSV" }).count(), 0)
  assert.equal(await viewer.getByRole("link", { name: "系统状态" }).count(), 0)
  assert.equal(requests.includes("/api/jobs"), false)

  await write(admin, "/users/" + username, { displayName, role: "DATA_MAINTAINER" }, 200, "PATCH")
  await viewer.reload()
  await viewer.getByRole("region", { name: "近期任务" }).getByText("S123 · 2024–2025", { exact: true }).waitFor()
  assert.equal(await viewer.getByRole("link", { name: "导出 CSV" }).count(), 0)
  assert.equal(await viewer.getByRole("link", { name: "系统状态" }).count(), 0)
}))

test("计数加载、失败和零篇分别显示，重试只请求失败的计数", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  page.setDefaultTimeout(8000)
  let release
  const waitForFailure = new Promise((resolve) => { release = resolve })
  let fail = true
  const counts = []
  await page.route("**/api/papers?*", async (route) => {
    const { searchParams } = new URL(route.request().url())
    const status = searchParams.get("status")
    counts.push(status)
    if (status !== "READY") return route.continue()
    await waitForFailure
    return fail ? route.fulfill({ status: 503, json: { message: "暂时无法完成操作" } }) :
      route.fulfill({ status: 200, json: { items: [], page: 0, size: 1, total: 0 } })
  })
  await page.route("**/api/jobs?size=5", (route) => route.fulfill({
    json: { items: [], page: 0, size: 5, total: 0 },
  }))
  await page.goto(url)
  await login(page)
  const papers = page.getByRole("region", { name: "全文处理状态" })
  await page.getByText("暂无任务", { exact: true }).waitFor()
  await papers.getByRole("link", { name: /已发布全文/ }).waitFor()
  await papers.getByText("正在加载…", { exact: true }).first().waitFor()
  assert.equal(await papers.getByRole("alert").count(), 0)

  release()
  const retry = papers.getByRole("button", { name: "重试待导入全文计数" })
  await retry.waitFor()
  await papers.getByRole("alert").getByText("计数加载失败", { exact: true }).waitFor()
  assert.equal(await papers.getByRole("link", { name: /待导入全文/ }).count(), 0)
  const before = counts.length
  fail = false
  await retry.focus()
  await page.keyboard.press("Enter")
  await papers.getByRole("link", { name: "待导入全文 0", exact: true }).waitFor()
  assert.deepEqual(counts.slice(before), ["READY"])
  assert.equal(await papers.getByRole("alert").count(), 0)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
}))
