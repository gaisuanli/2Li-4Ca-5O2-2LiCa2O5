<script setup>
defineProps({ label: { type: String, required: true }, tone: { type: String, default: 'neutral' } })
</script>

<template>
  <span class="status-badge" :class="`status-${tone}`"><span class="status-mark" aria-hidden="true"></span>{{ label }}</span>
</template>
