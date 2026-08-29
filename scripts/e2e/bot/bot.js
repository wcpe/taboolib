const mineflayer = require('mineflayer')

const host = process.argv[2] || '127.0.0.1'
const port = parseInt(process.argv[3] || '25565')
const username = process.argv[4] || 'E2EBot'
const version = process.argv[5] || undefined

console.log(`[Mineflayer] 正在连接 ${host}:${port} 作为 ${username} (版本: ${version || '自动协商'})...`)

const bot = mineflayer.createBot({
  host: host,
  port: port,
  username: username,
  version: version,
  checkTimeoutInterval: 60 * 1000,
  respawn: true
})

const emittedProbes = new Set()
let e2eScoreboardName = null
const legacyTeams = new Map()

function emitProbe (name) {
  if (!emittedProbes.has(name)) {
    emittedProbes.add(name)
    console.log(`[E2E-PROBE] ${name}`)
  }
}

function inspectScoreboard (scoreboard) {
  const title = scoreboard && scoreboard.title && scoreboard.title.toString ? scoreboard.title.toString() : String(scoreboard ? scoreboard.title : '')
  if (title.includes('UPDATED')) {
    e2eScoreboardName = scoreboard.name
    emitProbe('SCOREBOARD_TITLE')
  }
}

bot.on('login', () => {
  console.log(`[Mineflayer] [OK] 机器人 ${username} 登录成功！`)
})

bot.on('spawn', () => {
  console.log(`[Mineflayer] [OK] 机器人 ${username} 已在世界生成 (Spawn)`)
})

bot.on('title', (text, type) => {
  const value = typeof text === 'string' ? text : JSON.stringify(text)
  if (type === 'title' && value.includes('E2E_TITLE')) emitProbe('TITLE')
})

bot.on('actionBar', (message) => {
  if (message.toString().includes('E2E_ACTION')) emitProbe('ACTION_BAR')
})

bot.on('scoreboardCreated', inspectScoreboard)
bot.on('scoreboardTitleChanged', inspectScoreboard)
bot.on('scoreboardDeleted', (scoreboard) => {
  if (scoreboard && scoreboard.name === e2eScoreboardName) emitProbe('SCOREBOARD_REMOVED')
})

function inspectTeam (team) {
  if (!team) return
  // 新版对象暴露 members，旧版对象仍可能只保留 membersMap。
  const members = Array.isArray(team.members) ? team.members : Object.keys(team.membersMap || {})
  if (!members.includes(username)) return
  const prefix = team.prefix ? team.prefix.toString() : ''
  const suffix = team.suffix ? team.suffix.toString() : ''
  const color = team.color ?? team.formatting
  const hasRedColor = color === 'red' || color === 12
  if (prefix.includes('[E2E]') && suffix.includes('!') && hasRedColor) emitProbe('TEAM')
}

bot.on('teamCreated', inspectTeam)
bot.on('teamUpdated', inspectTeam)

function inspectTeamPacket (packet) {
  const teamName = packet.team || packet.name
  if (packet.mode === 1) {
    legacyTeams.delete(teamName)
    return
  }
  const team = legacyTeams.get(teamName) || { players: new Set() }
  if (packet.mode === 0 || packet.mode === 2) {
    team.prefix = packet.prefix ? packet.prefix.toString() : ''
    team.suffix = packet.suffix ? packet.suffix.toString() : ''
    team.color = packet.color ?? packet.formatting
  }
  if (packet.mode === 0) {
    team.players = new Set(packet.players || [])
  } else if (packet.mode === 3) {
    for (const player of packet.players || []) team.players.add(player)
  } else if (packet.mode === 4) {
    for (const player of packet.players || []) team.players.delete(player)
  }
  legacyTeams.set(teamName, team)
  // 旧 Mineflayer 不保留队伍颜色，直接以协议包累积状态后验证完整可观察行为。
  const hasRedColor = team.color === 'red' || team.color === 12
  if (team.players.has(username) && team.prefix.includes('[E2E]') && team.suffix.includes('!') && hasRedColor) emitProbe('TEAM')
}

// Mineflayer 1.8 使用 scoreboard_team，1.9+ 将同一类协议包规范化为 teams。
bot._client.on('scoreboard_team', inspectTeamPacket)
bot._client.on('teams', inspectTeamPacket)

bot._client.on('scoreboard_objective', (packet) => {
  const title = typeof packet.displayText === 'string' ? packet.displayText : JSON.stringify(packet.displayText)
  if ((packet.action === 0 || packet.action === 2) && title.includes('UPDATED')) {
    e2eScoreboardName = packet.name
    emitProbe('SCOREBOARD_TITLE')
  }
  if (packet.action === 1 && packet.name === e2eScoreboardName) emitProbe('SCOREBOARD_REMOVED')
})

bot._client.on('open_sign_entity', (packet) => {
  console.log(`[Mineflayer] [OK] 收到牌子编辑器数据包，提交 E2E 文本`)
  // 新版协议栈会丢弃编辑器打开事件回调栈内同步提交的更新包，模拟真实输入延迟发送。
  setTimeout(() => {
    bot._client.write('update_sign', {
      location: packet.location,
      isFrontText: packet.isFrontText !== false,
      text1: 'E2E',
      text2: '',
      text3: '',
      text4: ''
    })
  }, 100)
})

bot.on('kicked', (reason) => {
  console.log(`[Mineflayer] [KICK] 机器人被踢出: ${reason}`)
})

bot.on('error', (err) => {
  console.log(`[Mineflayer] [ERROR] 连接错误: ${err.message}`)
})

bot.on('end', () => {
  console.log(`[Mineflayer] 连接已断开`)
  process.exit(0)
})

// 保持在服内 60 秒供测试采集
setTimeout(() => {
  console.log(`[Mineflayer] 测试期结束，主动退出`)
  try {
    bot.quit()
  } catch (e) {}
  process.exit(0)
}, 60000)
