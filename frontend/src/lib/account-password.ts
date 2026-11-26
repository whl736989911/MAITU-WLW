type PasswordBounds = { min_length: number; max_length: number }

export const accountPasswordBounds = { min_length: 6, max_length: 128 }

const letter = /\p{L}/u
const digit = /\p{Nd}/u
const symbol = /[\p{P}\p{S}]/u

export function accountPasswordHint(bounds: PasswordBounds = accountPasswordBounds) {
  return `密码须为 ${bounds.min_length}–${bounds.max_length} 个字符，数字、字母、符号至少使用两类（空格不计为符号）`
}

export function validAccountPassword(password: string, bounds: PasswordBounds = accountPasswordBounds) {
  if (password.length < bounds.min_length || password.length > bounds.max_length) return false
  let length = 0
  for (let index = 0; index < password.length && length < bounds.min_length; length++) {
    index += password.codePointAt(index)! > 0xffff ? 2 : 1
  }
  return length >= bounds.min_length && Number(letter.test(password)) + Number(digit.test(password)) + Number(symbol.test(password)) >= 2
}
