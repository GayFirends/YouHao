import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

async function skipGuide(page: Page) {
  const skip = page.getByRole('button', { name: '跳过引导' })
  if (await skip.isVisible()) await skip.click()
}

async function addRecord(page: Page, station = '测试能源站') {
  await page.getByRole('button', { name: '记一笔加油', exact: true }).click()
  await page.getByLabel('日期', { exact: true }).fill('2026-09-18')
  await page.getByLabel('当前里程 km', { exact: true }).fill('12860')
  await page.getByLabel('加油量 L', { exact: true }).fill('35.2')
  await page.getByLabel('实付金额 元', { exact: true }).fill('255.4')
  await page.getByLabel('加油站 选填', { exact: true }).fill(station)
  await page.getByLabel('备注 选填', { exact: true }).fill('端到端测试记录')
  await page.getByRole('button', { name: '保存记录', exact: true }).click()
  await expect(page.getByText('加油记录已保存')).toBeVisible()
}

test.beforeEach(async ({ page }) => {
  await page.goto('/')
  await page.getByRole('heading', { name: '每一程，都心中有数。' }).waitFor()
})

test('首次使用引导解释车辆、满箱油耗和本地数据', async ({ page }) => {
  await expect(page.getByRole('heading', { name: '从一辆车开始' })).toBeVisible()
  await page.getByRole('button', { name: '下一步' }).click()
  await expect(page.getByRole('heading', { name: '两次满箱，算出真实油耗' })).toBeVisible()
  await page.getByRole('button', { name: '下一步' }).click()
  await expect(page.getByRole('heading', { name: '离线可用，也能安心同步' })).toBeVisible()
  await page.getByRole('button', { name: '记第一笔加油' }).click()
  await expect(page.getByRole('heading', { name: '记一笔加油' })).toBeVisible()
})

test('可以新增并编辑一笔加油记录', async ({ page }) => {
  await skipGuide(page)
  await addRecord(page)

  await page.getByRole('button', { name: /编辑 2026-09-18 的加油记录/ }).click()
  await page.getByLabel('加油站 选填', { exact: true }).fill('更新后的能源站')
  await page.getByRole('button', { name: '保存记录', exact: true }).click()

  await expect(page.getByText('记录已更新')).toBeVisible()
  await expect(page.getByText('更新后的能源站')).toBeVisible()
})

test('可以导出并合并导入 JSON 备份', async ({ page }) => {
  await skipGuide(page)
  const compact = (page.viewportSize()?.width || 1280) <= 720
  await page.getByRole('button', { name: compact ? '同步' : '数据与同步', exact: true }).click()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 JSON' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/^fuel-track-\d{4}-\d{2}-\d{2}\.json$/)

  const backup = {
    version: 1,
    exportedAt: '2026-09-18T00:00:00.000Z',
    vehicles: [],
    records: [],
  }
  await page.locator('input[type="file"]').setInputFiles({
    name: 'fuel-track-test.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(backup)),
  })
  await expect(page.getByText('备份已合并导入')).toBeVisible()
})

test('移动端底部导航可以切换到加油账本', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await skipGuide(page)
  await page.getByRole('button', { name: '记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '加油账本' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '底部导航' })).toBeVisible()
})

test('主要页面没有严重的自动化无障碍问题', async ({ page }) => {
  await skipGuide(page)
  const results = await new AxeBuilder({ page }).disableRules(['color-contrast']).analyze()
  expect(results.violations.filter((item) => ['critical', 'serious'].includes(item.impact || ''))).toEqual([])
})
