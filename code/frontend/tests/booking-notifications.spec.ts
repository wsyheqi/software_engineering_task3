import { test, expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { CommandResult, Inventory, Order, OrderDetail, Quote } from '../src/shared/types'

async function login(page: Page, username: string) {
  await page.goto('/')
  await page.getByLabel('账号', {exact: true}).fill(username)
  await page.getByLabel('密码', {exact: true}).fill('Demo@123456')
  await page.getByRole('button', {name: '登录工作空间', exact: true}).click()
  await expect(page.getByRole('navigation', {name: '工作空间导航'})).toBeVisible()
}

async function post(page: Page, path: string, data: unknown) {
  const session = await (await page.request.get('/api/session')).json()
  const response = await page.request.post('/api' + path, {headers: {[session.csrfHeader]: session.csrfToken, 'Idempotency-Key': randomUUID()}, data})
  expect(response.ok()).toBeTruthy()
  return response.json()
}

test('管理员从真实站内通知查看订单，所有关闭方式均保留通知页及标签', async ({page}) => {
  await login(page, 'reception')
  const orders: Order[] = await (await page.request.get('/api/bookings')).json()
  const source = orders.find(order => order.status === 'CONFIRMED')!
  const detail: OrderDetail = await (await page.request.get('/api/bookings/' + source.id)).json()
  const quotes: Quote[] = await post(page, '/hotel-options/search', {mode: 'CHANGE', orderId: source.id, plan: detail.plan})
  const approval: CommandResult = await post(page, `/bookings/${source.id}/special-changes`, {quoteId: quotes[0]!.quoteId, note: '通知关闭流程回归验证'})
  expect(approval.status).toBe('SUCCEEDED')
  await page.getByRole('button', {name: '退出', exact: true}).click()
  await expect(page.getByRole('button', {name: '登录工作空间', exact: true})).toBeVisible()
  await login(page, 'admin')
  const originalQuote = quotes[0]!
  const quoteResponse = await page.request.get(`/api/quotes/${originalQuote.quoteId}`)
  expect(quoteResponse.ok()).toBeTruthy()
  expect(await quoteResponse.json()).toEqual({id: originalQuote.quoteId, snapshot: originalQuote.snapshot})
  await expect.poll(async () => {
    const notifications = await (await page.request.get('/api/notifications')).json()
    return notifications.some((notice: {orderId: string}) => notice.orderId === source.id)
  }, {timeout: 25000, intervals: [500, 1000]}).toBeTruthy()
  await page.getByRole('navigation').getByRole('button', {name: '通知与待办'}).click()
  const noticesTab = page.getByRole('button', {name: /^站内通知/})
  await noticesTab.click()
  const notice = page.locator('.notification-row').filter({hasText: source.code}).first()
  const dialog = page.getByRole('dialog', {name: '订单 ' + source.code, exact: true})
  for (const method of ['footer', 'cross', 'escape', 'backdrop']) {
    await notice.getByRole('button', {name: '查看相关订单'}).click()
    await expect(dialog).toBeVisible()
    await expect(page.locator('.breadcrumb strong')).toHaveText('通知与待办')
    if (method === 'footer') await dialog.getByRole('button', {name: '关闭详情', exact: true}).click()
    if (method === 'cross') await dialog.getByRole('button', {name: '关闭', exact: true}).click()
    if (method === 'escape') await page.keyboard.press('Escape')
    if (method === 'backdrop') await page.locator('.modal-backdrop').click({position: {x: 5, y: 5}})
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('heading', {name: '通知与待办', exact: true})).toBeVisible()
    await expect(noticesTab).toHaveClass(/active/)
    await expect(notice).toBeVisible()
    await expect(page.getByRole('heading', {name: '订单与安置', exact: true})).toHaveCount(0)
  }
  await expect(notice).not.toHaveClass(/unread/)
  await page.screenshot({path: '../docs/screenshots/notification-return.png', fullPage: true, animations: 'disabled'})
  await notice.getByRole('button', {name: '查看相关订单'}).click()
  let orderListRequests = 0
  page.on('request', request => {
    if (request.method() === 'GET' && request.url().endsWith('/api/bookings')) orderListRequests++
  })
  await dialog.getByRole('button', {name: '确认本方意见', exact: true}).click()
  await expect(dialog.getByText('管理员：已确认 · 酒店：待确认', {exact: true})).toBeVisible()
  expect(orderListRequests).toBe(0)
})

test('管理员关闭待办关联订单后仍在原业务待办标签', async ({page}) => {
  await login(page, 'admin')
  await page.getByRole('navigation').getByRole('button', {name: '通知与待办'}).click()
  const todoTab = page.getByRole('button', {name: /^业务待办/})
  await page.getByRole('button', {name: '查看与处理', exact: true}).first().click()
  const dialog = page.getByRole('dialog', {name: /^订单 CR/})
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', {name: '关闭详情', exact: true}).click()
  await expect(dialog).toHaveCount(0)
  await expect(todoTab).toHaveClass(/active/)
  await expect(page.getByRole('heading', {name: '通知与待办', exact: true})).toBeVisible()
})

