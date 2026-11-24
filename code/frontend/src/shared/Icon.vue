<script setup lang="ts">
defineProps<{name: string; size?: number}>()
const paths: Record<string, string[]> = {
  bed: ['M3 18v-7h18v7M3 14h18M5 11V5h14v6M8 8h3m2 0h3M3 18v3m18-3v3'],
  grid: ['M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z'],
  orders: ['M8 3h8v3H8zM6 4H4v17h16V4h-2M8 11h8m-8 4h8'],
  bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4'],
  chart: ['M4 3v18h17M8 16v-5m5 5V6m5 10v-8'],
  log: ['M7 3h10l4 4v14H3V3h4M16 3v5h5M7 12h10M7 16h7'],
  user: ['M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2'],
  arrow: ['M5 12h14m-5-5 5 5-5 5'],
  back: ['M19 12H5m5-5-5 5 5 5'],
  search: ['M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0'],
  plus: ['M12 5v14M5 12h14'],
  close: ['M6 6l12 12M18 6 6 18'],
  pin: ['M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0'],
  check: ['M5 12l4 4L20 5'],
  shield: ['M12 2 3 6v6c0 6 9 10 9 10s9-4 9-10V6zM8 12l3 3 5-6'],
  logout: ['M9 3H3v18h6M9 12h12m-4-4 4 4-4 4'],
  menu: ['M4 6h16M4 12h16M4 18h16'],
  refresh: ['M20 7A9 9 0 0 0 4 5L2 8m0-5v5h5M4 17a9 9 0 0 0 16 2l2-3m0 5v-5h-5'],
  download: ['M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4'],
  info: ['M12 8v1m0 3v5M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0'],
  calendar: ['M4 5h16v16H4zM8 2v6m8-6v6M4 10h16'],
  building: ['M4 21V3h12v18M16 9h4v12M8 7h4M8 11h4M8 15h4M2 21h20'],
  spark: ['M12 2l3 7 7 3-7 3-3 7-3-7-7-3 7-3z'],
  eye: ['M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0'],
  clock: ['M12 6v6l4 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0'],
}
</script>
<template><svg :width="size || 20" :height="size || 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(path, index) in (paths[name] || paths.grid)" :key="index" :d="path"/></svg></template>
