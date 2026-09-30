'use strict'

/**
 * TabooLib full 场景：入服后监听服务端探针包，收到 E2E_PROBE_COLLECT 时经聊天回报。
 *
 * 探针名与历史 scripts/e2e/bot 对齐；判定真源仍在 harness（合并探针后写 RESULT_FILE）。
 */
module.exports = async function runTaboolibFull (context) {
  const { bot, config, log } = context
  const probes = new Set()
  const username = config.username || bot.username
  let e2eScoreboardName = null
  const legacyTeams = new Map()

  function emitProbe (name) {
    if (!probes.has(name)) {
      probes.add(name)
      log(`probe ${name}`)
      console.log(`[E2E-PROBE] ${name}`)
    }
  }

  function inspectScoreboard (scoreboard) {
    const title =
      scoreboard && scoreboard.title && scoreboard.title.toString
        ? scoreboard.title.toString()
        : String(scoreboard ? scoreboard.title : '')
    if (title.includes('UPDATED')) {
      e2eScoreboardName = scoreboard.name
      emitProbe('SCOREBOARD_TITLE')
    }
  }

  function inspectTeam (team) {
    if (!team) return
    const members = Array.isArray(team.members) ? team.members : Object.keys(team.membersMap || {})
    if (!members.includes(username)) return
    const prefix = team.prefix ? team.prefix.toString() : ''
    const suffix = team.suffix ? team.suffix.toString() : ''
    const color = team.color ?? team.formatting
    const hasRedColor = color === 'red' || color === 12
    if (prefix.includes('[E2E]') && suffix.includes('!') && hasRedColor) emitProbe('TEAM')
  }

  function inspectTeamPacket (packet) {
    const teamName = packet.team || packet.name
    if (packet.mode === 1) {
      legacyTeams.delete(teamName)
      return
    }
    if (packet.mode === 0) {
      legacyTeams.set(teamName, packet)
    }
    if (packet.players && packet.players.includes(username)) {
      const prefix = packet.prefix ? String(packet.prefix) : ''
      const suffix = packet.suffix ? String(packet.suffix) : ''
      if (prefix.includes('[E2E]') && suffix.includes('!')) emitProbe('TEAM')
    }
  }

  bot.on('spawn', () => {
    log(`spawn as ${username}, action=${config.action}`)
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

  bot.on('teamCreated', inspectTeam)
  bot.on('teamUpdated', inspectTeam)

  bot.on('message', (message) => {
    const text = message.toString()
    if (text.includes('E2E_PROBE_COLLECT')) {
      const payload = Array.from(probes).join(',')
      log(`collect probes -> ${payload || '(empty)'}`)
      bot.chat(`E2E_PROBES:${payload}`)
      return
    }
    if (text.includes('E2E_SIGN')) emitProbe('SIGN_CALLBACK')
    if (text.includes('E2E_AI_LIFECYCLE')) emitProbe('AI_LIFECYCLE')
    if (text.includes('E2E_AI_NAVIGATION_ENTITY')) emitProbe('AI_NAVIGATION_ENTITY')
    if (text.includes('E2E_AI_NAVIGATION_LOCATION')) emitProbe('AI_NAVIGATION_LOCATION')
  })

  bot._client.on('packet', (data, packetMeta) => {
    if (packetMeta.name === 'scoreboard_team' || packetMeta.name === 'teams') {
      inspectTeamPacket(data)
    }
  })

  // 保持在线直到 harness 关服；探针由 E2E_PROBE_COLLECT 触发回报
  await new Promise((resolve) => {
    bot.once('end', resolve)
  })
  log(`bot ended, probes=${Array.from(probes).join(',')}`)
}
