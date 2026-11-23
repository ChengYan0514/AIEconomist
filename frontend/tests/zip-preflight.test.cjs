const assert = require("node:assert/strict")
const path = require("node:path")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("在 ZIP 预检中核对三类记录并确认通过的行", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  await page.goto(url + "/jobs")
  await login(page)
  await page.getByRole("button", { name: "导入原件", exact: true }).click()
  await page.getByLabel("选择 ZIP 导入包").setInputFiles(path.join(__dirname, "fixtures", "preflight.zip"))
  await page.getByRole("button", { name: "开始预检" }).click()
  const confirm = page.getByRole("button", { name: "确认导入 1 条", exact: true })
  await confirm.waitFor()
  const taskPath = new URL(page.url()).pathname
  await page.getByText("1 条可导入 · 1 条重复 · 1 条有问题", { exact: true }).waitFor()

  const table = page.getByRole("region", { name: "ZIP 逐项结果" })
  await table.getByRole("columnheader", { name: "CSV 数据记录位序" }).waitFor()
  await table.focus()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
  await page.keyboard.press("ArrowRight")
  await page.waitForFunction(() => document.activeElement.scrollLeft > 0)

  const ready = table.getByRole("row").filter({ hasText: "Browser ZIP import" })
  const duplicate = table.getByRole("row").filter({ hasText: "Legacy paper" })
  const invalid = table.getByRole("row").filter({ hasText: "Browser ZIP missing" })
  for (const [row, cells] of [
    [ready, ["1", "files/paper.pdf", "可导入"]],
    [duplicate, ["2", "files/paper.pdf", "重复", "论文已存在"]],
    [invalid, ["3", "files/missing.pdf", "有问题", "包内找不到该原件"]],
  ]) {
    for (const cell of cells) await row.getByText(cell, { exact: true }).waitFor()
  }
  assert.equal(await ready.getByRole("link").count(), 0)
  const existingPaper = duplicate.getByRole("link", { name: "Legacy paper" })
  await existingPaper.click()
  assert.equal(new URL(page.url()).pathname, "/papers/legacy-001")
  await page.getByRole("heading", { name: "Legacy paper" }).waitFor()

  await page.goto(url + taskPath)
  await confirm.click()
  await page.getByText("1 成功 · 1 跳过 · 1 失败", { exact: true }).waitFor()
  await ready.getByRole("link", { name: "Browser ZIP import" }).waitFor()
  await ready.getByText("可导入", { exact: true }).waitFor()
  await ready.getByText("files/paper.pdf", { exact: true }).waitFor()
  await ready.getByText("原件已登记", { exact: true }).waitFor()
  assert.equal(await confirm.count(), 0)
  assert.equal(await page.getByRole("button", { name: "重试失败项" }).count(), 0)
  await page.getByLabel("任务项状态").selectOption("FAILED")
  await invalid.getByText("修正输入后重新上传 ZIP", { exact: true }).waitFor()
  assert.equal(await ready.count(), 0)
  assert.equal(new URL(page.url()).pathname, taskPath)
}))
