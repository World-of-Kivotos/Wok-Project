#!/usr/bin/env node
/**
 * 前端契约机械守卫脚本。
 *
 * webui 没有测试运行器 (无 vitest/jest), 本脚本是它的替代品: 只用 Node 原生能力 (node:fs / node:path /
 * node:assert / node:process) 对源码文本做结构断言, 把 fix/webui-frontend-contract 分支修的 8 条 finding
 * (F013 F014 F055 F056 F057 F058 F059 F060) 钉死, 防止后续改动把它们悄悄改回去。
 *
 * 运行: node webui/scripts/check-frontend-contract.mjs (或 pnpm -C webui check:contract)
 * 判据: 每条断言都必须落到具体文件/行号/件数; 全通过 exit 0, 任一条失败 exit 1 并打印失败明细。
 */

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
const SRC_ROOT = path.resolve(SCRIPT_DIR, '..', 'src')

// ============================================================
// 文件遍历与文本工具 (严禁 Bash find, 自己写递归遍历)
// ============================================================

function walkSourceFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...walkSourceFiles(full))
      continue
    }
    if (/\.tsx?$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

function toRel(file) {
  return path.relative(SRC_ROOT, file).split(path.sep).join('/')
}

/**
 * 把注释 (行注释与块注释) 替换成等长空白, 字符串/模板字面量原样保留, 换行符原样保留 (行号不漂移)。
 *
 * 为什么必须做这一步: mock/handlers.ts、mock/seed.ts 等文件的文件头注释里逐字写着
 * "mirror.wallet 全库零读取方 (F057)" 这类历史留档句子 —— 这正是本仓库鼓励的"解释为什么"的注释规范,
 * 不能因为它命中了被删标识符的名字就误判成"代码里还在用"。下面全部按标识符/属性访问做出现次数统计的断言
 * 都必须在这份去注释文本上跑, 否则会被自己的留档注释坑成假失败。
 */
function stripComments(text) {
  let out = ''
  let inString = null
  let inLineComment = false
  let inBlockComment = false
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i]
    const prev = text[i - 1]
    if (inLineComment) {
      out += c === '\n' ? '\n' : ' '
      if (c === '\n') {
        inLineComment = false
      }
      continue
    }
    if (inBlockComment) {
      out += c === '\n' ? '\n' : ' '
      if (c === '/' && prev === '*') {
        inBlockComment = false
      }
      continue
    }
    if (inString !== null) {
      out += c
      if (c === inString && prev !== '\\') {
        inString = null
      }
      continue
    }
    if (c === '/' && text[i + 1] === '/') {
      inLineComment = true
      out += ' '
      continue
    }
    if (c === '/' && text[i + 1] === '*') {
      inBlockComment = true
      out += ' '
      continue
    }
    if (c === '"' || c === "'" || c === '`') {
      inString = c
      out += c
      continue
    }
    out += c
  }
  return out
}

/** 逐行找 needle 的全部出现位置 (1-indexed 行号), 一行内出现多次记多条。 */
function findOccurrences(text, needle) {
  const hits = []
  const lines = text.split('\n')
  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const line = lines[lineIndex]
    let cursor = 0
    for (;;) {
      const found = line.indexOf(needle, cursor)
      if (found === -1) {
        break
      }
      hits.push(lineIndex + 1)
      cursor = found + needle.length
    }
  }
  return hits
}

/**
 * 提取一个命名函数 (`function <name>(...)  { ... }`) 的函数体源码, 含首尾花括号。
 * 用花括号计数而非"到下一个 function/export 止", 好让它在字符串/模板字面量/注释里出现的花括号
 * 不打乱计数; handleSell/delegateReal 之后紧跟的代码未必是另一个顶层声明 (如 handleSell 之后是 JSX
 * 的 return), 单纯找下一个 function 关键字会把函数体读穿到文件末尾。
 */
