const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("管理员可在因果图谱、变量/关系/论文主张、领域分析与因果抽取间完整流转", () => withBrowser(async (browser, url) => {
  const errors = []
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.setDefaultTimeout(10000)
  page.on("pageerror", (err) => errors.push(err.message))
  await page.goto(url + "/causal-graph")
  await login(page)
  await page.getByRole("heading", { name: "因果图谱", exact: true }).waitFor()

  // 放宽默认阈值（示例数据只有 2 条主张）并验证高频因果关系表与下钻
  const relax = page.getByRole("button", { name: /放宽阈值/ })
  if (await relax.isVisible()) await relax.click()
  await page.getByRole("link", { name: "Fiscal Policy", exact: true }).first().click()
  await page.getByRole("heading", { name: "Fiscal Policy", exact: true }).waitFor()
  await page.getByText("学科子领域分布").waitFor()

  // 下钻至因果关系证据页并展开原文证据
  await page.getByRole("link", { name: "查看证据" }).first().click()
  await page.getByRole("button", { name: "展开原文证据" }).first().click()
  await page.getByText("原文证据", { exact: true }).waitFor()

  // 下钻至论文因果主张页并验证回到本地论文详情的因果卡片双向联动
  await page.getByRole("link", { name: "Legacy paper" }).first().click()
  await page.getByRole("heading", { name: "Legacy paper", exact: true }).waitFor()
  await page.getByRole("link", { name: /打开本地论文详情/ }).click()
  await page.getByRole("heading", { name: "因果图谱", exact: true }).waitFor()
  await page.getByRole("link", { name: "查看因果主张" }).waitFor()
  await page.getByRole("link", { name: "发起因果抽取" }).waitFor()

  // 领域分析与因果抽取
  await page.getByRole("link", { name: "领域分析", exact: true }).click()
  await page.getByRole("heading", { name: "领域分析", exact: true }).waitFor()
  await page.getByText("学科子领域与主题明细").waitFor()
  await page.getByText("Public Economics").first().waitFor()

  await page.getByRole("link", { name: "因果抽取", exact: true }).click()
  await page.getByRole("heading", { name: "因果抽取", exact: true }).waitFor()
  await page.getByText("因果抽取批次记录").waitFor()

  // 移动端 390px 无水平溢出
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
  assert.deepEqual(errors, [])
}))
