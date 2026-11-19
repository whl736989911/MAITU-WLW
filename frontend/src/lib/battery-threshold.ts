function decimalCompare(a: string, b: string) {
  const parts = (value: string) => {
    const match = /^(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
    if (!match) throw new Error('Version threshold lexical value is invalid')
    const exponent = Number(match[3] ?? 0) - (match[2]?.length ?? 0)
    if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 100) throw new Error('Version threshold lexical value is invalid')
    return { coefficient: BigInt(match[1] + (match[2] ?? '')), exponent }
  }
  const x = parts(a), y = parts(b), exponent = Math.min(x.exponent, y.exponent)
  const left = x.coefficient * 10n ** BigInt(x.exponent - exponent), right = y.coefficient * 10n ** BigInt(y.exponent - exponent)
  return left < right ? -1 : left > right ? 1 : 0
}
export function validThreshold(raw: string, value: number) {
  try {
    const lexical = /^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)
    const match = /^(?:0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw)
    return lexical && !!match && Math.max(0, (match[1]?.length ?? 0) - Number(match[2] ?? 0)) <= 36 && Number.isFinite(value) && decimalCompare(raw, '0') >= 0 && decimalCompare(raw, '100') <= 0 && Number(raw) === value
  } catch { return false }
}
