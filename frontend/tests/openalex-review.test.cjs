const assert = require("node:assert/strict")
const { test } = require("node:test")
const { login, withBrowser } = require("./browser.cjs")

async function write(page, path, data, expected = 200, method = "POST") {
  const token = await (await page.request.get("/api/csrf")).json()
  const response = await page.request.fetch("/api" + path, { method, data, headers: { [token.headerName]: token.token } })
  assert.equal(response.status(), expected, path)
  return expected === 204 ? undefined : response.json()
}

async function activate(admin, page, username, role) {
  const { temporaryPassword } = await write(admin, "/users", { username, displayName: username, role }, 201)
  const password = "candidate-review-personal-password"
  await write(page, "/session", { username, password: temporaryPassword })
  await write(page, "/session/password", { currentPassword: temporaryPassword, newPassword: password }, 204)
  await write(page, "/session", { username, password })
}

async function submitMatch(page, title) {
  await page.goto("/papers")
  await page.getByRole("button", { name: "上传单篇", exact: true }).click()
  const upload = page.getByRole("dialog", { name: "上传单篇论文" })
  await upload.getByLabel("论文标题").fill(title)
  await upload.getByLabel("出版来源").fill("S123")
  await upload.getByLabel("发表年份").fill("2020")
  await upload.getByLabel("作者").fill("Local Author")
  await upload.getByLabel("DOI").fill("10.1/amb")
  await upload.locator('input[type="file"]').setInputFiles({ name: "review.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4\n%%EOF\n") })
  await upload.getByRole("button", { name: "上传论文" }).click()
  await page.getByRole("heading", { name: title }).waitFor()
  const paperPath = new URL(page.url()).pathname
  await page.getByRole("button", { name: "匹配论文" }).click()
  await page.getByRole("heading", { name: "OpenAlex 匹配", exact: true }).waitFor()
  await page.getByText("找到多个候选，请确认关联", { exact: true }).waitFor()
  return paperPath
}

async function openReview(page) {
  await page.getByRole("button", { name: "复核候选" }).click()
  const review = page.getByRole("dialog", { name: "OpenAlex 候选复核" })
  await review.getByRole("columnheader", { name: "当前本地书目" }).waitFor()
  return review
}

function field(review, label) {
  return review.getByRole("rowheader", { name: label, exact: true }).locator("..").getByRole("cell")
}

