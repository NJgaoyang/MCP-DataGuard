<script setup lang="ts">
defineProps<{name:string;size?:number}>();
</script>
<template>
 <svg :width="size||20" :height="size||20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <g v-if="name==='dashboard'"><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/></g>
  <g v-else-if="name==='agents'"><rect x="4" y="7" width="16" height="13" rx="3"/><path d="M12 3v4M9 3h6M8 13h.01M16 13h.01M9 17h6M2 11v5m20-5v5"/></g>
  <g v-else-if="name==='datasources'"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></g>
  <g v-else-if="name==='rules'"><path d="m12 2 8 4v5c0 5.3-3.5 8.8-8 11-4.5-2.2-8-5.7-8-11V6z"/></g>
  <g v-else-if="name==='flowable'"><path d="M7 3h9l4 4v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM16 3v5h4M9 12h6M9 16h4"/><path d="m14 18 2 2 4-4"/></g>
  <g v-else-if="name==='audit'"><path d="M4 21V13M9 21V8M14 21V11M19 21V3"/></g>
  <g v-else-if="name==='users'"><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></g>
  <g v-else-if="name==='bell'"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 9h18c0-1-3-2-3-9M10 21h4"/></g>
  <g v-else-if="name==='chevron'"><path d="m7 10 5 5 5-5"/></g>
  <g v-else-if="name==='arrow'"><path d="M4 12h15m-6-6 6 6-6 6"/></g>
  <g v-else-if="name==='warning'"><path d="m12 3 10 18H2zM12 9v5m0 4h.01"/></g>
  <g v-else-if="name==='clock'"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/></g>
  <g v-else-if="name==='document'"><path d="M6 2h9l4 4v16H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM15 2v5h4M8 12h8M8 16h5"/><path d="m14 17 2 2 4-4"/></g>
  <g v-else-if="name==='search'"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/></g>
  <g v-else-if="name==='shield-check'"><path d="m12 2 8 4v5c0 5.3-3.5 8.8-8 11-4.5-2.2-8-5.7-8-11V6z"/><path d="m8 12 3 3 5-6"/></g>
  <g v-else-if="name==='check'"><path d="m4 12 5 5L20 6"/></g>
 </svg>
</template>
