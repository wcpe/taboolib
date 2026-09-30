'use strict'

const { runBot } = require('@wcpe/mc-testkit-bot')
const runTaboolibFull = require('./scenarios/taboolibFull')

runBot({
  scenarios: {
    'taboolib-full': runTaboolibFull
  }
})