test('接待人员单选房型，切换后只预订最终选择的房型', async ({page}) => {
  await login(page, 'reception')
  const picker = page.getByRole('group', {name: '房型与间数', exact: true})
  const room = picker.getByRole('combobox', {name: '房型', exact: true})
  const quantity = picker.getByRole('spinbutton', {name: '房间数量', exact: true})
  await expect(room).toHaveValue('DOUBLE')
  await quantity.fill('2')
  await room.selectOption('SINGLE')
  await expect(quantity).toHaveValue('2')
  await expect(picker.getByRole('checkbox')).toHaveCount(0)
  expect(await room.evaluate((element: HTMLSelectElement) => element.multiple)).toBeFalsy()
  await page.getByLabel('整单预算 / 元', {exact: true}).fill('8000')
  const saved = page.waitForResponse(response => response.url().endsWith('/api/demands') && response.request().method() === 'POST')
  await page.getByRole('button', {name: '查询酒店', exact: true}).click()
  const demand = await saved
  expect(demand.request().postDataJSON().items).toEqual([{code: 'SINGLE', quantity: 2}])
  await expect(page.locator('.hotel-option')).toHaveCount(3)
  await page.screenshot({path: '../docs/screenshots/single-room-booking.png', fullPage: true, animations: 'disabled'})
  await page.getByRole('button', {name: '查看方案并确认', exact: true}).first().click()
  const quote = page.getByRole('dialog', {name: '确认住宿方案', exact: true})
  await expect(quote.getByRole('cell', {name: '雅致单人房', exact: true})).toHaveCount(3)
  await expect(quote.getByRole('cell', {name: '舒适双床房', exact: true})).toHaveCount(0)
  await quote.getByRole('checkbox').check()
  const booking = page.waitForResponse(response => response.url().endsWith('/api/bookings') && response.request().method() === 'POST')
  await quote.getByRole('button', {name: '确认并自动预订'}).click()
  const result: CommandResult = await (await booking).json()
  expect(result.status).toBe('SUCCEEDED')
  const detail: OrderDetail = await (await page.request.get('/api/bookings/' + result.data!.orderId)).json()
  expect(detail.items.map(item => ({code: item.code, quantity: item.quantity}))).toEqual([{code: 'SINGLE', quantity: 2}])
  expect(detail.items).toHaveLength(1)
  expect(detail.nights.reduce((sum, night) => sum + night.quantity, 0)).toBe(6)
  const order = page.getByRole('dialog', {name: '订单 ' + detail.order.code, exact: true})
  await order.getByRole('button', {name: '整单取消', exact: true}).click()
  await page.getByRole('dialog', {name: '整单取消', exact: true}).getByRole('button', {name: '确认处理'}).click()
  await expect(order.locator('.badge').first()).toHaveText('已取消')
})

test('移动端保持房型单选，无效间数不提交查询', async ({page}) => {
  await page.setViewportSize({width: 390, height: 844})
  await login(page, 'reception')
  const picker = page.getByRole('group', {name: '房型与间数', exact: true})
  const demandRequests: unknown[] = []
  page.on('request', request => { if (request.url().endsWith('/api/demands')) demandRequests.push(request.postDataJSON()) })
  await expect(picker.getByRole('combobox')).toHaveCount(1)
  const room = picker.getByRole('combobox', {name: '房型', exact: true})
  await room.selectOption('SINGLE')
  const quantity = picker.getByRole('spinbutton', {name: '房间数量', exact: true})
  await quantity.fill('0')
  await page.getByRole('button', {name: '查询酒店', exact: true}).click()
  expect(await quantity.evaluate((element: HTMLInputElement) => element.validity.valid)).toBeFalsy()
  expect(demandRequests).toHaveLength(0)
  await quantity.fill('2')
  await room.selectOption('SUITE')
  await room.selectOption('SINGLE')
  await expect(quantity).toHaveValue('2')
  const saved = page.waitForResponse(response => response.url().endsWith('/api/demands'))
  await page.getByRole('button', {name: '查询酒店', exact: true}).click()
  expect((await saved).request().postDataJSON().items).toEqual([{code: 'SINGLE', quantity: 2}])
  await expect(page.getByRole('button', {name: '查询酒店', exact: true})).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy()
})

test('查询期间修改房型后不展示旧条件的酒店结果', async ({page}) => {
  await login(page, 'reception')
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let arrived!: () => void
  const pending = new Promise<void>(resolve => { arrived = resolve })
  await page.route('**/api/hotel-options/search', async route => {
    const response = await route.fetch()
    arrived()
    await gate
    await route.fulfill({response})
  })
  await page.getByRole('button', {name: '查询酒店', exact: true}).click()
  await pending
  await page.getByRole('group', {name: '房型与间数', exact: true}).getByRole('combobox', {name: '房型', exact: true}).selectOption('SINGLE')
  release()
  await expect(page.getByRole('button', {name: '查询酒店', exact: true})).toBeEnabled()
  await expect(page.locator('.hotel-option')).toHaveCount(0)
  await expect(page.getByRole('heading', {name: '为来宾寻找合适的住宿'})).toBeVisible()
})

