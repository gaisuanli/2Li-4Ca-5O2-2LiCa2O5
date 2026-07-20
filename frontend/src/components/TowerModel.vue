<script setup>
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createTowerBindingController } from '../tower-model-bindings'

const props = defineProps({
  rotation: { type: Number, default: 0 },
  height: { type: Number, default: null },
  amplitude: { type: Number, default: null }
})
const canvasHost = ref(null)
const state = ref('LOADING')
const bindingAvailability = ref({ rotation: false, height: false, amplitude: false })
let renderer, scene, camera, controls, model, animationFrame, observer
let bindingController = null

const bindingStatus = computed(() => {
  const active = [
    bindingAvailability.value.rotation && '回转',
    bindingAvailability.value.height && '吊钩',
    bindingAvailability.value.amplitude && '幅度'
  ].filter(Boolean)
  return active.length ? `${active.join(' / ')}节点已绑定` : '当前模型无可绑定语义节点'
})

function applyTelemetryBindings() {
  bindingController?.apply({ rotation: props.rotation, height: props.height, amplitude: props.amplitude })
}

function displayMetric(value, unit) {
  const number = Number(value)
  return value == null || !Number.isFinite(number) ? '—' : `${number.toFixed(1)}${unit}`
}

function render() {
  controls?.update()
  renderer?.render(scene, camera)
  animationFrame = requestAnimationFrame(render)
}

function resize() {
  if (!renderer || !canvasHost.value) return
  const width = canvasHost.value.clientWidth
  const height = canvasHost.value.clientHeight
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

onMounted(() => {
  try {
    scene = new THREE.Scene()
    scene.background = new THREE.Color(0xf7f7f8)
    camera = new THREE.PerspectiveCamera(35, 1, 0.1, 5000)
    camera.position.set(170, 120, 210)
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.outputColorSpace = THREE.SRGBColorSpace
    canvasHost.value.appendChild(renderer.domElement)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.target.set(0, 55, 0)
    controls.minDistance = 80
    controls.maxDistance = 420
    scene.add(new THREE.HemisphereLight(0xffffff, 0x8a8d94, 2.2))
    const key = new THREE.DirectionalLight(0xffffff, 2.5)
    key.position.set(100, 180, 120)
    scene.add(key)
    const grid = new THREE.GridHelper(280, 20, 0xa9acb3, 0xd8d9dd)
    scene.add(grid)
    new GLTFLoader().load('/models/tower-crane.glb', gltf => {
      model = gltf.scene
      const box = new THREE.Box3().setFromObject(model)
      const size = box.getSize(new THREE.Vector3())
      const center = box.getCenter(new THREE.Vector3())
      const scale = 130 / Math.max(size.y, 1)
      model.scale.setScalar(scale)
      model.position.set(-center.x * scale, -box.min.y * scale, -center.z * scale)
      bindingController = createTowerBindingController(model)
      bindingAvailability.value = bindingController.availability
      applyTelemetryBindings()
      scene.add(model)
      state.value = 'READY'
    }, undefined, () => { state.value = 'ERROR' })
    observer = new ResizeObserver(resize)
    observer.observe(canvasHost.value)
    resize()
    render()
  } catch {
    state.value = 'UNSUPPORTED'
  }
})

watch(() => [props.rotation, props.height, props.amplitude], applyTelemetryBindings)

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  observer?.disconnect()
  controls?.dispose()
  scene?.traverse(object => {
    object.geometry?.dispose()
    if (Array.isArray(object.material)) object.material.forEach(material => material.dispose())
    else object.material?.dispose()
  })
  renderer?.dispose()
  renderer?.domElement?.remove()
})
</script>

<template>
  <div class="tower-model" ref="canvasHost" role="img" aria-label="塔吊三维模型，绑定回转角、吊钩高度和工作幅度，可拖拽旋转和缩放" :data-model-state="state">
    <div v-if="state !== 'READY'" class="model-state">
      <strong>{{ state === 'LOADING' ? '正在加载塔吊模型' : '三维模型不可用' }}</strong>
      <span>{{ state === 'LOADING' ? '模型约 19 MB，首次加载需要片刻' : '请使用支持 WebGL 的桌面浏览器' }}</span>
    </div>
    <div v-else class="model-caption"><span>{{ bindingStatus }}</span><strong>回转 {{ displayMetric(rotation, '°') }} · 吊钩 {{ displayMetric(height, 'm') }} · 幅度 {{ displayMetric(amplitude, 'm') }}</strong></div>
  </div>
</template>
