import { expect, test } from './fixtures.js'
import { isApiResponse, loginAsSupervisor, openModule, readJson } from './helpers.js'

test.describe.serial('PC 浏览器核心业务冒烟', () => {
  test('登录守卫、登录和桌面工作台', async ({ page }) => {
    await page.goto('/tower')
    await expect(page).toHaveURL(/\/login$/u)
    await expect(page.getByRole('heading', { level: 2, name: '进入监管工作台' })).toBeVisible()

    await loginAsSupervisor(page)

    expect(await page.evaluate(() => [window.innerWidth, window.innerHeight])).toEqual([1440, 900])
    await expect(page.getByText('项目监管员', { exact: true })).toBeVisible()
    await expect(page.getByText('项目主管', { exact: true })).toBeVisible()
    await expect(page.getByText('设备总数', { exact: true })).toBeVisible()
  })

  test('设备列表服务端分页可前进并切换每页条数', async ({ page }) => {
    await loginAsSupervisor(page)

    const firstPagePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return isApiResponse(response, '/devices')
        && url.searchParams.get('page') === '1'
        && url.searchParams.get('pageSize') === '10'
    })
    await openModule(page, '设备总览', '/devices')
    const firstPagePayload = await readJson(await firstPagePromise)

    expect(firstPagePayload.data.total).toBe(12)
    const table = page.getByRole('table', { name: '设备清单' })
    const rows = table.locator('tbody tr')
    await expect(rows).toHaveCount(10)

    const pagination = page.getByRole('navigation', { name: '设备清单分页' })
    await expect(pagination.locator('output')).toHaveText('1 / 2 页')

    const secondPagePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return isApiResponse(response, '/devices')
        && url.searchParams.get('page') === '2'
        && url.searchParams.get('pageSize') === '10'
    })
    await pagination.getByRole('button', { name: '转到下一页' }).click()
    const secondPagePayload = await readJson(await secondPagePromise)

    expect(secondPagePayload.data.items).toHaveLength(2)
    await expect(rows).toHaveCount(2)
    await expect(pagination.locator('output')).toHaveText('2 / 2 页')
    await expect(pagination.getByRole('button', { name: '转到下一页' })).toBeDisabled()

    const expandedPagePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return isApiResponse(response, '/devices')
        && url.searchParams.get('page') === '1'
        && url.searchParams.get('pageSize') === '20'
    })
    await pagination.getByLabel('每页条数').selectOption('20')
    await readJson(await expandedPagePromise)

    await expect(rows).toHaveCount(12)
    await expect(pagination.locator('output')).toHaveText('1 / 1 页')
  })

  test('塔吊遥测驱动指标、历史和三维模型参数', async ({ page }) => {
    await loginAsSupervisor(page)

    const latestPromise = page.waitForResponse(response => isApiResponse(response, '/telemetry/latest'))
    const modelAssetPromise = page.waitForResponse(response => (
      new URL(response.url()).pathname === '/models/tower-crane.glb'
    ))
    await openModule(page, '塔吊分析', '/tower')

    const latestPayload = await readJson(await latestPromise)
    const modelAssetResponse = await modelAssetPromise
    expect(modelAssetResponse.ok()).toBeTruthy()

    await expect(page.getByRole('heading', { level: 1, name: '塔吊分析' })).toBeVisible()
    const model = page.getByRole('img', { name: /塔吊三维模型/u })
    await expect(model).toBeVisible()
    await expect(model).toHaveAttribute('data-model-state', 'READY', { timeout: 60_000 })
    await expect(model.getByText('回转 / 吊钩 / 幅度节点已绑定', { exact: true })).toBeVisible()

    const rotation = latestPayload.data.find(item => item.code === 'rotation')
    expect(rotation).toBeTruthy()
    await expect(model.locator('.model-caption strong')).toContainText(`${Number(rotation.value).toFixed(1)}°`)

    const metricCards = page.locator('.tower-metrics .parameter-card')
    await expect(metricCards).toHaveCount(7)
    await expect(metricCards.filter({ hasText: '风速' }).locator('strong')).not.toContainText('—')

    const historyRows = page.getByRole('table', { name: '塔吊遥测历史' }).locator('tbody tr')
    await expect(historyRows.first()).toBeVisible()
    expect(await historyRows.count()).toBeGreaterThan(0)
  })

  test('告警筛选、列表和处置详情路径', async ({ page }) => {
    await loginAsSupervisor(page)

    const listPromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return isApiResponse(response, '/alarms') && !url.searchParams.has('status')
    })
    await openModule(page, '告警中心', '/alarms')
    const listPayload = await readJson(await listPromise)

    expect(listPayload.data.total).toBeGreaterThan(0)
    const table = page.getByRole('table', { name: '告警查询结果' })
    await expect(table.locator('tbody tr')).toHaveCount(listPayload.data.items.length)
    await expect(page.locator('.alarm-detail')).toContainText(listPayload.data.items[0].code)

    const pendingPromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return isApiResponse(response, '/alarms') && url.searchParams.get('status') === 'PENDING'
    })
    await page.locator('.alarm-filter').getByLabel('状态').selectOption('PENDING')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const pendingPayload = await readJson(await pendingPromise)

    expect(pendingPayload.data.items.length).toBeGreaterThan(0)
    expect(pendingPayload.data.items.every(item => item.status === 'PENDING')).toBeTruthy()
    await expect(table.locator('tbody tr')).toHaveCount(pendingPayload.data.items.length)
    await expect(table.locator('tbody tr').first()).toContainText('待确认')
    await expect(page.locator('.alarm-detail').getByRole('button', { name: '确认并开始处理' })).toBeVisible()
  })

  test('AI Agent 新建会话并完成 DEMO 问答', async ({ page }) => {
    await loginAsSupervisor(page)

    const configPromise = page.waitForResponse(response => isApiResponse(response, '/agent/config'))
    const providerConfigPromise = page.waitForResponse(response => isApiResponse(response, '/agent/provider-config'))
    await openModule(page, 'AI Agent 问答', '/agent')
    const configPayload = await readJson(await configPromise)
    const providerConfigPayload = await readJson(await providerConfigPromise)

    expect(configPayload.data.mode).toBe('DEMO')
    expect(configPayload.data.available).toBe(true)
    expect(providerConfigPayload.data.effectiveMode).toBe('DEMO')
    expect(providerConfigPayload.data.credentialStorageAvailable).toBe(false)
    expect(Object.keys(providerConfigPayload.data)).not.toContain('apiKey')
    expect(Object.keys(providerConfigPayload.data)).not.toContain('encryptedApiKey')
    await expect(page.locator('.agent-service-summary')).toContainText('演示模式')
    await expect(page.locator('.agent-provider-panel')).toContainText('密钥存储不可用')
    await expect(page.locator('.agent-provider-track')).toContainText('本地演示')

    await page.getByRole('button', { name: '新建会话' }).click()
    const question = '请汇总当前工地未闭环告警（PC E2E）'
    await page.getByRole('textbox', { name: '输入问题' }).fill(question)

    const createConversationPromise = page.waitForResponse(response => (
      isApiResponse(response, '/agent/conversations', 'POST')
    ))
    const sendMessagePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return response.request().method() === 'POST'
        && /^\/api\/agent\/conversations\/\d+\/messages$/u.test(url.pathname)
    })
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await readJson(await createConversationPromise)
    const answerPayload = await readJson(await sendMessagePromise)
    expect(answerPayload.data.assistantMessage.content).toContain('【演示模式】')

    await expect(page.locator('.agent-message.message-user pre')).toHaveText(question)
    await expect(page.locator('.agent-message.message-assistant pre')).toContainText('【演示模式】')
    await expect(page.getByRole('textbox', { name: '输入问题' })).toBeEnabled()
    await expect(page.getByRole('textbox', { name: '输入问题' })).toHaveValue('')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  })
})
