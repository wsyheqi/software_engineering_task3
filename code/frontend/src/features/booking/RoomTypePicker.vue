<script setup lang="ts">
import { computed } from 'vue'
import type { Plan, RoomType } from '../../shared/types'

defineProps<{options: RoomType[]; formId: string}>()
const items = defineModel<Plan['items']>({required: true})

const code = computed({
  get: () => items.value[0]?.code || '',
  set: value => { items.value = value ? [{code: value, quantity: items.value[0]?.quantity ?? 1}] : [] }
})
const quantity = computed({
  get: () => items.value[0]?.quantity ?? 1,
  set: value => { if (code.value) items.value = [{code: code.value, quantity: value}] }
})
</script>

<template>
  <fieldset class="room-type-picker">
    <legend>房型与间数</legend>
    <div class="room-type-fields">
      <label>房型
        <select v-model="code" :form="formId" required>
          <option value="" disabled>请选择房型</option>
          <option v-for="room in options" :key="room.code" :value="room.code">{{ room.name }}</option>
        </select>
      </label>
      <label>房间数量
        <input v-model.number="quantity" type="number" :disabled="!code" :form="formId" min="1" max="100" step="1" required/>
      </label>
    </div>
    <p v-if="!options.length" class="muted small">暂无可选房型，请联系管理员完善酒店房型资料。</p>
  </fieldset>
</template>

<style scoped>
.room-type-picker{border:0;padding:0;margin:24px 0 0;min-width:0}
.room-type-picker legend{padding:0;margin-bottom:10px;font-size:14px;font-weight:600;color:var(--ink)}
.room-type-fields{display:grid;grid-template-columns:minmax(0,1fr) 100px;gap:12px}
.room-type-fields label{min-width:0}
</style>
