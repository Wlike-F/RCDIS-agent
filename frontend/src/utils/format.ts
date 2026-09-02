import { toDecimalOrNull, type MoneyValue } from './money'

const moneyFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
})

export function formatMoney(value: MoneyValue | null | undefined): string {
  const decimal = toDecimalOrNull(value)
  if (!decimal) return '--'
  return moneyFormatter.format(decimal.toNumber())
}

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}

export function formatDate(input?: string | null): string {
  if (!input) return '--'
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return input
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function formatDateTime(input?: string | null): string {
  if (!input) return '--'
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return input
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`
}

export function formatTimeShort(input?: string | null): string {
  if (!input) return '--'
  const date = new Date(input)
  if (Number.isNaN(date.getTime())) return input
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes()
  )}`
}

const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

export function formatToday(): string {
  const now = new Date()
  return `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 · ${weekdays[now.getDay()]}`
}

export function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 9) return '早上好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

export function randomId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `id-${Date.now()}-${Math.round(Math.random() * 1e9)}`
}
