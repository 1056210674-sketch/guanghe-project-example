import { execFileSync } from 'node:child_process'
import console from 'node:console'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const javaOpenApi = resolve(
  process.env.JAVA_BROWSER_OPENAPI ?? resolve(
    repositoryRoot,
    '..',
    'guanghe-bankend',
    'src',
    'main',
    'resources',
    'contracts',
    'openapi',
    'browser-openapi.yaml',
  ),
)
const generatedApi = resolve(repositoryRoot, 'src', 'api', 'generated', 'browser-api.ts')
const cli = resolve(repositoryRoot, 'node_modules', 'openapi-typescript', 'bin', 'cli.js')
const temporaryDirectory = mkdtempSync(resolve(tmpdir(), 'guanghe-browser-contract-'))
const candidate = resolve(temporaryDirectory, 'browser-api.ts')
const normalize = (value) => value.replaceAll('\r\n', '\n')

try {
  execFileSync(process.execPath, [cli, javaOpenApi, '-o', candidate], {
    cwd: repositoryRoot,
    stdio: 'pipe',
  })
  const expected = normalize(readFileSync(generatedApi, 'utf8'))
  const actual = normalize(readFileSync(candidate, 'utf8'))
  if (actual !== expected) {
    console.error('BROWSER_OPENAPI_GENERATED_TYPES=STALE')
    console.error('Run npm run generate:api and review the generated contract diff.')
    process.exitCode = 1
  } else {
    console.log('BROWSER_OPENAPI_GENERATED_TYPES=PASS')
  }
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true })
}
