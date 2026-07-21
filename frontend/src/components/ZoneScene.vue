<script setup>
const props = defineProps({ zones: { type: Array, default: () => [] }, selectedId: Number })
defineEmits(['select'])

function percent(value) { return `${Number(value) * 100}%` }
</script>

<template>
  <div class="site-scene" aria-label="工地区域交互场景">
    <img src="/assets/site-scene.png" alt="贵州大学东校区实训工地三维效果场景" />
    <button
      v-for="zone in zones"
      :key="zone.id"
      class="zone-hit"
      :class="{ selected: selectedId === zone.id }"
      :style="{ left: percent(zone.mapX), top: percent(zone.mapY), width: percent(zone.mapWidth), height: percent(zone.mapHeight) }"
      type="button"
      :aria-pressed="selectedId === zone.id"
      :aria-label="`${zone.name}，${zone.deviceCount} 台设备，${zone.onlineCount} 台在线`"
      @click="$emit('select', zone.id)"
    >
      <span>{{ zone.name }}</span>
      <small>{{ zone.onlineCount }}/{{ zone.deviceCount }} 在线</small>
    </button>
    <div class="scene-caption"><strong>区域导航</strong><span>选择建筑区域，设备列表将同步筛选</span></div>
  </div>
</template>
