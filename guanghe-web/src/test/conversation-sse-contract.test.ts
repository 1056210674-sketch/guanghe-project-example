import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import contract from '../api/contracts/sse/conversation-event-v1.schema.json'

// This is a test-only mirror. Java owns the event contract; production does not
// import a second validator or ship this schema in the Browser bundle.
describe('Java-owned conversation SSE contract', () => {
  it('完整镜像与 Java 权威契约一致，允许平台换行差异', () => {
    const canonical = readFileSync(resolve(process.cwd(),
      '../guanghe-bankend/src/main/resources/contracts/sse/conversation-event-v1.schema.json'), 'utf8')
    const mirror = readFileSync(resolve(process.cwd(),
      'src/api/contracts/sse/conversation-event-v1.schema.json'), 'utf8')
    expect(mirror.replaceAll('\r\n', '\n')).toBe(canonical.replaceAll('\r\n', '\n'))
  })

  it('诊断报告读取有独立 purpose，原监测和其他资源用途仍有效', () => {
    const resource = contract.$defs.resourcePayload
    expect(resource.properties.purpose.enum).toEqual([
      'CATALOG_SUMMARY', 'LATEST_GEO_REPORT', 'GEO_ANSWER_EVIDENCE',
      'GEO_DIAGNOSTIC_REPORT', 'ASSET_INVENTORY',
    ])
    expect(resource.required).toEqual(['kind', 'success', 'runId'])
    expect(resource.properties.kind.enum).toContain('READ')
    expect(contract.properties.eventType.enum).toContain('context.resource')
    expect(resource.additionalProperties).toBe(false)
    expect(resource.properties).not.toHaveProperty('result')
    expect(resource.properties).not.toHaveProperty('inputHash')
  })
})
