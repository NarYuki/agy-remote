package moe.n4tsu.agyremote.state

/** Events still honoured after the user asked a turn to stop. */
internal val LATE_EVENTS: Set<String> = "turn.chat turn.finished turn.stopped turn.failed".split(' ').toSet()

/** Notification kinds the device can subscribe to. */
internal val PUSH_KINDS: List<String> = "finished failed approval cli_released".split(' ')
