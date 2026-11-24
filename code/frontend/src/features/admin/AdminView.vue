<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api, mutate, session } from '../../shared/api'
import { catalog, refreshCatalog, toast, showError } from '../../shared/state'
import { roleNames } from '../../shared/format'
import type { Hotel, Site, Rule } from '../../shared/types'
import Icon from '../../shared/Icon.vue'
import Modal from '../../shared/Modal.vue'
import './admin-deletion.css'
const tab = ref('hotels'), editKind = ref(''), busy = ref(false)
const tabs = [{id: 'hotels', title: '酒店与学校审核'}, {id: 'sites', title: '接待地点'}, {id: 'distances', title: '核实距离'}, {id: 'rules', title: '约定费用规则'}, {id: 'users', title: '账号与角色'}]
const form = reactive({id: '', name: '', city: '南京', address: '', description: '', audited: false, evaluation: null as number | null, version: 0, hotelId: '', siteId: '', distanceKm: 0, earlyCheckoutText: '', noShowText: '', username: '', displayName: '', password: '', role: 'RECEPTION', guestId: '', email: '', enabled: true})
const title = computed(() => ({hotels: '酒店资料与学校质量审核', sites: '维护接待地点', distances: '维护核实距离', rules: '维护约定费用规则', users: '管理账号与角色'})[editKind.value])
const deletion = ref<{kind: 'hotels' | 'users'; id: string; name: string; version: number}>()
const deleteError = ref('')
function remove(kind: 'hotels' | 'users', target: Hotel | Record<string, unknown>) {
  const row = {...target} as Record<string, unknown>
  deletion.value = {kind, id: String(row.id), name: String(kind === 'hotels' ? row.name : `${row.username}（${row.displayName}）`), version: Number(row.version)}
  deleteError.value = ''
}
async function confirmDelete() {
  if (!deletion.value) return
  busy.value = true; deleteError.value = ''
  try {
    const target = deletion.value
    const result = await mutate(`/admin/${target.kind}/${target.id}`, {version: target.version}, 'DELETE')
    toast(result.message)
    if (result.status === 'SUCCEEDED') { deletion.value = undefined; await refreshCatalog() }
  } catch (error) {
    deleteError.value = error instanceof Error ? error.message : '删除未完成，请刷新后重试'
    showError(error)
  } finally { busy.value = false }
}
function open(kind: string, value?: Hotel | Site | Rule | Record<string, unknown>) {
  Object.assign(form, {id: '', name: '', city: '南京', address: '', description: '', audited: false, evaluation: null, version: 0, hotelId: catalog.value?.hotels[0]?.id || '', siteId: catalog.value?.sites[0]?.id || '', distanceKm: 0, earlyCheckoutText: '', noShowText: '', username: '', displayName: '', password: '', role: 'RECEPTION', guestId: catalog.value?.guests[0]?.id || '', email: '', enabled: true}, value || {})
  editKind.value = kind
}
async function save() {
  busy.value = true
  try { if (editKind.value === 'users') { const r = await api<{message: string}>('/admin/users', {method: 'POST', body: JSON.stringify(form)}); toast(r.message) } else { const r = await mutate('/admin/' + editKind.value, form); toast(r.message); if (r.status !== 'SUCCEEDED') return } editKind.value = ''; await refreshCatalog() } catch (e) { showError(e) } finally { busy.value = false }
}
</script>
<template>
  <div class="page-heading"><div><span class="eyebrow">PLATFORM ADMINISTRATION</span><h1>平台管理</h1><p>维护校方接待基础资料、质量依据、约定规则与账号责任关系。</p></div><span class="soft-tag"><Icon name="shield" :size="16"/> 学校管理员权限</span></div>
  <div class="metric-grid three"><div class="metric-card"><span>合作酒店</span><strong>{{ catalog?.hotels.length || 0 }}</strong><small>学校来宾接待资源</small></div><div class="metric-card"><span>质量合格酒店</span><strong>{{ catalog?.hotels.filter(h => h.audited && (h.evaluation || 0) >= 70).length || 0 }}</strong><small>已审核且评价至少70分</small></div><div class="metric-card"><span>接待地点</span><strong>{{ catalog?.sites.length || 0 }}</strong><small>核实距离的统一基准</small></div></div>
  <section class="card"><div class="admin-tabs"><button v-for="t in tabs" :key="t.id" :class="{active: tab === t.id}" @click="tab = t.id">{{ t.title }}</button></div>
    <template v-if="tab === 'hotels'"><div class="section-heading"><h2>酒店资料与质量依据</h2><button class="button primary" @click="open('hotels')"><Icon name="plus" :size="17"/>新增酒店</button></div><p class="muted small">未审核、无评价或低于70分的酒店不参与推荐和预订。</p><div class="table-wrap"><table><thead><tr><th>酒店 / 地址</th><th>学校审核</th><th>学校评价</th><th>候选资格</th><th></th></tr></thead><tbody><tr v-for="h in catalog?.hotels" :key="h.id"><td><strong>{{ h.name }}</strong><small class="cell-sub">{{ h.address }}</small></td><td>{{ h.audited ? '已审核' : '待审核' }}</td><td>{{ h.evaluation === null ? '暂无评价' : h.evaluation + ' 分' }}</td><td><span class="badge" :class="h.audited && (h.evaluation || 0) >= 70 ? 'badge-checked_in' : 'badge-cancelled'">{{ h.audited && (h.evaluation || 0) >= 70 ? '可参与筛选' : '未满足条件' }}</span></td><td><div class="admin-row-actions"><button class="text-button" @click="open('hotels', h)">编辑资料与审核</button><button class="text-button delete-button" :aria-label="'删除酒店 ' + h.name" @click="remove('hotels', h)">删除酒店</button></div></td></tr></tbody></table></div></template>
    <template v-if="tab === 'sites'"><div class="section-heading"><h2>接待地点档案</h2><button class="button primary" @click="open('sites')"><Icon name="plus" :size="17"/>新增地点</button></div><div class="table-wrap"><table><thead><tr><th>接待地点</th><th>地址</th><th></th></tr></thead><tbody><tr v-for="s in catalog?.sites" :key="s.id"><td>{{ s.name }}</td><td>{{ s.address }}</td><td><button class="text-button" @click="open('sites', s)">编辑地点</button></td></tr></tbody></table></div></template>
    <template v-if="tab === 'distances'"><div class="section-heading"><h2>酒店至接待地点的核实距离</h2><button class="button primary" @click="open('distances')">新增距离关系</button></div><div class="table-wrap"><table><thead><tr><th>酒店</th><th>接待地点</th><th>距离 / km</th><th></th></tr></thead><tbody><tr v-for="d in catalog?.distances" :key="d.hotelId + d.siteId"><td>{{ catalog?.hotels.find(h => h.id === d.hotelId)?.name }}</td><td>{{ catalog?.sites.find(s => s.id === d.siteId)?.name }}</td><td>{{ d.distanceKm }}</td><td><button class="text-button" @click="open('distances', d)">更新核实距离</button></td></tr></tbody></table></div></template>
    <template v-if="tab === 'rules'"><div class="section-heading"><h2>与酒店约定的费用规则</h2></div><div class="notice purple-notice"><Icon name="info"/><span>新规则只用于后续确认方案，历史订单保留原有规则与价格快照。</span></div><article v-for="r in catalog?.rules" :key="r.hotelId" class="rule-admin"><div class="section-heading"><h3>{{ catalog?.hotels.find(h => h.id === r.hotelId)?.name }}<small class="muted"> · 版本 {{ r.version }}</small></h3><button class="text-button" @click="open('rules', r)">更新约定规则</button></div><p>{{ r.cancellationText }}</p><p>{{ r.earlyCheckoutText }}</p><p>{{ r.noShowText }}</p></article></template>
    <template v-if="tab === 'users'"><div class="section-heading"><h2>账号与责任绑定</h2><button class="button primary" @click="open('users')"><Icon name="plus" :size="17"/>新增账号</button></div><div class="table-wrap"><table><thead><tr><th>账号</th><th>姓名 / 名称</th><th>角色</th><th>绑定范围</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="u in catalog?.users" :key="String(u.id)"><td>{{ u.username }}</td><td>{{ u.displayName }}</td><td>{{ roleNames[String(u.role)] }}</td><td>{{ u.hotelId ? catalog?.hotels.find(h => h.id === u.hotelId)?.name : u.guestId ? catalog?.guests.find(g => g.id === u.guestId)?.name : '按业务责任关系' }}</td><td>{{ u.enabled ? '有效' : '已停用' }}</td><td><div class="admin-row-actions"><button class="text-button" @click="open('users', u)">维护账号</button><button class="text-button delete-button" :aria-label="'删除账号 ' + u.username" :disabled="u.id === session.user?.id" :title="u.id === session.user?.id ? '不能删除当前登录账号' : '删除账号并保留历史记录'" @click="remove('users', u)">删除账号</button></div></td></tr></tbody></table></div></template>
  </section>
  <Modal v-if="editKind" :title="title || '维护资料'" @close="editKind = ''"><form id="admin-form" class="form-stack" @submit.prevent="save"><template v-if="editKind === 'hotels'"><label>酒店名称<input v-model="form.name" required maxlength="100"/></label><div class="form-grid two"><label>城市<input v-model="form.city" required/></label><label>学校评价 / 0～100<input v-model.number="form.evaluation" type="number" min="0" max="100" step="0.01" placeholder="未评价可留空"/></label></div><label>酒店地址<input v-model="form.address" required maxlength="255"/></label><label>酒店介绍<textarea v-model="form.description" maxlength="500"/></label><label class="check-line"><input v-model="form.audited" type="checkbox"/>学校质量审核已通过（已有业务依据）</label></template><template v-if="editKind === 'sites'"><label>接待地点名称<input v-model="form.name" required/></label><label>地址<input v-model="form.address" required/></label><p class="muted">地点变化后，请同步核实相关酒店距离。</p></template><template v-if="editKind === 'distances'"><label>酒店<select v-model="form.hotelId" required><option v-for="h in catalog?.hotels" :key="h.id" :value="h.id">{{ h.name }}</option></select></label><label>接待地点<select v-model="form.siteId" required><option v-for="s in catalog?.sites" :key="s.id" :value="s.id">{{ s.name }}</option></select></label><label>校方核实距离 / km<input v-model.number="form.distanceKm" type="number" min="0" step="0.001" required/></label></template><template v-if="editKind === 'rules'"><div class="notice">取消期限固定为入住前一日18:00；该时点起按逐房锁定首晚价格之和记录整单取消费用。</div><label>提前退房约定<textarea v-model="form.earlyCheckoutText" required maxlength="500" rows="4"/></label><label>未入住费用约定<textarea v-model="form.noShowText" required maxlength="500" rows="4"/></label></template><template v-if="editKind === 'users'"><div class="form-grid two"><label>账号<input v-model="form.username" :readonly="!!form.id" required pattern="[A-Za-z0-9_.-]{3,60}" autocomplete="off"/></label><label>姓名 / 名称<input v-model="form.displayName" required/></label></div><label>角色<select v-model="form.role" :disabled="!!form.id"><option v-for="(name, value) in roleNames" :key="value" :value="value">{{ name }}</option></select></label><label v-if="form.role === 'HOTEL'">所属酒店<select v-model="form.hotelId" required><option v-for="h in catalog?.hotels" :key="h.id" :value="h.id">{{ h.name }}</option></select></label><label v-if="form.role === 'GUEST'">绑定来宾本人<select v-model="form.guestId" required><option v-for="g in catalog?.guests" :key="g.id" :value="g.id">{{ g.name }} · {{ g.identityRef }}</option></select></label><label>学校邮箱<input v-model="form.email" type="email" placeholder="用于相关异常通知"/></label><label>{{ form.id ? '新密码（留空保持原密码）' : '初始密码' }}<input v-model="form.password" type="password" :required="!form.id" minlength="10" autocomplete="new-password"/></label><label class="check-line"><input v-model="form.enabled" type="checkbox"/>账号有效</label></template></form><template #footer><button class="button secondary" @click="editKind = ''">返回</button><button form="admin-form" class="button primary" :disabled="busy">{{ busy ? '正在保存…' : '保存资料' }}</button></template></Modal>
  <Modal v-if="deletion" :title="deletion.kind === 'hotels' ? '删除酒店' : '删除账号'" @close="!busy && (deletion = undefined)">
    <p>确认删除 <strong>{{ deletion.name }}</strong>？</p>
    <div class="notice warning"><Icon name="info"/><span v-if="deletion.kind === 'hotels'">删除后酒店将移出管理及可选房源列表，旧方案不能继续预订。历史订单、费用、统计与操作日志保留。有未完成订单、待处理费用或有效工作人员绑定时，须先处理关联业务。</span><span v-else>删除后账号将移出账号列表并失去登录权限，已有会话也不能继续访问业务。历史订单、来宾资料、责任关系与操作日志保留；未完成的负责业务须先处理。</span></div>
    <p v-if="deleteError" role="alert" class="danger-text">{{ deleteError }}</p>
    <template #footer><button class="button secondary" :disabled="busy" @click="deletion = undefined">取消</button><button class="button danger" :disabled="busy" @click="confirmDelete">{{ busy ? '正在删除…' : '确认删除' }}</button></template>
  </Modal>
</template>