for (const {title, maxDistance, replacement} of [
  {title: '提交时某晚缺房，按原预算和距离刷新替代酒店', maxDistance: '20', replacement: '城市会议酒店'},
  {title: '提交时某晚缺房且无合格替代，展示空结果', maxDistance: '4', replacement: ''},
]) {
  test(title, async ({page, browser, baseURL}) => {
    await login(page, 'reception')
    await page.getByLabel('整单预算 / 元', {exact: true}).fill('1000')
    await page.getByLabel('最大距离 / km', {exact: true}).fill(maxDistance)
    const initialSearch = page.waitForResponse(response => response.url().endsWith('/api/hotel-options/search'))
    await page.getByRole('button', {name: '查询酒店', exact: true}).click()
    const initialResponse = await initialSearch
    const quotes: Quote[] = await initialResponse.json()
    const originalQuote = quotes.find(quote => quote.snapshot.hotelId === 'h1')!
    const night = originalQuote.snapshot.lines[0]!
    const originalOrders: Order[] = await (await page.request.get('/api/bookings')).json()
    await page.locator('.hotel-option').filter({hasText: '学府宾馆'}).getByRole('button', {name: '查看方案并确认'}).click()
    const dialog = page.getByRole('dialog', {name: '确认住宿方案'})
    await dialog.getByRole('checkbox').check()

    const staffContext = await browser.newContext({baseURL})
    try {
      const staff = await staffContext.newPage()
      await login(staff, 'hotel')
      const {csrfHeader, csrfToken} = await (await staff.request.get('/api/session')).json()
      const inventoryPath = '/api/hotels/h1/daily-inventory'
      async function readNight() {
        const response = await staff.request.get(`${inventoryPath}?from=${night.date}&to=${night.date}`)
        expect(response.ok()).toBeTruthy()
        const rows: Inventory[] = await response.json()
        return rows.find(row => row.id === night.inventoryId)!
      }
      async function setQuota(row: Inventory, quota: number) {
        const response = await staff.request.put(inventoryPath, {
          headers: {[csrfHeader]: csrfToken, 'Idempotency-Key': randomUUID()},
          data: {entries: [{id: row.id, price: row.price, quota, version: row.version}]},
        })
        expect((await response.json()).businessCode).toBe('INVENTORY_UPDATED')
      }
      const originalNight = await readNight()
      try {
        await setQuota(originalNight, originalNight.occupied + originalNight.blocked)
        const booking = page.waitForResponse(response => response.url().endsWith('/api/bookings') && response.request().method() === 'POST')
        const fallback = page.waitForResponse(response => response.url().endsWith('/api/hotel-options/search'))
        await dialog.getByRole('button', {name: '确认并自动预订'}).click()
        expect((await (await booking).json()).businessCode).toBe('INSUFFICIENT_INVENTORY')
        const retry = await fallback
        expect(retry.request().postDataJSON()).toEqual(initialResponse.request().postDataJSON())
        const alternatives: Quote[] = await retry.json()
        expect(alternatives.map(quote => quote.snapshot.hotelName)).toEqual(replacement ? [replacement] : [])
        await expect(dialog).toHaveCount(0)
        await expect(page.getByRole('heading', {name: '可选替代酒店', exact: true})).toBeVisible()
        await expect(page.getByText('「学府宾馆」部分住宿晚房源不足，整单预订失败。', {exact: true})).toBeVisible()
        await expect(page.locator('.hotel-option')).toHaveCount(replacement ? 1 : 0)
        const currentOrders: Order[] = await (await page.request.get('/api/bookings')).json()
        expect(currentOrders.map(order => order.id).sort()).toEqual(originalOrders.map(order => order.id).sort())
        expect((await readNight()).occupied).toBe(originalNight.occupied)
        if (replacement) {
          expect(alternatives[0]!.snapshot.plan).toEqual(originalQuote.snapshot.plan)
          await page.locator('.hotel-option').filter({hasText: replacement}).getByRole('button', {name: '查看方案并确认'}).click()
          await expect(dialog.getByRole('checkbox')).not.toBeChecked()
          await expect(dialog.getByRole('button', {name: '确认并自动预订'})).toBeDisabled()
        } else {
          await expect(page.getByRole('heading', {name: '暂无符合原条件的替代酒店'})).toBeVisible()
        }
      } finally {
        const currentNight = await readNight()
        if (currentNight.quota !== originalNight.quota) await setQuota(currentNight, originalNight.quota)
      }
    } finally {
      await staffContext.close()
    }
  })
}
