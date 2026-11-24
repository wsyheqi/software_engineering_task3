<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api, mutate, session, ApiError } from '../../shared/api'
import { catalog, refreshCatalog, toast, showError } from '../../shared/state'
import type { Plan, Quote, Snapshot, Guest } from '../../shared/types'
import { addDays, money, nights, shortDate } from '../../shared/format'
import Icon from '../../shared/Icon.vue'
import Modal from '../../shared/Modal.vue'
import QuotePanel from '../../shared/QuotePanel.vue'
import RoomTypePicker from './RoomTypePicker.vue'
const emit = defineEmits<{openOrder: [id: string]}>()
const form = reactive<Plan>({city: '南京', siteId: catalog.value?.sites[0]?.id || '', startDate: addDays(session.today, 3), endDate: addDays(session.today, 6), budget: 1500, maxDistance: 20, minPrice: 0, maxPrice: 1000, items: [{code: 'DOUBLE', quantity: 1}], guestIds: catalog.value?.guests.slice(0, 2).map(g => g.id) || [], special: ''})
const demand = ref<{id: string; version: number}>(), options = ref<Quote[]>([]), searched = ref(false), busy = ref(false), sort = ref('recommended')
const selected = ref<Quote>(), latest = ref<Snapshot>(), accepted = ref(false)
const unavailableHotel = ref<Pick<Snapshot, 'hotelId' | 'hotelName'>>()
const guestModal = ref(false), guestBusy = ref(false), guestEdit = reactive({id: '', name: '', phone: '', identityRef: ''})
const roomTypes = computed(() => [...new Map((catalog.value?.roomTypes || []).map(r => [r.code, r])).values()])
const guests = computed(() => (catalog.value?.guests || []).filter(g => form.guestIds.includes(g.id)))
const site = computed(() => catalog.value?.sites.find(s => s.id === form.siteId))
let formVersion = 0
function closeQuote() {
  selected.value = undefined
  latest.value = undefined
  accepted.value = false
}
watch(form, () => {
  formVersion++
  options.value = []
  searched.value = false
  unavailableHotel.value = undefined
  closeQuote()
}, {flush: 'sync'})
async function loadOptions(demandId: string, version: number) {
  const result = await api<Quote[]>('/hotel-options/search', {method: 'POST', body: JSON.stringify({demandId, sort: sort.value})})
  if (version !== formVersion) return
  const excludedHotelId = unavailableHotel.value?.hotelId
  options.value = excludedHotelId ? result.filter(quote => quote.snapshot.hotelId !== excludedHotelId) : result
  searched.value = true
}
async function search(save = true) {
  if (busy.value) return
  if (!form.guestIds.length || !form.items.length) { toast('请至少选择一位来宾和一种房型', true); return }
  if (form.items.length !== 1) { toast('请选择一种房型进行预订', true); return }
  if (form.items.some(item => !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 100)) { toast('每种房型的预订间数应为1～100的整数', true); return }
  busy.value = true
  const version = formVersion
  if (save) unavailableHotel.value = undefined
  try {
    if (save || !demand.value) {
      const {id, version: demandVersion} = demand.value ?? {}
      demand.value = await api('/demands', {method: 'POST', body: JSON.stringify({...form, id, version: demandVersion})})
    }
    if (version === formVersion && demand.value) await loadOptions(demand.value.id, version)
  } catch (error) { showError(error) } finally { busy.value = false }
}
async function setSort(value: string) {
  if (busy.value) return
  sort.value = value
  if (searched.value) await search(false)
}
function choose(q: Quote) { selected.value = q; latest.value = undefined; accepted.value = false }
async function book() {
  const quote = selected.value
  const demandId = demand.value?.id
  if (!quote || !demandId || !accepted.value || busy.value) return
  const version = formVersion
  busy.value = true
  try {
    const result = await mutate('/bookings', {quoteId: quote.quoteId, rulesConfirmed: accepted.value})
    toast(result.message)
    if (result.status === 'SUCCEEDED') {
      closeQuote()
      emit('openOrder', String(result.data?.orderId))
    }
  } catch (error) {
    if (error instanceof ApiError && version === formVersion) {
      if (error.code === 'NEEDS_RECONFIRMATION' && selected.value?.quoteId === quote.quoteId) {
        latest.value = error.data.latest as Snapshot
        accepted.value = false
        return
      }
      if (error.code === 'INSUFFICIENT_INVENTORY') {
        const {hotelId, hotelName} = quote.snapshot
        unavailableHotel.value = {hotelId, hotelName}
        options.value = []
        searched.value = false
        closeQuote()
        try { await loadOptions(demandId, version) }
        catch (searchError) { showError(searchError) }
        return
      }
    }
    showError(error)
  } finally { busy.value = false }
}
function openGuest(guest?: Guest) { Object.assign(guestEdit, guest ? {id: guest.id, name: guest.name, phone: guest.phone, identityRef: guest.identityRef} : {id: '', name: '', phone: '', identityRef: ''}); guestModal.value = true }
async function saveGuest() { guestBusy.value = true; try { const result = await mutate('/guests', guestEdit); if (result.status === 'SUCCEEDED') { await refreshCatalog(); const id = String(result.data?.guestId); if (!form.guestIds.includes(id)) form.guestIds.push(id); guestModal.value = false; toast(result.message) } } catch (e) { showError(e) } finally { guestBusy.value = false } }
async function refreshQuote() { closeQuote(); await search(false) }
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">RECEPTION WORKSPACE</span><h1>住宿预订</h1><p>维护住宿条件、比较候选酒店，确认方案与费用后完成预订。</p></div><span class="soft-tag"><Icon name="shield" :size="16"/> 学校专属房源</span></div>
  <div class="steps"><span class="active"><b>01</b> 住宿需求</span><i></i><span :class="{active: searched}"><b>02</b> 酒店推荐</span><i></i><span :class="{active: selected}"><b>03</b> 确认预订</span></div>
  <form id="hotel-search" class="card search-form" @submit.prevent="search()"><div class="form-grid four"><label>住宿城市<input v-model="form.city" required/></label><label>入住日期<input v-model="form.startDate" type="date" :min="session.today" required/></label><label>离店日期<input v-model="form.endDate" type="date" :min="addDays(form.startDate, 1)" required/></label><label>接待地点<select v-model="form.siteId" required><option v-for="s in catalog?.sites" :key="s.id" :value="s.id">{{ s.name }}</option></select></label><label>整单预算 / 元<input v-model.number="form.budget" type="number" min="0.01" step="0.01" required/></label><label>最大距离 / km<input v-model.number="form.maxDistance" type="number" min="0.01" max="20" step="0.01" required/></label><label>每晚价格下限 / 元<input v-model.number="form.minPrice" type="number" min="0" step="0.01" required/></label><label>每晚价格上限 / 元<input v-model.number="form.maxPrice" type="number" :min="form.minPrice" step="0.01" required/></label></div><div class="search-form-footer"><span class="muted">逐间逐晚符合价格区间；整单总价不超过预算。</span><button class="button primary" :disabled="busy"><Icon name="search" :size="18"/>{{ busy ? '正在查询…' : '查询酒店' }}</button></div></form>
  <div class="booking-columns"><section class="card demand-card"><div class="section-heading"><h2>本次住宿需求</h2><span class="muted">{{ nights(form.startDate, form.endDate) || 0 }} 晚</span></div><p class="muted small">{{ site?.name }} / {{ form.city }}</p><div class="detail-row"><span>接待负责人</span><strong>{{ session.user?.name }}</strong></div><div class="section-heading gap-top"><h3>来宾资料</h3><button class="text-button" @click="openGuest()"><Icon name="plus" :size="16"/>新增</button></div><div class="guest-options"><div v-for="g in catalog?.guests" :key="g.id" class="guest-option"><label><input v-model="form.guestIds" type="checkbox" :value="g.id"/><span>{{ g.name }}<small>{{ g.phone }}</small></span></label><button class="text-button muted" @click="openGuest(g)">编辑</button></div><p v-if="!catalog?.guests.length" class="muted">请先新增由您负责的来宾。</p></div><RoomTypePicker v-model="form.items" :options="roomTypes" form-id="hotel-search"/><label class="gap-top">特殊需求<textarea v-model="form.special" rows="2" maxlength="1000" placeholder="例如：安静房间、无障碍安排等"/></label><div class="notice purple-notice"><Icon name="info" :size="18"/><span>本次预订选择一种房型。当前 {{ guests.length }} 位来宾；人数须在所选房型的总容量内。</span></div></section>
    <section class="card candidates">
      <div class="section-heading">
        <h2>{{ unavailableHotel ? '可选替代酒店' : '符合条件的酒店' }}</h2>
        <span v-if="searched" class="count-label">{{ options.length }} 个方案</span>
      </div>
      <p class="muted small">全部住宿晚有房 · 学校审核通过 · 评价不低于 70 分</p>
      <div v-if="unavailableHotel" class="notice warning" role="status">
        <Icon name="info"/>
        <span>「{{ unavailableHotel.hotelName }}」部分住宿晚房源不足，整单预订失败。</span>
      </div>
      <div class="segmented">
        <button :class="{active: sort === 'recommended'}" :disabled="busy" @click="setSort('recommended')">综合推荐</button>
        <button :class="{active: sort === 'price'}" :disabled="busy" @click="setSort('price')">整单价格</button>
        <button :class="{active: sort === 'distance'}" :disabled="busy" @click="setSort('distance')">距离</button>
      </div>
      <div v-if="!searched" class="empty-state">
        <template v-if="unavailableHotel">
          <h3>{{ busy ? '正在按原条件查询替代酒店…' : '请重新查询酒店' }}</h3>
        </template>
        <template v-else>
          <div class="empty-icon"><Icon name="building" :size="36"/></div>
          <h3>为来宾寻找合适的住宿</h3>
          <p>完善左侧来宾及房型需求，<br/>点击查询，比较满足全部条件的酒店。</p>
        </template>
      </div>
      <div v-else-if="!options.length" class="empty-state">
        <Icon name="search" :size="36"/>
        <h3>{{ unavailableHotel ? '暂无符合原条件的替代酒店' : '暂无符合全部条件的方案' }}</h3>
        <p>请检查预算、距离、日期与房型。<br/>系统不会自动放宽条件或替您预订其他酒店。</p>
      </div>
      <article v-for="(q, index) in options" :key="q.quoteId" class="hotel-option" :class="{recommended: index === 0 && sort === 'recommended'}">
        <div class="hotel-monogram" :class="'tone-' + index % 3">{{ q.snapshot.hotelName.slice(0, 1) }}</div>
        <div class="hotel-info">
          <div class="inline">
            <h3>{{ q.snapshot.hotelName }}</h3>
            <span v-if="index === 0 && sort === 'recommended'" class="recommend-label">优选</span>
          </div>
          <p>评价 {{ q.snapshot.evaluation }} 分 · {{ q.snapshot.distance }} km</p>
          <small>推荐分 {{ Number(q.snapshot.score).toFixed(2) }} · {{ q.snapshot.description }}</small>
        </div>
        <div class="hotel-price">
          <strong>¥ {{ money(q.snapshot.total) }}</strong>
          <small>整单 / {{ nights(q.snapshot.plan.startDate, q.snapshot.plan.endDate) }} 晚</small>
          <button class="button" :class="index === 0 ? 'primary' : 'secondary'" :disabled="busy" @click="choose(q)">查看方案并确认</button>
        </div>
      </article>
      <p class="formula-note">价格 40% · 距离 30% · 学校评价 30%<br/>按未舍入分数排序，价格与规则在提交时再次复核。</p>
    </section>
  </div>
  <Modal v-if="selected" title="确认住宿方案" wide @close="closeQuote"><div v-if="latest" class="notice warning"><Icon name="info"/><div><strong>原方案已变化，本次尚未生成已确认订单或占用名额。</strong><p>下方展示最新价格、名额与规则。请返回重新查询并选择确认。</p></div></div><QuotePanel :snapshot="latest || selected.snapshot"/><label v-if="!latest" class="check-line"><input v-model="accepted" type="checkbox"/>我已核对住宿方案，并阅读取消期限及可能产生的费用。</label><template #footer><button class="button secondary" @click="closeQuote">返回比较</button><button v-if="latest" class="button primary" @click="refreshQuote">重新查询并确认</button><button v-else class="button primary" :disabled="!accepted || busy" @click="book">{{ busy ? '正在提交…' : '确认并自动预订' }}</button></template></Modal>
  <Modal v-if="guestModal" :title="guestEdit.id ? '编辑来宾资料' : '新增来宾资料'" @close="guestModal = false"><form id="guest-form" class="form-stack" @submit.prevent="saveGuest"><label>来宾姓名<input v-model="guestEdit.name" required maxlength="80"/></label><label>联系方式<input v-model="guestEdit.phone" required maxlength="32" autocomplete="tel"/></label><label>校方来宾编号<input v-model="guestEdit.identityRef" :readonly="!!guestEdit.id" required placeholder="同一来宾使用稳定的校方编号"/></label><p class="muted">来宾将关联到您负责的接待范围。</p></form><template #footer><button class="button secondary" @click="guestModal = false">取消</button><button form="guest-form" class="button primary" :disabled="guestBusy">保存来宾资料</button></template></Modal>
</template>
