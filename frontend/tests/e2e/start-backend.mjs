import { spawn } from 'node:child_process'
import { readdir, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const platformDirectory = path.resolve(frontendDirectory, '..')
const targetDirectory = path.join(platformDirectory, 'backend', 'target')

let jarNames
try {
  jarNames = (await readdir(targetDirectory))
    .filter(name => /^building-safety-api-.*\.jar$/u.test(name) && !name.endsWith('.original'))
} catch {
  jarNames = []
}

const jars = await Promise.all(jarNames.map(async name => {
  const fullPath = path.join(targetDirectory, name)
  return { fullPath, modifiedAt: (await stat(fullPath)).mtimeMs }
}))
jars.sort((left, right) => right.modifiedAt - left.modifiedAt)

if (!jars.length) {
  console.error('未找到后端可执行 JAR。请先在项目根目录运行 scripts\\build.ps1。')
  process.exit(1)
}

const javaExecutable = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java'

const child = spawn(javaExecutable, ['-jar', jars[0].fullPath], {
  cwd: platformDirectory,
  env: process.env,
  stdio: 'inherit',
  windowsHide: true
})

let stopping = false
function stop(signal = 'SIGTERM') {
  if (stopping || child.exitCode !== null) return
  stopping = true
  child.kill(signal)
}

process.once('SIGINT', () => stop('SIGINT'))
process.once('SIGTERM', () => stop('SIGTERM'))
process.once('SIGHUP', () => stop('SIGTERM'))

child.once('error', error => {
  console.error(`后端启动失败：${error.message}`)
  process.exitCode = 1
})

child.once('exit', (code, signal) => {
  if (!stopping && code !== 0) {
    console.error(`后端进程异常退出：${signal || code}`)
  }
  process.exitCode = code ?? (stopping ? 0 : 1)
})
