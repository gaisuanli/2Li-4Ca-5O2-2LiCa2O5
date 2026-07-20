<script setup>
defineProps({ title: { type: String, required: true }, message: { type: String, required: true }, action: String })
defineEmits(['action'])
</script>

<template>
  <section class="page-state" role="status">
    <span class="state-rule" aria-hidden="true"></span>
    <h2>{{ title }}</h2>
    <p>{{ message }}</p>
    <button v-if="action" class="button button-secondary" type="button" @click="$emit('action')">{{ action }}</button>
  </section>
</template>
