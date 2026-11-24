const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

test("登录页仅展示居中登录模块，且管理员可在因果知识图谱、详情、领域分析与因果抽取间完整流转", () => withBrowser(async (browser, url) => {
  const errors = []
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.setDefaultTimeout(10000)
  page.on("pageerror", (err) => errors.push(err.message))
  await page.goto(url + "/causal-graph")
  assert.equal(await page.locator(".auth-editorial").count(), 0)
  await login(page)
  await page.getByRole("heading", { name: "因果知识图谱", exact: true }).waitFor()
  await page.getByText("抽取记录", { exact: true }).waitFor()
  await page.getByText("标准关系", { exact: true }).waitFor()
  await page.getByRole("button", { name: "最小记录数说明" }).waitFor()
  await page.getByRole("button", { name: "最小方法数说明" }).waitFor()

  // 验证节点名称 STW 补全（带来源标记与记录数）及领域可搜索多选下拉
  const nodeInput = page.getByRole("combobox", { name: "节点名称" })
  await nodeInput.fill("fis")
  await page.getByRole("option", { name: /Fiscal Policy/ }).first().click()
  assert.equal(await nodeInput.inputValue(), "Fiscal Policy")
  await page.getByRole("button", { name: "清空节点名称" }).click()
  assert.equal(await nodeInput.inputValue(), "")

  await page.getByRole("button", { name: /选择领域/ }).click()
  await page.getByRole("searchbox", { name: "搜索领域" }).fill("Public")
  await page.getByLabel("Public Economics").check()
  await page.keyboard.press("Escape")
  await page.getByRole("button", { name: "移除领域 Public Economics" }).click()

  // 切换按边搜索并校验原因/结果变量 tooltip
  await page.getByRole("button", { name: "按边搜索", exact: true }).click()
  await page.getByRole("button", { name: "原因变量说明" }).waitFor()
  await page.getByRole("button", { name: "结果变量说明" }).waitFor()
  await page.getByRole("button", { name: "按节点搜索", exact: true }).click()

  // 放宽默认阈值（示例数据只有 2 条主张）并验证高频因果关系表与下钻
  const relax = page.getByRole("button", { name: /放宽阈值/ })
  if (await relax.isVisible()) await relax.click()
  await page.getByRole("link", { name: "Fiscal Policy", exact: true }).first().click()
  await page.getByRole("heading", { name: "Fiscal Policy", exact: true }).waitFor()
  await page.getByText("总记录", { exact: true }).waitFor()
  await page.getByText("主领域", { exact: true }).waitFor()

  // 下钻至关系详情页并校验 tooltip 与展开原文证据
  await page.getByRole("link", { name: "查看", exact: true }).first().click()
  await page.getByText("证据记录", { exact: false }).first().waitFor()
  await page.getByRole("button", { name: "展开原文证据" }).first().click()
  await page.getByText("原文证据", { exact: true }).waitFor()

  // 下钻至论文因果声明页并验证回到本地论文详情的因果卡片双向联动
  await page.getByRole("link", { name: "Legacy paper" }).first().click()
  await page.getByRole("heading", { name: "Legacy paper", exact: true }).waitFor()
  await page.getByText("声明明细", { exact: true }).waitFor()
  await page.getByRole("link", { name: /打开本地论文详情/ }).click()
  await page.getByRole("heading", { name: "因果知识图谱", exact: true }).waitFor()
  await page.getByRole("link", { name: "查看论文因果声明" }).waitFor()
  await page.getByRole("link", { name: "发起因果抽取" }).waitFor()

  // 领域分析与因果抽取
  await page.getByRole("link", { name: "领域分析", exact: true }).click()
  await page.getByRole("heading", { name: "领域分析", exact: true }).waitFor()
  await page.getByText("领域明细", { exact: false }).waitFor()
  await page.getByText("Public Economics").first().waitFor()

  await page.getByRole("link", { name: "因果抽取", exact: true }).click()
  await page.getByRole("heading", { name: "因果抽取", exact: true }).waitFor()
  await page.getByText("因果抽取任务列表").waitFor()

  // 移动端 390px 无水平溢出
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390)
  assert.deepEqual(errors, [])
}))
