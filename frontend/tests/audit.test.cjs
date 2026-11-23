const assert = require("node:assert/strict")
const { readFile } = require("node:fs/promises")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("系统管理员从筛选后的 CSV 下载查阅审计事件", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ acceptDownloads: true })
  await page.goto(url + "/papers")
  await login(page)
  await page.getByLabel("搜索论文").fill("legacy-001")
  await page.getByRole("button", { name: "查询", exact: true }).click()
  await page.getByRole("link", { name: "Legacy paper", exact: true }).waitFor()
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "导出 CSV", exact: true }).click(),
  ])
  assert.equal(download.suggestedFilename(), "paperflow-papers.csv")
  const content = await readFile(await download.path(), "utf8")
  assert.match(content, /legacy-001,S123,2024,Legacy paper/)
  assert.match(download.url(), /q=legacy-001/)
  for (let index = 0; index < 20; index++) {
    const next = await page.request.get(url + "/api/papers/export")
    assert.equal(next.status(), 200)
  }

  await page.getByRole("navigation").getByRole("link", { name: "审计日志" }).click({ timeout: 5000 })
  await page.getByRole("heading", { name: "审计日志", exact: true }).waitFor()
  await page.getByLabel("操作者", { exact: true }).fill(process.env.AIE_BROWSER_USER)
  await page.getByLabel("动作", { exact: true }).selectOption("PAPER_CSV_EXPORT")
  await page.getByLabel("结果", { exact: true }).selectOption("SUCCEEDED")
  await page.getByRole("button", { name: "查询", exact: true }).click()
  await page.getByRole("button", { name: "下一页" }).click()
  await page.getByText("第 2 / 2 页", { exact: false }).waitFor()
  await page.getByRole("button", { name: "论文 CSV 导出", exact: true }).click()
  const detail = page.getByRole("region", { name: "事件详情" })
  await detail.getByText("变更摘要").waitFor()
  assert.match(await detail.innerText(), /导出行数\s+1/)
  assert.match(await detail.innerText(), /应用了搜索词\s+是/)
  assert.doesNotMatch(await detail.innerText(), /legacy-001|Legacy paper/)
  await detail.getByRole("button", { name: "关闭事件详情" }).click()
  assert.equal(await page.getByLabel("操作者", { exact: true }).inputValue(), process.env.AIE_BROWSER_USER)
  assert.equal(await page.getByLabel("动作", { exact: true }).inputValue(), "PAPER_CSV_EXPORT")
  assert.equal(await page.getByLabel("结果", { exact: true }).inputValue(), "SUCCEEDED")
  await page.getByText("第 2 / 2 页", { exact: false }).waitFor()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole("button", { name: "论文 CSV 导出", exact: true }).click()
  await detail.getByText("变更摘要").waitFor()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
  await detail.getByRole("link", { name: "查看论文库" }).click()
  await page.getByRole("heading", { name: "论文库", exact: true }).waitFor()
}))

test("审计详情链接到存在的论文、任务和用户，保留缺失对象摘要", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage()
  await page.goto(url + "/audit")
  await login(page)
  await page.getByRole("heading", { name: "审计日志", exact: true }).waitFor()

  async function write(path, method, input, expected) {
    const csrf = await (await page.request.get(url + "/api/csrf")).json()
    const response = await page.request.fetch(url + "/api" + path, {
      method, headers: { [csrf.headerName]: csrf.token }, ...input,
    })
    assert.equal(response.status(), expected, await response.text())
    return response.json()
  }

  async function open(action, id) {
    await page.goto(url + "/audit?" + new URLSearchParams({ action, objectId: id }))
    const table = page.getByRole("region", { name: "审计事件表格" })
    await table.getByRole("button").click()
    const detail = page.getByRole("region", { name: "事件详情" })
    await detail.getByText("变更摘要").waitFor()
    return detail
  }

  const paper = await write("/papers", "POST", { multipart: {
    sourceId: "S123", year: "2024", title: "Audit paper link", authors: "Alice",
    file: { name: "audit.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n") },
  } }, 201)
  const paperDetail = await open("PAPER_IMPORT", paper.paperId)
  await paperDetail.getByRole("link", { name: "查看论文", exact: true }).click()
  await page.getByRole("heading", { name: "Audit paper link", exact: true }).waitFor()

  const job = await write("/jobs", "POST", { data: { kind: "METADATA", sourceId: "S123", yearFrom: 2024 } }, 202)
  await open("JOB_SUBMIT", job.jobId).then((detail) => detail.getByRole("link", { name: "查看任务", exact: true }).click())
  await page.getByRole("heading", { name: "OpenAlex 元数据导入", exact: true }).waitFor()

  const username = "audit.link.user"
  await write("/users", "POST", { data: { username, displayName: "审计关联用户", role: "READ_ONLY" } }, 201)
  await open("USER_CREATE", username).then((detail) => detail.getByRole("link", { name: "查看用户", exact: true }).click())
  await page.getByRole("row", { name: /审计关联用户/ }).waitFor()

  const missing = "missing-audit-paper"
  await write("/papers/" + missing, "PATCH", { data: {
    sourceId: "S123", year: 2024, title: "Private change", authors: "Alice", version: 0,
  } }, 404)
  const unavailable = page.waitForResponse((response) => response.url() === url + "/api/papers/" + missing)
  const missingDetail = await open("PAPER_EDIT", missing)
  assert.equal((await unavailable).status(), 404)
  assert.match(await missingDetail.innerText(), /PAPER_NOT_FOUND/)
  assert.doesNotMatch(await missingDetail.innerText(), /Private change/)
  assert.equal(await missingDetail.getByRole("link", { name: "查看论文", exact: true }).count(), 0)
}))
