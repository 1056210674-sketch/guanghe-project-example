export type BrowserTraceFlags = '00' | '01'

export interface BrowserTraceContext {
  readonly traceId: string
  readonly traceFlags: BrowserTraceFlags
}

export interface BrowserRequestTrace {
  readonly context: BrowserTraceContext
  readonly spanId: string
  readonly traceparent: string
}

const TRACE_ID_BYTES = 16
const SPAN_ID_BYTES = 8
const UINT32_RANGE = 0x1_0000_0000

function secureRandomBytes(length: number): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(length)
  globalThis.crypto.getRandomValues(bytes)
  return bytes
}

function nonZeroRandomHex(length: number): string {
  let bytes = secureRandomBytes(length)
  while (bytes.every((value) => value === 0)) {
    bytes = secureRandomBytes(length)
  }
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
}

function configuredSampleRate(): number {
  const configured = Number(import.meta.env.VITE_BROWSER_TRACE_SAMPLE_RATE)
  if (Number.isFinite(configured) && configured >= 0 && configured <= 1) {
    return configured
  }

  // Local/test traffic is fully sampled. Production defaults to the approved
  // low-volume success sampling rate; collector tail sampling remains authoritative.
  return import.meta.env.DEV || import.meta.env.MODE === 'test' ? 1 : 0.1
}

function sampleWithCrypto(rate: number): boolean {
  if (rate <= 0) return false
  if (rate >= 1) return true
  const draw = new Uint32Array(1)
  globalThis.crypto.getRandomValues(draw)
  return draw[0] / UINT32_RANGE < rate
}

export function createBrowserTraceContext(
  options: { sampled?: boolean } = {},
): BrowserTraceContext {
  const sampled = options.sampled ?? sampleWithCrypto(configuredSampleRate())
  return {
    traceId: nonZeroRandomHex(TRACE_ID_BYTES),
    traceFlags: sampled ? '01' : '00',
  }
}

export function createBrowserRequestTrace(
  context: BrowserTraceContext = createBrowserTraceContext(),
): BrowserRequestTrace {
  const spanId = nonZeroRandomHex(SPAN_ID_BYTES)
  return {
    context,
    spanId,
    traceparent: `00-${context.traceId}-${spanId}-${context.traceFlags}`,
  }
}

export function withBrowserTrace<T>(
  action: (context: BrowserTraceContext) => T,
): T {
  return action(createBrowserTraceContext())
}
