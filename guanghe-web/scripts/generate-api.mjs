import { execFileSync } from 'node:child_process'
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
const cli = resolve(repositoryRoot, 'node_modules', 'openapi-typescript', 'bin', 'cli.js')
const generatedApi = resolve(repositoryRoot, 'src', 'api', 'generated', 'browser-api.ts')

execFileSync(process.execPath, [cli, javaOpenApi, '-o', generatedApi], {
  cwd: repositoryRoot,
  stdio: 'inherit',
})
