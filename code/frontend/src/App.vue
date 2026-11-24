<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, logout, pendingRequests, queryPending, refreshSession, session } from './shared/api'
import { refreshCatalog, showError, toast, toasts } from './shared/state'
import { dateTime, roleNames } from './shared/format'
import type { CommandResult, Notification } from './shared/types'
import LoginView from './app/LoginView.vue'
import BookingView from './features/booking/BookingView.vue'
import OrdersView from './features/orders/OrdersView.vue'
import InventoryView from './features/inventory/InventoryView.vue'
import AdminView from './features/admin/AdminView.vue'
import NotificationsView from './features/notification/NotificationsView.vue'
import ReportsView from './features/reporting/ReportsView.vue'
import GuestView from './features/guest/GuestView.vue'
import AuditView from './features/audit/AuditView.vue'
import Icon from './shared/Icon.vue'
import Modal from './shared/Modal.vue'
import Badge from './shared/Badge.vue'
const loading = ref(true), bootError = ref(''), tab = ref(''), mobileMenu = ref(false), orderId = ref(''), unread = ref(0)
const requestModal = ref(false), requestId = ref(''), requestResult = ref<CommandResult>(), requestBusy = ref(false)
const items = [{id: 'booking', title: '住宿预订', icon: 'bed', roles: ['RECEPTION']}, {id: 'orders', title: '订单与安置', icon: 'orders', roles: ['RECEPTION', 'ADMIN']}, {id: 'inventory', title: '每日房源与价格', icon: 'calendar', roles: ['HOTEL']}, {id: 'stay', title: '入住与到店处理', icon: 'bed', roles: ['HOTEL']}, {id: 'admin', title: '平台管理', icon: 'grid', roles: ['ADMIN']}, {id: 'notifications', title: '通知与待办', icon: 'bell', roles: ['RECEPTION', 'HOTEL', 'ADMIN']}, {id: 'reports', title: '接待住宿统计', icon: 'chart', roles: ['RECEPTION', 'HOTEL', 'ADMIN']}, {id: 'audit', title: '操作日志', icon: 'log', roles: ['HOTEL', 'ADMIN']}, {id: 'guest', title: '本人住宿安排', icon: 'bed', roles: ['GUEST']}]
const menu = computed(() => items.filter(i => session.user && i.roles.includes(session.user.role)))
const currentTitle = computed(() => menu.value.find(i => i.id === tab.value)?.title || '工作空间')
const myPending = computed(() => pendingRequests.value.filter(r => r.userId === session.user?.id))
async function authenticated() { loading.value = true; try { await refreshCatalog(); tab.value = session.user?.role === 'ADMIN' ? 'admin' : session.user?.role === 'HOTEL' ? 'inventory' : session.user?.role === 'GUEST' ? 'guest' : 'booking'; orderId.value = ''; if (session.user?.role !== 'GUEST') unread.value = (await api<Notification[]>('/notifications')).filter(n => !n.isRead).length } catch (e) { showError(e) } finally { loading.value = false } }
async function boot() { loading.value = true; bootError.value = ''; try { await refreshSession(); if (session.user) await authenticated() } catch (e) { bootError.value = e instanceof Error ? e.message : '无法连接服务' } finally { loading.value = false } }
onMounted(boot)
watch(() => session.user?.id, (id, previous) => { if (id && id !== previous && !loading.value) authenticated() })
function navigate(id: string) { tab.value = id; mobileMenu.value = false; orderId.value = '' }
function openOrder(id: string) { orderId.value = id; tab.value = session.user?.role === 'HOTEL' ? 'stay' : 'orders'; mobileMenu.value = false }
async function signOut() { try { await logout(); tab.value = ''; mobileMenu.value = false } catch (e) { showError(e) } }
async function checkRequest(id = requestId.value) { if (!id.trim()) return; requestBusy.value = true; try { requestResult.value = await queryPending(id.trim()); toast(requestResult.value.message, requestResult.value.status === 'REJECTED') } catch (e) { showError(e) } finally { requestBusy.value = false } }
</script>
<template>
  <div v-if="loading" class="boot-screen"><span class="brand-mark">宾</span><h1>学宾接待</h1><p>正在连接接待工作空间…</p><span class="loader"></span></div>
  <div v-else-if="bootError" class="boot-screen"><Icon name="info" :size="40"/><h1>暂时无法连接服务</h1><p>{{ bootError }}</p><button class="button primary" @click="boot">重新连接</button></div>
  <LoginView v-else-if="!session.user"/>
  <div v-else class="app-shell"><div v-if="mobileMenu" class="sidebar-overlay" @click="mobileMenu = false"></div><aside class="sidebar" :class="{expanded: mobileMenu}"><a class="brand" href="#" @click.prevent="navigate(menu[0]?.id || '')"><span class="brand-mark">宾</span><span><strong>学宾接待</strong><small>CAMPUS HOSPITALITY</small></span></a><div class="workspace-label">{{ roleNames[session.user.role] }}工作空间</div><nav aria-label="工作空间导航"><button v-for="item in menu" :key="item.id" :class="{active: tab === item.id}" @click="navigate(item.id)"><Icon :name="item.icon" :size="20"/><span>{{ item.title }}</span><i v-if="item.id === 'notifications' && unread" class="nav-dot"></i></button></nav><div class="sidebar-footer"><p>仅供本校来宾接待使用</p><span><Icon name="shield" :size="15"/> 责任范围清晰 · 住宿安排有序</span></div></aside><div class="app-main"><header class="topbar"><button class="icon-button mobile-toggle" aria-label="展开导航" @click="mobileMenu = !mobileMenu"><Icon name="menu"/></button><div class="breadcrumb">工作空间<span>/</span><strong>{{ currentTitle }}</strong></div><div class="topbar-actions"><button class="request-link" @click="requestModal = true; requestResult = undefined"><Icon name="clock" :size="16"/><span>请求结果</span><b v-if="myPending.length">{{ myPending.length }}</b></button><span class="role-chip"><Icon name="user" :size="15"/>{{ session.user.name }}</span><button class="logout-button" @click="signOut"><Icon name="logout" :size="16"/>退出</button></div></header><main class="page-content"><BookingView v-if="tab === 'booking'" @open-order="openOrder"/><OrdersView v-else-if="tab === 'orders' || tab === 'stay'" :initial-order="orderId" @open-order="openOrder"/><InventoryView v-else-if="tab === 'inventory'"/><AdminView v-else-if="tab === 'admin'"/><NotificationsView v-else-if="tab === 'notifications'"/><ReportsView v-else-if="tab === 'reports'" @open-order="openOrder"/><GuestView v-else-if="tab === 'guest'"/><AuditView v-else-if="tab === 'audit'"/></main><footer class="app-footer">学宾接待 · 校园来宾住宿服务<span>让每一次来访都有妥善安排</span></footer></div></div>
  <div class="toast-container" aria-live="polite"><div v-for="t in toasts" :key="t.id" class="toast" :class="{error: t.error}"><Icon :name="t.error ? 'info' : 'check'" :size="19"/><span>{{ t.message }}</span><button class="icon-button" aria-label="关闭提示" @click="toasts = toasts.filter(x => x.id !== t.id)"><Icon name="close" :size="16"/></button></div></div>
  <Modal v-if="requestModal && session.user" title="查询原请求处理结果" @close="requestModal = false"><p class="muted">遇到页面超时或网络中断时，请先查明原请求结果，避免把同一次操作重复提交为新请求。</p><form class="form-stack" @submit.prevent="checkRequest()"><label>请求编号<input v-model="requestId" placeholder="粘贴请求编号查询" required/></label><button class="button primary" :disabled="requestBusy">{{ requestBusy ? '正在查询…' : '查询原结果' }}</button></form><h3 class="section-title">本账号待查请求</h3><div v-if="!myPending.length" class="subtle-empty">没有待确认结果的请求。</div><div v-for="r in myPending" :key="r.id" class="pending-row"><div><code>{{ r.id }}</code><small>{{ dateTime(r.createdAt) }}</small></div><button class="text-button" @click="requestId = r.id; checkRequest(r.id)">查询结果</button></div><div v-if="requestResult" class="rule-box"><Badge :status="requestResult.status"/><p>{{ requestResult.message }}</p><button v-if="requestResult.data?.orderId" class="text-button" @click="openOrder(String(requestResult.data.orderId)); requestModal = false">查看关联订单</button></div></Modal>
</template>
