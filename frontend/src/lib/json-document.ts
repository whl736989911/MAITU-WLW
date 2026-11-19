// Strict JSON validation and raw-span access. Number tokens are never reconstructed.
export function parseJsonDocument(text: string): unknown {
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('JSON语法无效，请检查结构；错误信息不回显输入内容。') }
  const stack: { object: boolean; key: boolean; keys: Set<string> }[] = []
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (char === '{' || char === '[') stack.push({ object: char === '{', key: char === '{', keys: new Set() })
    else if (char === '}' || char === ']') stack.pop()
    else if (char === ',') { const current = stack.at(-1); if (current?.object) current.key = true }
    else if (char === '"') {
      const start = i
      for (i++; i < text.length; i++) { if (text[i] === '\\') i++; else if (text[i] === '"') break }
      const current = stack.at(-1)
      if (current?.object && current.key) {
        const key: string = JSON.parse(text.slice(start, i + 1))
        if (current.keys.has(key)) throw new Error('JSON 包含重复键，已拒绝；不会通过覆盖键值消除歧义。')
        current.keys.add(key); current.key = false
      }
    }
  }
  const pending: unknown[] = [value]
  while (pending.length) {
    const item = pending.pop()
    if (typeof item === 'number' && !Number.isFinite(item)) throw new Error('JSON 包含非有限数值，已拒绝；不会转换为 null 或 0。')
    if (item && typeof item === 'object') pending.push(...Object.values(item))
  }
  return value
}
function skipSpace(text: string, index: number) { while (/\s/.test(text[index] ?? '') && index < text.length) index++; return index }
function valueEnd(text: string, start: number): number {
  if (text[start] === '"') { let index = start + 1; while (index < text.length) { if (text[index] === '\\') index += 2; else if (text[index++] === '"') return index } throw new Error('Invalid JSON string') }
  if (text[start] === '{' || text[start] === '[') {
    let depth = 0
    for (let index = start; index < text.length; index++) {
      if (text[index] === '"') index = valueEnd(text, index) - 1
      else if (text[index] === '{' || text[index] === '[') depth++
      else if ((text[index] === '}' || text[index] === ']') && --depth === 0) return index + 1
    }
    throw new Error('Invalid JSON container')
  }
  let index = start; while (index < text.length && !/[,}\]\s]/.test(text[index])) index++; return index
}
function rawMembers(text: string) {
  const result = new Map<string, string>(); let index = skipSpace(text, 1)
  while (index < text.length && text[index] !== '}') {
    const keyEnd = valueEnd(text, index), key: string = JSON.parse(text.slice(index, keyEnd))
    index = skipSpace(text, keyEnd); index = skipSpace(text, index + 1)
    const end = valueEnd(text, index); result.set(key, text.slice(index, end))
    index = skipSpace(text, end); if (text[index] === ',') index = skipSpace(text, index + 1)
  }
  return result
}
function rawItems(text: string) {
  const items: string[] = []; let index = skipSpace(text, 1)
  while (index < text.length && text[index] !== ']') { const end = valueEnd(text, index); items.push(text.slice(index, end)); index = skipSpace(text, end); if (text[index] === ',') index = skipSpace(text, index + 1) }
  return items
}
export function jsonFieldText(text: string, key: string): string | undefined {
  JSON.parse(text)
  return rawMembers(text.trim()).get(key)
}
export function jsonArrayItems(text: string): string[] {
  if (!Array.isArray(JSON.parse(text))) throw new Error('Expected JSON array')
  return rawItems(text.trim())
}
