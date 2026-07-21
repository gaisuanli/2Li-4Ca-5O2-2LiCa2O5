import { expect, test as base } from '@playwright/test'

export const test = base.extend({
  page: async ({ page }, use, testInfo) => {
    const browserErrors = []

    page.on('pageerror', error => {
      browserErrors.push(`pageerror: ${error.stack || error.message}`)
    })
    page.on('console', message => {
      if (message.type() === 'error') {
        const location = message.location()
        // Chromium requests this browser decoration even though the app does not
        // declare one. Keep the gate focused on application/runtime failures.
        if (location.url && new URL(location.url).pathname === '/favicon.ico') return
        const source = location.url ? ` (${location.url}:${location.lineNumber || 0})` : ''
        browserErrors.push(`console.error: ${message.text()}${source}`)
      }
    })
    page.on('response', response => {
      if (response.status() >= 500) {
        browserErrors.push(`http ${response.status()}: ${response.request().method()} ${response.url()}`)
      }
    })

    await use(page)

    if (browserErrors.length) {
      await testInfo.attach('browser-errors.txt', {
        body: Buffer.from(browserErrors.join('\n'), 'utf8'),
        contentType: 'text/plain'
      })
    }
    expect.soft(browserErrors, '页面不应出现未处理异常、console.error 或意外 5xx').toEqual([])
  }
})

export { expect }
