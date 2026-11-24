<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../shared/api'
import { showError } from '../shared/state'
import Icon from '../shared/Icon.vue'
const emit = defineEmits<{authenticated: []}>()
const username = ref('reception'), password = ref('Demo@123456'), busy = ref(false)
const accounts = [{id: 'reception', title: '学校接待人员', icon: 'user'}, {id: 'hotel', title: '酒店工作人员', icon: 'building'}, {id: 'admin', title: '平台管理员', icon: 'shield'}, {id: 'guest', title: '来宾本人', icon: 'bed'}]
async function submit() { busy.value = true; try { await login(username.value, password.value); emit('authenticated') } catch (e) { showError(e) } finally { busy.value = false } }
</script>
<template>
  <main class="login-page">
    <section class="login-story"><a class="brand"><span class="brand-mark">宾</span><span><strong>学宾接待</strong><small>CAMPUS HOSPITALITY</small></span></a><div class="login-copy"><span class="eyebrow">每一次到访，都妥善安排</span><h1>让学术相聚，<br/>从安心入住开始。</h1><p>为学校接待连接来宾与酒店。<br/>从住宿需求、方案预订到实际入住，<br/>在同一工作空间有序完成。</p></div><svg class="campus-art" viewBox="0 0 620 240" fill="none" aria-hidden="true"><path d="M25 207H596M90 207V111h128v96M228 207V88h170v119M412 207V126h117v81M204 88h218L313 25 204 88Z" stroke="#9294e2" stroke-width="2"/><path d="M268 207v-77h91v77M281 130v77m64-77v77M127 136h18v23h-18zm38 0h18v23h-18zm-38 41h18v22h-18zm38 0h18v22h-18zm278-23h17v21h-17zm35 0h17v21h-17zM61 207v-39m0 0c-32-11-22-52 0-48 26-1 28 39 0 48m500 39v-58m0 0c-39-10-25-64 0-58 29-2 37 50 0 58" stroke="#7278bc" stroke-width="2"/><circle cx="474" cy="55" r="22" fill="#7771de" opacity=".45"/><path d="M310 42v29m-15-14h30" stroke="#c9c5ff" stroke-width="2"/></svg><div class="login-footer"><Icon name="shield" :size="16"/> 仅供本校来宾接待使用</div></section>
    <section class="login-form-side"><div class="login-form"><span class="eyebrow purple">欢迎回来</span><h2>进入接待工作空间</h2><p class="muted">使用已分配的账号登录，查看对应的工作安排。</p><form @submit.prevent="submit"><label>账号<input v-model="username" name="username" autocomplete="username" required placeholder="请输入账号"/></label><label>密码<input v-model="password" type="password" name="password" autocomplete="current-password" required placeholder="请输入密码"/></label><button class="button primary full" :disabled="busy">{{ busy ? '正在登录…' : '登录工作空间' }}<Icon name="arrow"/></button></form><div class="demo-login"><span>演示账号 · 点击选择角色</span><div class="demo-grid"><button v-for="account in accounts" :key="account.id" :class="{selected: username === account.id}" @click="username = account.id; password = 'Demo@123456'"><Icon :name="account.icon" :size="19"/>{{ account.title }}</button></div><small>演示密码：Demo@123456</small></div><p class="login-note">账号由平台管理员分配。来宾仅可查看本人住宿安排。</p></div></section>
  </main>
</template>
