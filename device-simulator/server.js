import express from 'express'
import { createServer } from 'node:http'
import net from 'node:net'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { Server } from 'socket.io'
import { decodeFrames, encodeFrame, parseCsv, simulatedDeviceCodes, telemetryForDevice } from './protocol.js'

const dirname = path.dirname(fileURLToPath(import.meta.url))
const webPort = Number(process.env.SIMULATOR_WEB_PORT || 9200)
const tcpHost = process.env.TCP_HOST || '127.0.0.1'
const tcpPort = Number(process.env.TCP_PORT || 9100)
const rows = parseCsv(await readFile(path.join(dirname, 'data', 'tower_crane_data.csv'), 'utf8'))
const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14)

const app = express()
const httpServer = createServer(app)
const io = new Server(httpServer)
app.use(express.static(path.join(dirname, 'public')))

let tcpSocket = null
let receiveBuffer = Buffer.alloc(0)
let rowIndex = 0
let sentCount = 0
let ackCount = 0
let autoTimer = null
let lastAck = null

function snapshot() {
  const nextDeviceCode = simulatedDeviceCodes[rowIndex % simulatedDeviceCodes.length]
  return {
    connected: Boolean(tcpSocket && !tcpSocket.destroyed), tcpHost, tcpPort, rowCount: rows.length,
    rowIndex, sentCount, ackCount, auto: Boolean(autoTimer), lastAck,
    deviceCodes: simulatedDeviceCodes, nextDeviceCode
  }
}
function emitState() { io.emit('state', snapshot()) }

function connectTcp() {
  if (tcpSocket && !tcpSocket.destroyed) return
  tcpSocket = net.createConnection({ host: tcpHost, port: tcpPort })
  tcpSocket.on('connect', emitState)
  tcpSocket.on('data', chunk => {
    receiveBuffer = Buffer.concat([receiveBuffer, chunk])
    const decoded = decodeFrames(receiveBuffer)
    receiveBuffer = decoded.remainder
    for (const message of decoded.messages) { lastAck = message; ackCount += 1; io.emit('ack', message) }
    emitState()
  })
  tcpSocket.on('close', () => { tcpSocket = null; emitState() })
  tcpSocket.on('error', error => { io.emit('simulator-error', error.message) })
}

function disconnectTcp() {
  tcpSocket?.destroy()
  tcpSocket = null
  emitState()
}

function sendOnce() {
  if (!tcpSocket || tcpSocket.destroyed) throw new Error('TCP 尚未连接')
  const deviceCode = simulatedDeviceCodes[rowIndex % simulatedDeviceCodes.length]
  const sampleIndex = Math.floor(rowIndex / simulatedDeviceCodes.length)
  const row = rows[sampleIndex % rows.length]
  const payload = telemetryForDevice(
    deviceCode,
    sampleIndex,
    row,
    `SIM-${runId}-${deviceCode}-${String(rowIndex + 1).padStart(6, '0')}`
  )
  tcpSocket.write(encodeFrame(payload))
  rowIndex = (rowIndex + 1) % rows.length
  sentCount += 1
  io.emit('telemetry', payload)
  emitState()
}

function setAuto(enabled) {
  clearInterval(autoTimer)
  autoTimer = null
  if (enabled) autoTimer = setInterval(() => { try { sendOnce() } catch (error) { io.emit('simulator-error', error.message) } }, 5000)
  emitState()
}

io.on('connection', socket => {
  socket.emit('state', snapshot())
  socket.on('connect-tcp', connectTcp)
  socket.on('disconnect-tcp', disconnectTcp)
  socket.on('send-once', () => { try { sendOnce() } catch (error) { socket.emit('simulator-error', error.message) } })
  socket.on('set-auto', enabled => setAuto(Boolean(enabled)))
})

httpServer.listen(webPort, '127.0.0.1', () => {
  console.log(`Device simulator: http://127.0.0.1:${webPort}`)
  console.log(`TCP target: ${tcpHost}:${tcpPort}`)
})

process.on('SIGINT', () => { setAuto(false); disconnectTcp(); httpServer.close(() => process.exit(0)) })
