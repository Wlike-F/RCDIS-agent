import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

import { REQUEST_CONTEXT } from './context'
import type { ApiResponse } from './types'

export class ApiError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000
})

client.interceptors.request.use((config) => {
  config.headers.set('X-User-Id', REQUEST_CONTEXT.userId)
  config.headers.set('X-User-Name', REQUEST_CONTEXT.userName)
  config.headers.set('X-Tenant-Id', REQUEST_CONTEXT.tenantId)
  return config
})

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<ApiResponse<unknown>>
    const body = axiosError.response?.data
    if (body && typeof body === 'object' && 'success' in body && !body.success) {
      return new ApiError(body.code || 'BIZ_ERROR', body.message || '请求失败')
    }
    if (axiosError.response) {
      return new ApiError(
        `HTTP_${axiosError.response.status}`,
        `请求失败（HTTP ${axiosError.response.status}）`
      )
    }
    if (axiosError.code === 'ECONNABORTED') {
      return new ApiError('TIMEOUT', '请求超时，请检查后端服务是否启动')
    }
    return new ApiError('NETWORK_ERROR', '网络异常，无法连接后端服务')
  }
  return new ApiError('UNKNOWN', error instanceof Error ? error.message : '未知错误')
}

function assertSuccess<T>(body: ApiResponse<T> | undefined): T {
  if (!body || typeof body !== 'object' || !('success' in body)) {
    throw new ApiError('BAD_RESPONSE', '后端响应格式异常')
  }
  if (!body.success) {
    throw new ApiError(body.code || 'BIZ_ERROR', body.message || '请求失败')
  }
  return body.data
}

export async function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.get<ApiResponse<T>>(url, config)
    return assertSuccess(response.data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function apiPost<T>(url: string, payload?: unknown, config?: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.post<ApiResponse<T>>(url, payload, config)
    return assertSuccess(response.data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function apiPut<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await client.put<ApiResponse<T>>(url, payload)
    return assertSuccess(response.data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function apiDelete<T>(url: string, payload: unknown): Promise<T> {
  try {
    const response = await client.delete<ApiResponse<T>>(url, { data: payload })
    return assertSuccess(response.data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function apiUpload<T>(url: string, formData: FormData): Promise<T> {
  try {
    const response = await client.post<ApiResponse<T>>(url, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
    return assertSuccess(response.data)
  } catch (error) {
    throw toApiError(error)
  }
}