function extractFunctionBody(text, functionName) {
  const nameIdx = text.indexOf(`function ${functionName}`)
  if (nameIdx === -1) {
    return null
  }
  const parenOpen = text.indexOf('(', nameIdx)
  if (parenOpen === -1) {
    return null
  }
  let parenDepth = 0
  let i = parenOpen
  for (; i < text.length; i += 1) {
    if (text[i] === '(') {
      parenDepth += 1
    } else if (text[i] === ')') {
      parenDepth -= 1
      if (parenDepth === 0) {
        i += 1
        break
      }
    }
  }
  const braceOpen = text.indexOf('{', i)
  if (braceOpen === -1) {
    return null
  }
  let depth = 0
  let inString = null
  let inLineComment = false
  let inBlockComment = false
  for (let j = braceOpen; j < text.length; j += 1) {
    const c = text[j]
    const prev = text[j - 1]
    if (inLineComment) {
      if (c === '\n') {
        inLineComment = false
      }
      continue
    }
    if (inBlockComment) {
      if (c === '/' && prev === '*') {
        inBlockComment = false
      }
      continue
    }
    if (inString !== null) {
      if (c === inString && prev !== '\\') {
        inString = null
      }
      continue
    }
    if (c === '/' && text[j + 1] === '/') {
      inLineComment = true
      continue
    }
    if (c === '/' && text[j + 1] === '*') {
      inBlockComment = true
      continue
    }
    if (c === '"' || c === "'" || c === '`') {
      inString = c
      continue
    }
    if (c === '{') {
      depth += 1
    } else if (c === '}') {
      depth -= 1
      if (depth === 0) {
        return text.slice(braceOpen, j + 1)
      }
    }
  }
  return null
}

// ============================================================
// 数据准备: 全量读一遍, 后面各条断言共用
// ============================================================

const allFiles = walkSourceFiles(SRC_ROOT)
/*
 * contents 存去注释后的文本 (见 stripComments 注释), 全部断言一律在这份文本上跑。
 * stripComments 逐字符原样保留换行, 故这里的行号与源文件行号完全一致。
 */
const contents = new Map(allFiles.map((file) => [file, stripComments(readFileSync(file, 'utf8'))]))

function requireFile(relPath) {
  const full = path.join(SRC_ROOT, relPath)
  const text = contents.get(full)
  if (text === undefined) {
    throw new Error(`断言依赖的文件不存在: src/${relPath}`)
  }
  return text
}

const results = []

function runCheck(id, description, fn) {
  try {
    const detail = fn()
    results.push({ id, description, ok: true, detail: detail ?? null })
  } catch (error) {
    results.push({
      id,
      description,
      ok: false,
      detail: error instanceof Error ? error.message : String(error),
    })
  }
}

// ============================================================
// 1. [F060] components/ui 传递引用闭包: 每个 .tsx 都必须可从非 ui 文件到达
// ============================================================

