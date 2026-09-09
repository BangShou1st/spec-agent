import { test, expect } from '@playwright/test'

const skills = [
  { skillId: 's1', name: 'Research', description: 'Research helper', sourceKind: 'BUILTIN', versionId: 'v1', enabled: true, createdAt: '2026-01-01T00:00:00Z' },
  { skillId: 's2', name: 'Mine', description: 'Personal notes', sourceKind: 'UPLOAD_ZIP', versionId: null, enabled: false, createdAt: '2026-01-02T00:00:00Z' },
]

const staged = [
  { stagedImportId: 'st1', sourceKind: 'GIT', sourceIdentity: 'https://example.com/skill.git', manifest: '# Helper Helps with things.', totalBytes: 2048, fileCount: 3, contentHash: 'abc123', status: 'STAGED', rejectedReason: null, createdAt: '2026-01-03T00:00:00Z' },
]

async function mockSkills(page, opts: { installed?: typeof skills; stagedImports?: typeof staged } = {}) {
  const installed = opts.installed ?? [...skills]
  const stagedImports = opts.stagedImports ?? [...staged]
  await page.route('**/api/v1/skills**', async (route) => {
    const req = route.request()
    const method = req.method()
    const url = new URL(req.url())
    const path = url.pathname.replace('/api/v1/skills', '') || '/'
    const json = (status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (method === 'GET' && path === '/') return json(200, installed)
    if (method === 'GET' && path === '/imports') return json(200, stagedImports)
    if (method === 'POST' && path === '/imports/git') {
      return json(201, { stagedImportId: 'st1', name: 'Helper', description: 'Helps', contentHash: 'abc123', fileCount: 3, totalBytes: 2048 })
    }
    if (method === 'POST' && path === '/imports/zip') {
      return json(201, { stagedImportId: 'st1', name: 'Helper', description: 'Helps', contentHash: 'abc123', fileCount: 3, totalBytes: 2048 })
    }
    if (path === '/imports/st1') {
      if (method === 'GET') return json(200, stagedImports[0])
    }
    if (method === 'POST' && path === '/imports/st1/install') {
      installed.push({ skillId: 's3', name: 'Helper', description: 'Helps', sourceKind: 'GIT', versionId: 'v3', enabled: false, createdAt: '2026-01-04T00:00:00Z' })
      stagedImports.length = 0
      return json(200, { skillId: 's3', skillRowId: 'row', versionId: 'v3', versionNo: 1 })
    }
    if (method === 'POST' && path === '/imports/st1/reject') {
      stagedImports.length = 0
      return route.fulfill({ status: 204, body: '' })
    }
    if (method === 'POST' && path === '/s2/enable') {
      installed.find((s) => s.skillId === 's2')!.enabled = true
      return route.fulfill({ status: 204, body: '' })
    }
    if (method === 'GET' && path === '/s1') return json(200, { skillId: 's1', name: 'Research', description: 'Research helper', sourceKind: 'BUILTIN', sourceIdentity: 'builtin', versionId: 'v1', enabled: true, createdAt: 'x', updatedAt: 'x' })
    if (method === 'GET' && path === '/s1/versions') return json(200, [{ id: 'v1', versionNo: 1, contentHash: 'h', fileCount: 2, totalBytes: 512, createdAt: 'x' }])
    if (method === 'GET' && path === '/s1/resources') return json(200, [{ path: 'SKILL.md', kind: 'doc', sizeBytes: 120, sha256: 's' }])
    if (method === 'GET' && path === '/s1/resources/read') return json(200, { relativePath: 'SKILL.md', content: 'bounded body', truncated: true, totalChars: 9000, sha256: 's', versionId: 'v1' })
    if (method === 'DELETE' && path === '/s1') {
      const i = installed.findIndex((s) => s.skillId === 's1')
      if (i >= 0) installed.splice(i, 1)
      return route.fulfill({ status: 204, body: '' })
    }
    return route.fallback()
  })
}

test('skills list shows installed and staged, enables a skill', async ({ page }) => {
  await mockSkills(page)
  await page.goto('/settings/skills')
  await expect(page.getByTestId('skill-row-s1')).toBeVisible()
  await expect(page.getByTestId('skill-status-s2')).toContainText('已禁用')
  await expect(page.getByTestId('staged-section')).toBeVisible()
  await page.getByTestId('skill-more-s2').locator('summary').click()
  await page.getByTestId('skill-enable-s2').click()
  await expect(page.getByTestId('skill-status-s2')).toContainText('已启用')
})

test('git stage, review, and install flow', async ({ page }) => {
  await mockSkills(page)
  await page.goto('/settings/skills')
  await page.getByTestId('add-skill').click()
  await page.getByTestId('import-tab-git').click()
  await page.getByTestId('git-url').fill('https://example.com/skill.git')
  await page.getByTestId('stage-git').click()
  await expect(page.getByTestId('staged-review')).toBeVisible()
  await expect(page.getByTestId('staged-name')).toContainText('Helper')
  await page.getByTestId('install-staged').click()
  await expect(page.getByTestId('staged-review')).toHaveCount(0)
  await expect(page.getByTestId('skill-row-s3')).toBeVisible()
})

test('skill detail reads a truncated resource and deletes with confirmation', async ({ page }) => {
  await mockSkills(page)
  await page.goto('/settings/skills/s1')
  await expect(page.getByTestId('skill-detail-name')).toContainText('Research')
  await page.getByTestId('skill-resource-SKILL.md').click()
  await expect(page.getByTestId('resource-truncated')).toBeVisible()
  await page.getByTestId('skill-detail-delete').click()
  await page.getByTestId('skill-detail-delete-confirm').click()
  await expect(page).toHaveURL(/\/settings\/skills$/)
})
