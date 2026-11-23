const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("系统管理员查看依赖状态，刷新失败后保留上次检查结果", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.setDefaultTimeout(8000)
  await page.goto(url + "/papers")
  await login(page)
  await page.getByRole("link", { name: "系统状态", exact: true }).click()
  await page.getByRole("heading", { name: "系统状态", exact: true }).waitFor()
  assert.equal(await page.getByRole("link", { name: "系统状态", exact: true }).getAttribute("aria-current"), "page")

  const dependencies = page.getByRole("table", { name: "依赖检查结果" })
  const mineru = process.env.AIE_MINERU_FIXTURE
  const postgres = dependencies.getByRole("row", { name: /PostgreSQL.*正常.*查询成功/ })
  await postgres.waitFor()
  const mineruRow = dependencies.getByRole("row", { name: /MinerU.*(正常|不可用)/ })
  await mineruRow.waitFor()
  if (mineru === "healthy") await mineruRow.getByText("正常", { exact: true }).waitFor()
  if (mineru === "slow") await mineruRow.getByText("连接超时", { exact: true }).waitFor()
  await dependencies.getByRole("row", { name: /新版文件存储.*正常.*读写检查通过/ }).waitFor()
  await dependencies.getByRole("row", { name: /旧版文件存储.*正常.*读取检查通过/ }).waitFor()
  await dependencies.getByRole("row", { name: /OpenAlex 快照.*正常.*快照表可读/ }).waitFor()

  const config = page.getByRole("region", { name: "有效配置" })
  for (const text of ["100 MiB", "256 MiB", "10,000 篇", /\d+\.\d+\.\d+/]) {
    await config.getByText(text, { exact: typeof text === "string" }).waitFor()
  }
  const previousChecks = await dependencies.locator("time").evaluateAll((times) => times.map((time) => time.dateTime))
  assert.equal(previousChecks.length, 5)
  assert.ok(previousChecks.every((value) => !Number.isNaN(Date.parse(value))))

  await page.route("**/api/system-status", (route) => route.fulfill({
    status: 503,
    json: { code: "INTERNAL", message: "暂时无法完成操作" },
  }))
  const refresh = page.getByRole("button", { name: "重新检查" })
  await refresh.focus()
  await page.keyboard.press("Enter")
  await page.getByRole("alert").getByText(/刷新失败.*显示上次检查结果/).waitFor()
  assert.deepEqual(await dependencies.locator("time").evaluateAll((times) => times.map((time) => time.dateTime)), previousChecks)
  assert.ok(await postgres.isVisible())
  assert.ok(await config.getByText("100 MiB", { exact: true }).isVisible())

  await page.unroute("**/api/system-status")
  await refresh.click()
  await page.waitForFunction((previous) => {
    const time = document.querySelector("table time")
    return time && time.dateTime !== previous
  }, previousChecks[0])
  assert.equal(await page.getByRole("alert").count(), 0)
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
}))
