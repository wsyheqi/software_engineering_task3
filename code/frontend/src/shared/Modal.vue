<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import Icon from './Icon.vue'
import { modalStack } from './modalStack'
defineProps<{title: string; wide?: boolean}>()
const emit = defineEmits<{close: []}>()
const container = ref<HTMLElement>()
const previous = document.activeElement as HTMLElement | null
const instanceId = Symbol('modal')
function key(event: KeyboardEvent) {
  if (modalStack.at(-1) !== instanceId) return
  if (event.key === 'Escape') emit('close')
  if (event.key === 'Tab' && container.value) {
    const nodes = [...container.value.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select, textarea, a[href]')]
    const first = nodes[0], last = nodes.at(-1)
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
onMounted(() => { modalStack.push(instanceId); document.body.style.overflow = 'hidden'; document.addEventListener('keydown', key); container.value?.focus() })
onUnmounted(() => { const position = modalStack.indexOf(instanceId); if (position >= 0) modalStack.splice(position, 1); if (!modalStack.length) document.body.style.overflow = ''; document.removeEventListener('keydown', key); if (previous?.isConnected) previous.focus() })
</script>
<template><Teleport to="body"><div class="modal-backdrop" @click.self="emit('close')"><section ref="container" class="modal" :class="{'modal-wide': wide}" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1"><header class="modal-header"><h2>{{ title }}</h2><button class="icon-button" aria-label="关闭" @click="emit('close')"><Icon name="close"/></button></header><div class="modal-body"><slot/></div><footer v-if="$slots.footer" class="modal-footer"><slot name="footer"/></footer></section></div></Teleport></template>