test("数据维护员从 OpenAlex 导入走到任务复核，可暂缓并用已核对的版本确认关联", () => withBrowser(async (browser, url) => {
  const admin = await browser.newPage({ baseURL: url })
  const page = await browser.newPage({ baseURL: url, viewport: { width: 390, height: 844 } })
  await admin.goto(url)
  await login(admin)
  await admin.getByRole("link", { name: "AI Economist 首页" }).waitFor()
  await activate(admin, page, "alex.browser.maintainer", "DATA_MAINTAINER")
  await page.goto(url + "/openalex")
  await page.getByLabel("搜索出版来源").fill("S123")
  await page.getByRole("button", { name: "搜索", exact: true }).click()
  await page.getByRole("row").filter({ hasText: "S123" }).getByRole("button", { name: "选择", exact: true }).click()
  await page.getByRole("button", { name: "提交导入" }).click()
  await page.getByRole("heading", { name: "OpenAlex 元数据导入", exact: true }).waitFor()
  await page.getByText(/已导入 \d+ 条作品元数据/).waitFor()

  const paperPath = await submitMatch(page, "Browser bibliography for review")
  const jobPath = new URL(page.url()).pathname
  await page.getByLabel("任务项状态").selectOption("SKIPPED")
  let review = await openReview(page)
  assert.deepEqual(await field(review, "标题").allTextContents(), ["Browser bibliography for review", "Ambiguous Study"])
  assert.deepEqual(await field(review, "作者").allTextContents(), ["Local Author", "Alice"])
  assert.deepEqual(await field(review, "发表年份").allTextContents(), ["2020", "2024"])
  assert.deepEqual(await field(review, "DOI").allTextContents(), ["10.1/amb", "10.1/amb"])
  assert.match(await field(review, "出版来源").nth(1).textContent(), /Review of Applied Economics.*S123/)
  assert.equal(await field(review, "OpenAlex 作品 ID").nth(1).textContent(), "W124")
  assert.equal(await review.evaluate((dialog) => dialog.scrollWidth === dialog.clientWidth), true)
  await review.getByRole("button", { name: "稍后处理" }).click()
  await review.waitFor({ state: "hidden" })
  assert.equal(new URL(page.url()).pathname, jobPath)
  assert.equal(await page.getByLabel("任务项状态").inputValue(), "SKIPPED")
  await page.waitForFunction(() => document.activeElement?.textContent === "复核候选")
  await page.getByText("待确认匹配", { exact: true }).waitFor()

  review = await openReview(page)
  await review.getByLabel("OpenAlex 候选作品").selectOption("W125")
  assert.equal(await field(review, "标题").nth(1).textContent(), "Ambiguous Study, Alternative")
  assert.equal(await field(review, "作者").nth(1).textContent(), "Bob")
  const paper = await (await admin.request.get("/api" + paperPath)).json()
  const { version, sourceId, year, authors, doi } = paper
  await write(admin, paperPath, { version, sourceId, year, authors, doi, title: "Changed during review" }, 200, "PATCH")
  await review.getByRole("button", { name: "确认关联" }).click()
  await review.getByRole("alert").filter({ hasText: "论文已更新" }).waitFor()
  assert.equal(await field(review, "标题").nth(0).textContent(), "Browser bibliography for review")
  await review.getByRole("button", { name: "刷新论文" }).click()
  await field(review, "标题").nth(0).getByText("Changed during review").waitFor()
  await review.getByRole("button", { name: "确认关联" }).click()
  await review.getByRole("status").filter({ hasText: "已关联 OpenAlex 作品 W125" }).waitFor()
  await review.getByRole("button", { name: "返回任务" }).click()
  review = await openReview(page)
  await review.getByRole("status").filter({ hasText: "已关联 OpenAlex 作品 W125" }).waitFor()
  assert.equal(await review.getByRole("button", { name: "确认关联" }).count(), 0)
  const linked = await (await admin.request.get("/api" + paperPath)).json()
  assert.equal(linked.matchedWorkId, "W125")
  assert.equal(linked.title, "Changed during review")
  assert.equal(linked.doi, "10.1/amb")

  const concurrentPath = await submitMatch(page, "Browser concurrent OpenAlex binding")
  review = await openReview(page)
  const candidates = review.getByLabel("OpenAlex 候选作品")
  await candidates.selectOption("W125")
  await review.getByRole("button", { name: "确认关联" }).click()
  await review.getByRole("alert").filter({ hasText: "该作品已有论文关联，请选择其他候选或重新匹配" }).waitFor()
  await candidates.selectOption("W124")
  await candidates.selectOption("W125")
  const concurrent = await (await admin.request.get("/api" + concurrentPath)).json()
  await write(admin, concurrentPath + "/match", { version: concurrent.version, workId: "W124" })
  await review.getByRole("button", { name: "确认关联" }).click()
  await review.getByRole("status").filter({ hasText: "已关联 OpenAlex 作品 W124" }).waitFor()
  assert.deepEqual(await field(review, "OpenAlex 作品 ID").allTextContents(), ["W124", "W125"])

  const movedPath = await submitMatch(page, "Browser review with a different source")
  const moved = await (await admin.request.get("/api" + movedPath)).json()
  await write(admin, movedPath, { version: moved.version, sourceId: "S124", year, authors, doi, title: moved.title }, 200, "PATCH")
  const oldItemsPath = "**/api" + new URL(page.url()).pathname + "/items?*"
  await page.route(oldItemsPath, async (route) => {
    const response = await route.fetch()
    const data = await response.json()
    for (const item of data.items) delete item.result?.candidateSource
    await route.fulfill({ response, json: data })
  })
  await page.reload()
  review = await openReview(page)
  assert.deepEqual(await field(review, "出版来源").allTextContents(), ["S124", "旧任务未记录出版来源"])
  await review.getByRole("button", { name: "确认关联" }).click()
  await review.getByRole("alert").filter({ hasText: "候选已变化，请重新匹配" }).waitFor()
  assert.equal(await review.getByRole("link", { name: "查看论文" }).getAttribute("href"), movedPath)

  const reader = await browser.newPage({ baseURL: url })
  await activate(admin, reader, "alex.browser.reader", "READ_ONLY")
  await reader.goto(url + jobPath)
  await reader.getByRole("heading", { name: "无权访问此页面" }).waitFor()
  assert.equal((await write(reader, paperPath + "/match", { version: linked.version, workId: "W124" }, 403)).code, "ACCESS_DENIED")
}))
