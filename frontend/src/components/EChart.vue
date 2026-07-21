<script setup>
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const SWISS_THEME = 'building-safety-swiss'

echarts.registerTheme(SWISS_THEME, {
  color: ['#002fa7', '#646870', '#101114', '#a9acb3'],
  backgroundColor: '#ffffff',
  textStyle: {
    color: '#101114',
    fontFamily: '"Helvetica Neue", Helvetica, Arial, sans-serif'
  },
  title: { textStyle: { color: '#101114', fontWeight: 700 } },
  legend: { textStyle: { color: '#646870', fontSize: 11 } },
  tooltip: {
    backgroundColor: '#ffffff',
    borderColor: '#101114',
    borderWidth: 1,
    borderRadius: 0,
    shadowBlur: 0,
    shadowOffsetX: 0,
    shadowOffsetY: 0,
    padding: 10,
    textStyle: { color: '#101114', fontSize: 12 }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#a9acb3', width: 1 } },
    axisTick: { lineStyle: { color: '#a9acb3' } },
    axisLabel: { color: '#646870', fontSize: 11, hideOverlap: true },
    splitLine: { lineStyle: { color: '#efeff1', width: 1 } }
  },
  valueAxis: {
    axisLine: { lineStyle: { color: '#a9acb3', width: 1 } },
    axisLabel: { color: '#646870', fontSize: 11, hideOverlap: true },
    splitLine: { lineStyle: { color: '#efeff1', width: 1 } }
  }
})

const props = defineProps({ option: { type: Object, required: true }, summary: { type: String, required: true }, height: { type: String, default: '300px' } })
const chartEl = ref(null)
let chart
let observer

onMounted(() => {
  chart = echarts.init(chartEl.value, SWISS_THEME)
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartEl.value)
})

watch(() => props.option, option => chart?.setOption(option, true), { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <figure class="chart-figure">
    <div ref="chartEl" class="chart-canvas" :style="{ height }" role="img" :aria-label="summary"></div>
    <figcaption>{{ summary }}</figcaption>
  </figure>
</template>
