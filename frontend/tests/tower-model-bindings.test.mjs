import test from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import {
  createTowerBindingController,
  towerModelNodeNames
} from '../src/tower-model-bindings.js'

function createModelFixture() {
  const root = new THREE.Group()
  const upperAssembly = new THREE.Group()
  upperAssembly.name = towerModelNodeNames.upperAssembly
  upperAssembly.rotation.y = Math.PI / 2
  const trolley = new THREE.Group()
  trolley.name = towerModelNodeNames.trolley
  trolley.position.x = 3900
  const hook = new THREE.Group()
  hook.name = towerModelNodeNames.hook
  hook.position.set(3900, -1700, 0)
  const cable = new THREE.Mesh(new THREE.BoxGeometry(2, 1700, 2), new THREE.MeshBasicMaterial())
  cable.name = towerModelNodeNames.cable
  cable.position.set(3900, -740, 0)
  cable.scale.y = 1.1
  upperAssembly.add(trolley, hook, cable)
  root.add(upperAssembly)
  return { root, upperAssembly, trolley, hook, cable }
}

test('real tower nodes bind rotation, height and amplitude independently', () => {
  const fixture = createModelFixture()
  const controller = createTowerBindingController(fixture.root)
  assert.deepEqual(controller.availability, { rotation: true, amplitude: true, height: true })
  const baselineQuaternion = fixture.upperAssembly.quaternion.clone()
  const baselineCableTop = fixture.cable.position.y + 850 * fixture.cable.scale.y

  controller.apply({ rotation: 30, height: 52, amplitude: 45 })

  assert.ok(baselineQuaternion.angleTo(fixture.upperAssembly.quaternion) > 0.5)
  assert.equal(fixture.trolley.position.x, 4500)
  assert.equal(fixture.hook.position.x, 4500)
  assert.equal(fixture.hook.position.y, -1000)
  assert.ok(fixture.cable.scale.y < 1.1)
  assert.ok(Math.abs((fixture.cable.position.y + 850 * fixture.cable.scale.y) - baselineCableTop) < 1e-9)
})

test('missing semantic nodes safely disable only unavailable bindings', () => {
  const controller = createTowerBindingController(new THREE.Group())
  assert.deepEqual(controller.availability, { rotation: false, amplitude: false, height: false })
  assert.doesNotThrow(() => controller.apply({ rotation: 180, height: 40, amplitude: 30 }))
})

test('GLTFLoader-sanitized child node names still bind all three channels', () => {
  const fixture = createModelFixture()
  fixture.trolley.name = THREE.PropertyBinding.sanitizeNodeName(towerModelNodeNames.trolley)
  fixture.hook.name = THREE.PropertyBinding.sanitizeNodeName(towerModelNodeNames.hook)
  fixture.cable.name = THREE.PropertyBinding.sanitizeNodeName(towerModelNodeNames.cable)

  const controller = createTowerBindingController(fixture.root)

  assert.deepEqual(controller.availability, { rotation: true, amplitude: true, height: true })
  assert.doesNotThrow(() => controller.apply({ rotation: 20, height: 48, amplitude: 42 }))
})
