const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

async function submit(page, path, payload = {}, expected = 200) {
  const csrf = await page.request.get("/api/csrf")
  assert.ok(csrf.ok())
  const { headerName, token } = await csrf.json()
  const response = await page.request.post("/api" + path, {
    ...payload, headers: { [headerName]: token },
  })
  assert.equal(response.status(), expected, await response.text())
  if (expected !== 204) return response.json()
}

async function archivePaper(page, index) {
  const paper = await submit(page, "/papers", { multipart: {
    sourceId: "S123", year: "2025", title: "Archived For Job " + index, authors: "Alice",
    file: {
      name: "paper.pdf", mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n"),
    },
  } }, 201)
  await submit(page, "/papers/" + paper.paperId + "/archive?version=" + paper.version)
  return paper.paperId
}

test("在后续页找到解析失败项，筛选并从重试任务返回原任务", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ baseURL: url })
  page.setDefaultTimeout(10000)
  await page.goto(url + "/jobs")
  await login(page)
  await page.getByRole("heading", { name: "导入与处理" }).waitFor()
  const archived = await Promise.all(Array.from({ length: 20 }, (_, index) => archivePaper(page, index)))
  const original = await submit(page, "/jobs", { data: { kind: "PARSE", paperIds: [...archived, "legacy-001"] } }, 202)
  await page.reload()
  const originalPath = "/jobs/" + original.jobId
  await page.getByRole("link", { name: "文件解析", exact: true }).and(page.locator('a[href="' + originalPath + '"]')).click()
  await page.getByText("0 成功 · 20 跳过 · 1 失败", { exact: true }).waitFor()
  assert.equal(await page.getByRole("link", { name: "Legacy paper", exact: true }).count(), 0)

  await page.getByRole("button", { name: "下一页" }).click()
  await page.getByRole("link", { name: "Legacy paper", exact: true }).waitFor()
  await page.getByText("共 21 条 · 第 2 / 2 页", { exact: true }).waitFor()

  const filter = page.getByLabel("任务项状态")
  assert.equal(await filter.locator('option[value="PARTIAL"]').count(), 0)
  await filter.selectOption("FAILED")
  await page.getByText("共 1 条 · 第 1 / 1 页", { exact: true }).waitFor()
  await page.getByRole("row", { name: /Legacy paper.*失败.*登记的原件或解析产物不可读取/ }).waitFor()
  assert.equal(await page.getByRole("link", { name: /Archived For Job/ }).count(), 0)
  await page.getByText("0 成功 · 20 跳过 · 1 失败", { exact: true }).waitFor()

  await filter.selectOption("SUCCEEDED")
  await page.getByText("没有符合条件的任务项", { exact: true }).waitFor()
  assert.ok(await page.getByRole("button", { name: "重试失败项" }).isEnabled())
  await filter.selectOption("FAILED")
  await page.getByText("共 1 条 · 第 1 / 1 页", { exact: true }).waitFor()
  await page.getByRole("button", { name: "重试失败项" }).click()
  const returnLink = page.getByRole("link", { name: "查看原任务" })
  await returnLink.waitFor()
  assert.notEqual(new URL(page.url()).pathname, originalPath)
  assert.equal(await returnLink.getAttribute("href"), originalPath)
  await page.getByText("0 成功 · 0 跳过 · 1 失败", { exact: true }).waitFor()
  await returnLink.click()
  await page.getByText("0 成功 · 20 跳过 · 1 失败", { exact: true }).waitFor()

  const reader = await browser.newPage({ baseURL: url })
  const { temporaryPassword } = await submit(page, "/users", { data: {
    username: "job.browser.reader", displayName: "任务只读用户", role: "READ_ONLY",
  } }, 201)
  const personalPassword = "job-browser-personal-password"
  const credentials = { username: "job.browser.reader", password: temporaryPassword }
  await reader.goto(url + originalPath)
  await submit(reader, "/session", { data: credentials })
  await submit(reader, "/session/password", { data: {
    currentPassword: temporaryPassword, newPassword: personalPassword,
  } }, 204)
  await submit(reader, "/session", { data: { ...credentials, password: personalPassword } })
  await reader.reload()
  await reader.getByRole("heading", { name: "无权访问此页面" }).waitFor()
  assert.equal(await reader.getByRole("button", { name: "重试失败项" }).count(), 0)
}))
