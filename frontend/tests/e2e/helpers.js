import { expect } from './fixtures.js'

export function isApiResponse(response, pathname, method = 'GET') {
  const url = new URL(response.url())
  return url.pathname === `/api${pathname}` && response.request().method() === method
}

export async function loginAsSupervisor(page) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill('supervisor')
  await page.getByLabel('密码').fill('Safe@123')

  const loginResponsePromise = page.waitForResponse(response => (
    isApiResponse(response, '/auth/login', 'POST')
  ))
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const loginResponse = await loginResponsePromise

  expect(loginResponse.ok()).toBeTruthy()
  await expect(page).toHaveURL(/\/dashboard$/u)
  await expect(page.locator('.app-shell[data-route-path="/dashboard"]')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: '综合首页' })).toBeVisible()
  await expect(page.getByLabel('选择当前工地')).toHaveValue('1')
}

export async function openModule(page, name, path) {
  await page.getByRole('navigation', { name: '主要导航' })
    .getByRole('link', { name, exact: true })
    .click()
  await expect(page).toHaveURL(new RegExp(`${path.replace('/', '\\/')}$`, 'u'))
  await expect(page.locator(`.app-shell[data-route-path="${path}"]`)).toBeVisible()
}

export async function readJson(response) {
  expect(response.ok(), `${response.request().method()} ${response.url()} 应成功`).toBeTruthy()
  return response.json()
}
