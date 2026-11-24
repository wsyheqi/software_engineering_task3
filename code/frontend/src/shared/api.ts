import { reactive, ref } from 'vue'
import type { User, CommandResult } from './types'
export const session = reactive<{user: User | null; csrfToken: string; csrfHeader: string; today: string; now: string}>({user: null, csrfToken: '', csrfHeader: 'X-CSRF-TOKEN', today: '', now: ''})
export const pendingRequests = ref<{id: string; createdAt: string; userId: string}[]>(JSON.parse(localStorage.getItem('campus-pending') || '[]'))
function persist() { localStorage.setItem('campus-pending', JSON.stringify(pendingRequests.value)) }
export class ApiError extends Error {
  constructor(message: string, public code: string, public data: Record<string, unknown> = {}, public requestId?: string) { super(message) }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json')
  if (options.method && !['GET', 'HEAD'].includes(options.method)) headers.set(session.csrfHeader, session.csrfToken)
  const response = await fetch('/api' + path, {...options, headers, credentials: 'same-origin'})
  const data = await response.json()
  if (response.status === 401 && path !== '/session') session.user = null
  if (response.status >= 500) throw new ApiError('系统响应暂时不可用，请按原请求查询处理结果。', 'RESULT_UNKNOWN', {}, data.requestId)
  if (!response.ok) throw new ApiError(data.message || '请求失败，请稍后重试', data.businessCode || 'ERROR', data.data || {}, data.requestId)
  return data as T
}
export async function refreshSession() { Object.assign(session, await api('/session')) }
export async function login(username: string, password: string) {
  await refreshSession()
  await api('/session', {method: 'POST', body: new URLSearchParams({username, password})})
  await refreshSession()
}
export async function logout() { await api('/session', {method: 'DELETE'}); await refreshSession() }
export async function mutate(path: string, body: unknown = {}, method = 'POST', requestId = crypto.randomUUID()): Promise<CommandResult> {
  if (!pendingRequests.value.some(r => r.id === requestId)) { pendingRequests.value.push({id: requestId, createdAt: new Date().toISOString(), userId: session.user?.id || ''}); persist() }
  try {
    const result = await api<CommandResult>(path, {method, headers: {'Idempotency-Key': requestId}, body: JSON.stringify(body)})
    if (result.status !== 'PENDING') { pendingRequests.value = pendingRequests.value.filter(r => r.id !== requestId); persist() }
    return result
  } catch (error) {
    if (error instanceof ApiError && error.code !== 'RESULT_UNKNOWN') { pendingRequests.value = pendingRequests.value.filter(r => r.id !== requestId); persist(); throw error }
    throw new ApiError('网络响应未送达，请在「请求结果」中查询原请求，避免重复操作。', 'RESULT_UNKNOWN', {}, requestId)
  }
}
export async function queryPending(id: string) {
  const result = await api<CommandResult>('/requests/' + encodeURIComponent(id))
  if (result.status !== 'PENDING') { pendingRequests.value = pendingRequests.value.filter(r => r.id !== id); persist() }
  return result
}
