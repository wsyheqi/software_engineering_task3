<script setup lang="ts">
import type { Snapshot } from './types'
import { money, shortDate, nights, dateTime } from './format'
defineProps<{snapshot: Snapshot}>()
</script>
<template>
  <div class="quote-summary"><div><span class="eyebrow">本次住宿方案</span><h3>{{ snapshot.hotelName }}</h3><p class="muted">{{ snapshot.address }}</p><p>{{ shortDate(snapshot.plan.startDate) }} — {{ shortDate(snapshot.plan.endDate) }} · {{ nights(snapshot.plan.startDate, snapshot.plan.endDate) }} 晚</p></div><div class="price">¥ {{ money(snapshot.total) }}<small>整单住宿报价</small></div></div>
  <div class="table-wrap"><table><thead><tr><th>住宿晚</th><th>房型</th><th>间数</th><th>单价</th><th>小计</th></tr></thead><tbody><tr v-for="line in snapshot.lines" :key="line.inventoryId"><td>{{ shortDate(line.date) }}</td><td>{{ line.roomName }}</td><td>{{ line.quantity }}</td><td>¥ {{ money(line.price) }}</td><td>¥ {{ money(line.price * line.quantity) }}</td></tr></tbody></table></div>
  <div class="rule-box"><h4>取消期限与适用费用规则</h4><p><strong>取消截止：{{ dateTime(snapshot.rule.cancelDeadline) }}</strong></p><p>{{ snapshot.rule.cancellationText }}</p><p>{{ snapshot.rule.earlyCheckoutText }}</p><p>{{ snapshot.rule.noShowText }}</p><p class="muted">费用记录供线下结算，不代表已付款。确认时将保存各晚价格与本次规则。</p></div>
</template>
