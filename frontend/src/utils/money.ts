import Decimal from 'decimal.js'

export type MoneyValue = number | string | Decimal

export function toDecimal(value: MoneyValue): Decimal {
  return new Decimal(value)
}

export function toDecimalOrNull(value: MoneyValue | null | undefined): Decimal | null {
  if (value === null || value === undefined || value === '') return null
  const decimal = toDecimal(value)
  if (!decimal.isFinite()) return null
  return decimal
}

export function sumMoney(values: MoneyValue[]): string {
  return values.reduce<Decimal>((sum, value) => sum.plus(toDecimal(value)), new Decimal(0)).toFixed(2)
}

export function addMoney(left: MoneyValue, right: MoneyValue): string {
  return toDecimal(left).plus(toDecimal(right)).toFixed(2)
}

export function subtractMoney(left: MoneyValue, right: MoneyValue): string {
  return toDecimal(left).minus(toDecimal(right)).toFixed(2)
}

export function greaterThanMoney(left: MoneyValue, right: MoneyValue): boolean {
  return toDecimal(left).greaterThan(toDecimal(right))
}
