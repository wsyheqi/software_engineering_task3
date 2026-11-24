<script setup lang="ts">import {statusNames} from './format'; defineProps<{status: string}>()</script>
<template><span class="badge" :class="'badge-' + status.toLowerCase()">{{ statusNames[status] || status }}</span></template>
