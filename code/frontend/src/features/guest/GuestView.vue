<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../../shared/api'
import { showError } from '../../shared/state'
import { shortDate, dateTime, nights } from '../../shared/format'
import Icon from '../../shared/Icon.vue'
import Badge from '../../shared/Badge.vue'
interface MyStay {id: string; code: string; guestName: string; phone: string; hotelName: string; hotelAddress: string; startDate: string; endDate: string; status: string; arrivedAt?: string; departedAt?: string; items: {name: string; quantity: number}[]}
const stays = ref<MyStay[]>([]), loading = ref(false)
async function load() { loading.value = true; try { stays.value = await api('/me/stays') } catch (e) { showError(e) } finally { loading.value = false } }
onMounted(load)
</script>
<template><div class="page-heading"><div><span class="eyebrow">YOUR CAMPUS VISIT</span><h1>本人住宿安排</h1><p>欢迎到访。这里仅展示与您本人关联的住宿信息。</p></div><button class="button secondary" @click="load"><Icon name="refresh" :size="17"/>刷新安排</button></div><div v-if="loading" class="card empty-state">正在读取住宿安排…</div><div v-else-if="!stays.length" class="card empty-state"><Icon name="bed" :size="40"/><h3>暂无住宿安排</h3><p>如需住宿或调整安排，请联系负责您的学校接待人员。</p></div><div v-else class="guest-stays"><article v-for="stay in stays" :key="stay.id" class="card guest-stay"><div class="guest-card-top"><span class="guest-hotel-icon"><Icon name="building" :size="30"/></span><Badge :status="stay.status"/></div><span class="eyebrow">您的住宿安排</span><h2>{{ stay.hotelName }}</h2><p class="inline muted"><Icon name="pin" :size="17"/>{{ stay.hotelAddress }}</p><div class="guest-date-range"><div><span>入住日期</span><strong>{{ shortDate(stay.startDate) }}</strong></div><div class="date-nights">{{ nights(stay.startDate,stay.endDate) }} 晚<Icon name="arrow"/></div><div><span>离店日期</span><strong>{{ shortDate(stay.endDate) }}</strong></div></div><div class="detail-row"><span>本人姓名</span><strong>{{ stay.guestName }}</strong></div><div class="detail-row"><span>联系方式</span><strong>{{ stay.phone }}</strong></div><div class="detail-row"><span>安排房型</span><strong>{{ stay.items.map(i => i.name).join('、') }}</strong></div><div class="detail-row"><span>实际到店</span><strong>{{ stay.arrivedAt ? dateTime(stay.arrivedAt) : '尚未登记到店' }}</strong></div><div v-if="stay.departedAt" class="detail-row"><span>实际离店</span><strong>{{ dateTime(stay.departedAt) }}</strong></div><p class="guest-order-ref">安排编号 {{ stay.code }}</p><div class="notice purple-notice"><Icon name="info" :size="18"/><span>调整住宿、确认晚到或其他接待事宜，请联系负责您的学校接待人员。</span></div></article></div></template>
