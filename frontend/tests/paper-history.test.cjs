const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

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

test("按勾选的论文提交解析，从处理记录打开任务并回到论文", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ baseURL: url })
  page.setDefaultTimeout(10000)
  await page.goto(url + "/papers")
  await login(page)
  await page.getByRole("heading", { name: "论文库", exact: true }).waitFor()
  const a = "History roundtrip selected A"
  const b = "History roundtrip selected B"
  const other = "History roundtrip unselected"
  const aId = await upload(page, a, "2097")
  const bId = await upload(page, b, "2097")
  await upload(page, other, "2098")
  await page.getByLabel("搜索论文").fill("History roundtrip")
  await page.getByRole("button", { name: "查询", exact: true }).click()
  const otherCheckbox = page.getByRole("checkbox", { name: "选择 " + other, exact: true })
  await otherCheckbox.waitFor()
  await page.getByRole("button", { name: "取消选择", exact: true }).waitFor({ state: "hidden" })
  await page.getByRole("checkbox", { name: "选择 " + a, exact: true }).check()
  await page.getByRole("checkbox", { name: "选择 " + b, exact: true }).check()
  assert.equal(await otherCheckbox.isChecked(), false)
  await page.getByRole("status").filter({ hasText: "已选择 2 篇" }).waitFor()
  await page.getByRole("button", { name: "批量处理", exact: true }).click()
  const dialog = page.getByRole("dialog", { name: "提交论文处理" })
  assert.equal(await dialog.getByRole("button", { name: "所选论文 (2)" }).getAttribute("aria-pressed"), "true")
  await dialog.getByLabel("处理阶段").selectOption("PARSE")
  await dialog.getByRole("button", { name: "提交任务" }).click()
  await page.getByText("2 成功 · 0 跳过 · 0 失败", { exact: true }).waitFor()
  const taskPath = new URL(page.url()).pathname
  assert.match(taskPath, /^\/jobs\/[^/]+$/)
  const results = page.getByRole("region", { name: "任务逐项结果" })
  await results.getByText("解析已完成", { exact: true }).first().waitFor()
  assert.deepEqual((await results.getByRole("link").evaluateAll((links) => links.map((link) => link.getAttribute("href")))).sort(),
    ["/papers/" + aId, "/papers/" + bId].sort())
  await results.getByRole("link", { name: a, exact: true }).click()
  await page.getByRole("button", { name: "处理记录", exact: true }).click()
  const history = page.getByRole("region", { name: "论文处理记录" })
  const historyRow = history.getByRole("row", { name: /文件解析.*已完成.*解析已完成/ })
  const historyLink = historyRow.getByRole("link", { name: "文件解析" })
  assert.equal(await historyLink.getAttribute("href"), taskPath)
  await historyLink.click()
  await page.getByRole("heading", { name: "文件解析", exact: true }).waitFor()
  await results.getByRole("link", { name: a, exact: true }).click()
  assert.equal(new URL(page.url()).pathname, "/papers/" + aId)

  await page.getByRole("link", { name: "返回论文库", exact: true }).click()
  await page.getByRole("button", { name: "批量处理", exact: true }).click()
  await dialog.getByLabel("出版来源").fill("S123")
  await dialog.getByLabel("起始年份").fill("2098")
  await dialog.getByLabel("结束年份").fill("2098")
  await dialog.getByLabel("处理阶段").selectOption("PUBLISH")
  await dialog.getByRole("button", { name: "提交任务" }).click()
  await page.getByText("0 成功 · 1 跳过 · 0 失败", { exact: true }).waitFor()
  await results.getByRole("link", { name: other, exact: true }).waitFor()
  assert.equal(await results.getByRole("link").count(), 1)
}))
