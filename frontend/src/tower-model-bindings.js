import * as THREE from 'three'

export const towerModelNodeNames = Object.freeze({
  upperAssembly: 'Plane002',
  trolley: 'Plane002_Material #44_0.001',
  hook: 'Plane002_Material #44_0.002',
  cable: 'Plane002_Material #44_0.003'
})

export const towerBindingReference = Object.freeze({
  height: 45,
  amplitude: 39,
  modelUnitsPerMeter: 100
})

const verticalAxis = new THREE.Vector3(0, 1, 0)

function finiteOr(value, fallback) {
  const number = Number(value)
  return value == null || !Number.isFinite(number) ? fallback : number
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value))
}

function findNode(root, name) {
  if (!root?.getObjectByName) return null
  return root.getObjectByName(name)
    || root.getObjectByName(THREE.PropertyBinding.sanitizeNodeName(name))
    || null
}

/**
 * Creates a stateful binding controller for the real node layout in
 * tower-crane.glb. Every channel is optional so a replacement model with
 * missing/generic nodes still renders instead of throwing during telemetry.
 */
export function createTowerBindingController(root) {
  const nodes = {
    upperAssembly: findNode(root, towerModelNodeNames.upperAssembly),
    trolley: findNode(root, towerModelNodeNames.trolley),
    hook: findNode(root, towerModelNodeNames.hook),
    cable: findNode(root, towerModelNodeNames.cable)
  }
  const baselines = Object.fromEntries(Object.entries(nodes).map(([key, node]) => [key, node ? {
    position: node.position.clone(),
    scale: node.scale.clone(),
    quaternion: node.quaternion.clone()
  } : null]))

  let cableBounds = null
  if (nodes.cable?.geometry) {
    nodes.cable.geometry.computeBoundingBox()
    const bounds = nodes.cable.geometry.boundingBox
    if (bounds && Number.isFinite(bounds.min.y) && Number.isFinite(bounds.max.y) && bounds.max.y > bounds.min.y) {
      cableBounds = bounds.clone()
    }
  }

  const availability = Object.freeze({
    rotation: Boolean(nodes.upperAssembly),
    amplitude: Boolean(nodes.trolley || nodes.hook || nodes.cable),
    height: Boolean(nodes.hook || (nodes.cable && cableBounds))
  })

  function apply({ rotation, height, amplitude } = {}) {
    const yaw = THREE.MathUtils.degToRad(finiteOr(rotation, 0))
    const normalizedHeight = clamp(finiteOr(height, towerBindingReference.height), 0, 100)
    const normalizedAmplitude = clamp(finiteOr(amplitude, towerBindingReference.amplitude), 0, 80)
    const heightDelta = (normalizedHeight - towerBindingReference.height) * towerBindingReference.modelUnitsPerMeter
    const amplitudeDelta = (normalizedAmplitude - towerBindingReference.amplitude) * towerBindingReference.modelUnitsPerMeter

    if (nodes.upperAssembly) {
      const yawQuaternion = new THREE.Quaternion().setFromAxisAngle(verticalAxis, yaw)
      nodes.upperAssembly.quaternion.copy(baselines.upperAssembly.quaternion).multiply(yawQuaternion)
    }

    for (const key of ['trolley', 'hook', 'cable']) {
      if (nodes[key]) nodes[key].position.x = baselines[key].position.x + amplitudeDelta
    }

    if (nodes.hook) nodes.hook.position.y = baselines.hook.position.y + heightDelta

    if (nodes.cable && cableBounds) {
      const baseline = baselines.cable
      const span = cableBounds.max.y - cableBounds.min.y
      const nextScaleY = clamp(baseline.scale.y - heightDelta / span, 0.12, 2.2)
      const fixedTop = baseline.position.y + cableBounds.max.y * baseline.scale.y
      nodes.cable.scale.y = nextScaleY
      nodes.cable.position.y = fixedTop - cableBounds.max.y * nextScaleY
    }
  }

  return { availability, apply, nodes }
}