runCheck('F060-ui-closure', 'components/ui 下每个组件都在从非-ui 文件出发的引用闭包内', () => {
  const UI_DIR = path.join(SRC_ROOT, 'components', 'ui')
  const uiFiles = allFiles.filter(
    (file) => file.startsWith(UI_DIR + path.sep) && file.endsWith('.tsx'),
  )
  const uiNames = new Set(uiFiles.map((file) => path.basename(file, '.tsx')))

  // 建图: 文本里 "ui/<name>" 紧跟引号的形式, 覆盖 @/components/ui/<name> 与相对路径两种写法。
  const UI_REF_RE = /ui\/([a-zA-Z0-9_-]+)["']/g
  function refsIn(text) {
    const names = new Set()
    UI_REF_RE.lastIndex = 0
    let match
    while ((match = UI_REF_RE.exec(text)) !== null) {
      names.add(match[1])
    }
    return names
  }

  const reachable = new Set()
  const queue = []
  for (const file of allFiles) {
    if (file.startsWith(UI_DIR + path.sep)) {
      continue
    }
    for (const name of refsIn(contents.get(file))) {
      if (!reachable.has(name)) {
        reachable.add(name)
        queue.push(name)
      }
    }
  }
  while (queue.length > 0) {
    const name = queue.pop()
    const uiFile = path.join(UI_DIR, `${name}.tsx`)
    const uiText = contents.get(uiFile)
    if (uiText === undefined) {
      continue
    }
    for (const next of refsIn(uiText)) {
      if (!reachable.has(next)) {
        reachable.add(next)
        queue.push(next)
      }
    }
  }

  const unreachable = [...uiNames].filter((name) => !reachable.has(name))
  assert.equal(
    unreachable.length,
    0,
    `发现不可达的 components/ui 组件 (共 ${String(unreachable.length)} 个), 应已随本轮核销一并删除: ${unreachable
      .map((name) => `components/ui/${name}.tsx`)
      .join(', ')}`,
  )
  return `components/ui 现存 ${String(uiNames.size)} 个组件, 全部可达`
})

// ============================================================
// 2. [F060] hooks 下每个文件都至少有一个 hooks 目录之外的引用方
// ============================================================

runCheck('F060-hooks-referenced', 'hooks/ 下每个文件都至少被一个 hooks 目录之外的文件引用', () => {
  const HOOKS_DIR = path.join(SRC_ROOT, 'hooks')
  const hookFiles = allFiles.filter((file) => file.startsWith(HOOKS_DIR + path.sep))
  assert.ok(hookFiles.length > 0, 'src/hooks 下没有任何文件, 断言无意义, 请检查路径是否变化')

  const HOOKS_REF_RE = /hooks\/([a-zA-Z0-9_-]+)["']/g
  function hookRefsIn(text) {
    const names = new Set()
    HOOKS_REF_RE.lastIndex = 0
    let match
    while ((match = HOOKS_REF_RE.exec(text)) !== null) {
      names.add(match[1])
    }
    return names
  }

  const orphans = []
  for (const hookFile of hookFiles) {
    const basename = path.basename(hookFile).replace(/\.tsx?$/, '')
    let referenced = false
    for (const file of allFiles) {
      if (file.startsWith(HOOKS_DIR + path.sep)) {
        continue
      }
      if (hookRefsIn(contents.get(file)).has(basename)) {
        referenced = true
        break
      }
    }
    if (!referenced) {
      orphans.push(toRel(hookFile))
    }
  }
  assert.equal(
    orphans.length,
    0,
    `hooks 下存在无引用方的文件 (应已随核销删除): ${orphans.join(', ')}`,
  )
  return `hooks 现存 ${String(hookFiles.length)} 个文件, 全部有 hooks 目录之外的引用方`
})

// ============================================================
// 3. [F013][F058] world.player / world.jobs 读取形式的落点限制
// ============================================================

runCheck(
  'F013-F058-mock-identity-leak',
  'world.player / world.jobs 在 pages/** 下 0 出现, components/** 下只允许 shell/TabletShell.tsx 至多 1 次',
  () => {
    const PAGES_DIR = path.join(SRC_ROOT, 'pages')
    const COMPONENTS_DIR = path.join(SRC_ROOT, 'components')
    const SHELL_FILE = path.join(COMPONENTS_DIR, 'shell', 'TabletShell.tsx')
    const NEEDLES = ['world.player', 'world.jobs']

    const pageViolations = []
    for (const file of allFiles) {
      if (!file.startsWith(PAGES_DIR + path.sep)) {
        continue
      }
      const text = contents.get(file)
      for (const needle of NEEDLES) {
        for (const line of findOccurrences(text, needle)) {
          pageViolations.push(`${toRel(file)}:${String(line)} (${needle})`)
        }
      }
    }
    assert.equal(
      pageViolations.length,
      0,
      `pages/** 下不许直接读 world.player / world.jobs (身份/职业进度须走真契约 action), 发现: ${pageViolations.join(', ')}`,
    )

    let componentTotal = 0
    const componentViolationsOutsideShell = []
    for (const file of allFiles) {
      if (!file.startsWith(COMPONENTS_DIR + path.sep)) {
        continue
      }
      const text = contents.get(file)
      for (const needle of NEEDLES) {
        const hits = findOccurrences(text, needle)
        componentTotal += hits.length
        if (hits.length > 0 && file !== SHELL_FILE) {
          for (const line of hits) {
            componentViolationsOutsideShell.push(`${toRel(file)}:${String(line)} (${needle})`)
          }
        }
      }
    }
    assert.equal(
      componentViolationsOutsideShell.length,
      0,
      `components/** 下只允许 shell/TabletShell.tsx 读 world.player / world.jobs (dev-only OP 视图开关), 发现越界: ${componentViolationsOutsideShell.join(', ')}`,
    )
    assert.ok(
      componentTotal <= 1,
      `components/** 下 world.player / world.jobs 总出现次数应 <= 1 (仅 TabletShell 的 OP 开关那一处), 实际 ${String(componentTotal)} 次`,
    )
    return `pages/** 0 次, components/** ${String(componentTotal)} 次 (TabletShell.tsx)`
  },
)

// ============================================================
// 4. [F057] mirror.wallet / mirror.myListings / mirror.caseOwnedTotal 全库零出现 + 接口形状
// ============================================================

runCheck(
  'F057-mirror-fields-removed',
  'mirror.wallet / mirror.myListings / mirror.caseOwnedTotal 全库零读取方, MockRealDomainMirror 只留 inventory/lastError',
  () => {
    const NEEDLES = ['mirror.wallet', 'mirror.myListings', 'mirror.caseOwnedTotal']
    const violations = []
    for (const file of allFiles) {
      const text = contents.get(file)
      for (const needle of NEEDLES) {
        for (const line of findOccurrences(text, needle)) {
          violations.push(`${toRel(file)}:${String(line)} (${needle})`)
        }
      }
    }
    assert.equal(
      violations.length,
      0,
      `mirror.wallet / mirror.myListings / mirror.caseOwnedTotal 应已全库零读取方 (F057), 发现: ${violations.join(', ')}`,
    )

    const storeText = requireFile('mock/store.ts')
    const interfaceMatch = storeText.match(/interface MockRealDomainMirror \{([\s\S]*?)\n\}/)
    assert.ok(interfaceMatch, 'mock/store.ts 找不到 interface MockRealDomainMirror 定义')
    const body = interfaceMatch[1]
    assert.match(body, /\binventory\b\s*:/, 'MockRealDomainMirror 应含 inventory 键')
    assert.match(body, /\blastError\b\s*:/, 'MockRealDomainMirror 应含 lastError 键')
    assert.doesNotMatch(body, /\bwallet\b\s*:/, 'MockRealDomainMirror 不应再含 wallet 键 (F057)')
    assert.doesNotMatch(body, /\bmyListings\b\s*:/, 'MockRealDomainMirror 不应再含 myListings 键 (F057)')
    assert.doesNotMatch(
      body,
      /\bcaseOwnedTotal\b\s*:/,
      'MockRealDomainMirror 不应再含 caseOwnedTotal 键 (F057)',
    )
    return 'mirror.* 三键全库零读取方, 接口体仅剩 inventory/lastError (以及 refreshedAt)'
  },
)

// ============================================================
// 5. [F057] mock/handlers.ts 内不再存在 refreshCaseTotals / MIRROR_AFTER_CASE
// ============================================================

runCheck(
  'F057-handlers-no-case-mirror',
  'mock/handlers.ts 内 refreshCaseTotals 与 MIRROR_AFTER_CASE 两个标识符均已删除',
  () => {
    const handlersText = requireFile('mock/handlers.ts')
    for (const identifier of ['refreshCaseTotals', 'MIRROR_AFTER_CASE']) {
      const hits = findOccurrences(handlersText, identifier)
      assert.equal(
        hits.length,
        0,
        `mock/handlers.ts 不应再出现标识符 ${identifier} (F057), 命中行: ${hits.join(', ')}`,
      )
    }
    return 'mock/handlers.ts 内 refreshCaseTotals / MIRROR_AFTER_CASE 均为 0 次'
  },
)

// ============================================================
// 6. [F012] delegateReal: 镜像刷新必须是 fire-and-forget (void ...), 不能 await
// ============================================================

runCheck(
  'F012-delegate-real-nonblocking-mirror',
  'delegateReal 函数体内镜像刷新走 void refreshInventoryMirror(...), 不出现 await refresh',
  () => {
    const handlersText = requireFile('mock/handlers.ts')
    const body = extractFunctionBody(handlersText, 'delegateReal')
    assert.ok(body, 'mock/handlers.ts 找不到 async function delegateReal 的函数体')
    assert.match(
      body,
      /void refreshInventoryMirror\(/,
      'delegateReal 函数体内应出现 void refreshInventoryMirror( (F012: 镜像刷新不能挡住写操作回执)',
    )
    assert.doesNotMatch(
      body,
      /await refresh/,
      'delegateReal 函数体内不应出现 await refresh (F012 红线: 刷新失败不能让已成功的写操作被判失败)',
    )
    return `delegateReal 函数体 ${String(body.length)} 字符, 命中 void refreshInventoryMirror(, 零 await refresh`
  },
)

// ============================================================
// 7. [F014] FarmerPanel.handleSell: 卖出成功后走 query.reload(), 不再手动 loadInventory()
// ============================================================

runCheck(
  'F014-farmer-sell-no-manual-inventory-reload',
  'FarmerPanel.handleSell 函数体内 loadInventory( 出现 0 次, query.reload( 出现 1 次',
  () => {
    const panelText = requireFile('pages/jobs/panels/FarmerPanel.tsx')
    const body = extractFunctionBody(panelText, 'handleSell')
    assert.ok(body, 'FarmerPanel.tsx 找不到 handleSell 的函数体')
    const loadInventoryHits = findOccurrences(body, 'loadInventory(')
    const reloadHits = findOccurrences(body, 'query.reload(')
    assert.equal(
      loadInventoryHits.length,
      0,
      `handleSell 函数体内不应再手动调 loadInventory( (F014: 背包已由 MIRROR_AFTER_INVENTORY 自动刷), 命中 ${String(loadInventoryHits.length)} 次`,
    )
    assert.equal(
      reloadHits.length,
      1,
      `handleSell 函数体内应恰好调 1 次 query.reload( (重查职业档案拿新单价), 实际 ${String(reloadHits.length)} 次`,
    )
    return 'handleSell: loadInventory( 0 次, query.reload( 1 次'
  },
)

// ============================================================
// 8. [F059] mirror.myListings 整字段核销: seed.ts 无 seedMyListings, 初始世界字面量无 myListings 键
// ============================================================

runCheck(
  'F059-seed-no-my-listings',
  'mock/seed.ts 不存在 seedMyListings 标识符, createInitialWorld 的 mirror 字面量不含 myListings 键',
  () => {
    const seedText = requireFile('mock/seed.ts')
    const hits = findOccurrences(seedText, 'seedMyListings')
    assert.equal(
      hits.length,
      0,
      `mock/seed.ts 不应再出现标识符 seedMyListings (F059), 命中行: ${hits.join(', ')}`,
    )

    const createInitialWorldBody = extractFunctionBody(seedText, 'createInitialWorld')
    assert.ok(createInitialWorldBody, 'mock/seed.ts 找不到 createInitialWorld 的函数体')
    const mirrorLiteralMatch = createInitialWorldBody.match(/mirror\s*:\s*\{([\s\S]*?)\n(\s*)\},/)
    assert.ok(mirrorLiteralMatch, 'createInitialWorld 函数体内找不到 mirror: { ... } 字面量')
    assert.doesNotMatch(
      mirrorLiteralMatch[1],
      /\bmyListings\b\s*:/,
      'createInitialWorld 的 mirror 字面量不应再含 myListings 键 (F059)',
    )
    return 'seed.ts 无 seedMyListings, mirror 字面量无 myListings 键'
  },
)

// ============================================================
// 9. [F056] handshake( 真的接了调用点, 且落在 TabletShell.tsx
// ============================================================

runCheck(
  'F056-handshake-wired',
  'handshake( 在 lib/bridge.ts 之外至少出现 1 次, 且调用点是 components/shell/TabletShell.tsx',
  () => {
    const BRIDGE_FILE = path.join(SRC_ROOT, 'lib', 'bridge.ts')
    const SHELL_FILE = path.join(SRC_ROOT, 'components', 'shell', 'TabletShell.tsx')
    const callSites = []
    for (const file of allFiles) {
      if (file === BRIDGE_FILE) {
        continue
      }
      for (const line of findOccurrences(contents.get(file), 'handshake(')) {
        callSites.push({ file, line })
      }
    }
    assert.ok(
      callSites.length >= 1,
      'handshake( 在 lib/bridge.ts 之外的出现次数为 0 (F056: 握手自检没有接调用点, 契约漂移检测形同虚设)',
    )
    const outsideShell = callSites.filter((site) => site.file !== SHELL_FILE)
    assert.equal(
      outsideShell.length,
      0,
      `handshake( 的调用点应落在 components/shell/TabletShell.tsx, 发现其它落点: ${outsideShell
        .map((site) => `${toRel(site.file)}:${String(site.line)}`)
        .join(', ')}`,
    )
    return `handshake( 在 TabletShell.tsx 命中 ${String(callSites.length)} 次调用`
  },
)

// ============================================================
// 10. [F055] ItemIcon: 探图必须带超时熔断, 不能无限挂起占用 MCEF 连接池
// ============================================================

runCheck(
  'F055-item-icon-timeout-and-circuit-breaker',
  'ItemIcon.tsx 内出现 AbortSignal.timeout( 与模块级熔断标识符',
  () => {
    const iconText = requireFile('components/ItemIcon.tsx')
    assert.match(
      iconText,
      /AbortSignal\.timeout\(/,
      'ItemIcon.tsx 应出现 AbortSignal.timeout( (F055: 模型 JSON 请求必须带超时, 否则镜像站挂起会占满 MCEF 连接池)',
    )
    // 熔断位与其翻转阈值 (由实现组命名; 名字与当前实现不一致时视为本条断言失败, 需要实施组统一命名而不是
    // 放宽成"含 timeout 字样"这种永远通过的弱校验)。
    assert.match(
      iconText,
      /let\s+vanillaCdnCircuitOpen\s*=\s*false/,
      'ItemIcon.tsx 应有模块级熔断标志位 `let vanillaCdnCircuitOpen = false` (F055: 连续超时后本会话剩余原版图标应直接落占位块)',
    )
    assert.match(
      iconText,
      /VANILLA_CDN_TIMEOUT_THRESHOLD/,
      'ItemIcon.tsx 应有熔断阈值常量 VANILLA_CDN_TIMEOUT_THRESHOLD (F055)',
    )
    return 'ItemIcon.tsx 命中 AbortSignal.timeout(, vanillaCdnCircuitOpen, VANILLA_CDN_TIMEOUT_THRESHOLD'
  },
)

// ============================================================
// 汇总输出
// ============================================================

const failed = results.filter((result) => !result.ok)
const passed = results.filter((result) => result.ok)

for (const result of results) {
  const status = result.ok ? 'PASS' : 'FAIL'
  console.log(`[${status}] ${result.id} - ${result.description}`)
  if (result.ok) {
    console.log(`       ${result.detail}`)
  } else {
    console.log(`       断言失败: ${result.detail}`)
  }
}

console.log('')
if (failed.length > 0) {
  console.log(`前端契约守卫: ${String(failed.length)} 条断言失败, ${String(passed.length)} 条通过 (共 ${String(results.length)} 条)`)
  process.exit(1)
} else {
  console.log(`前端契约守卫: 全部 ${String(results.length)} 条断言通过`)
  process.exit(0)
}
