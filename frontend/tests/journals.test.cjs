const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("从期刊目录查阅期刊覆盖情况、打开年度分布抽屉并下钻至论文库", () => withBrowser(async (browser, url) => {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.setDefaultTimeout(8000)
  await page.goto(url + "/journals")
  await login(page)
  await page.getByRole("heading", { name: "期刊目录", exact: true }).waitFor()

  const cards = page.getByRole("region", { name: "期刊目录卡片" })
  await cards.getByRole("button", { name: "Review of Applied Economics", exact: true }).click()

  const drawer = page.getByRole("dialog", { name: "Review of Applied Economics" })
  await drawer.getByText("年度文献分布", { exact: true }).waitFor()
  await drawer.getByRole("listitem", { name: /2024 年共 \d+ 篇/ }).waitFor()
  await drawer.getByRole("link", { name: /查看全部 \d+ 篇论文/ }).click()

  await page.getByRole("heading", { name: "论文库", exact: true }).waitFor()
  assert.equal(new URL(page.url()).searchParams.get("sourceId"), "S123")
  await page.getByText("出版来源：S123", { exact: true }).waitFor()
  await page.getByRole("link", { name: "Legacy paper", exact: true }).waitFor()

  await page.getByRole("link", { name: "期刊目录", exact: true }).click()
  await page.getByRole("heading", { name: "期刊目录", exact: true }).waitFor()
  await page.getByRole("button", { name: "列表", exact: true }).click()
  await page.getByRole("region", { name: "期刊目录表格" }).getByText("Review of Applied Economics", { exact: true }).waitFor()

  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
}))
