import { ref } from 'vue'
import { api, session } from './api'
import type { Catalog } from './types'
export const catalog = ref<Catalog | null>(null)
export const revision = ref(0)
export const toasts = ref<{id: number; message: string; error: boolean}[]>([])
let toastId = 0
export function toast(message: string, error = false) { const id = ++toastId; toasts.value.push({id, message, error}); setTimeout(() => toasts.value = toasts.value.filter(t => t.id !== id), error ? 10000 : 5000) }
export function showError(error: unknown) { toast(error instanceof Error ? error.message : '操作失败，请稍后重试', true) }
export async function refreshCatalog() { if (session.user && session.user.role !== 'GUEST') catalog.value = await api<Catalog>('/catalog'); revision.value++ }
