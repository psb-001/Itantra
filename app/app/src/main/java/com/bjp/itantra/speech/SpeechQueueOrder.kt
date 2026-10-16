package com.bjp.itantra.speech

import com.bjp.itantra.Message
import java.util.concurrent.LinkedBlockingDeque

/**
 * The speech worker's queue ordering rules, in one place so they can be tested on the JVM.
 *
 * Everything about ordering is plain FIFO, with a single exception: ALERTs go to the head of the
 * queue and are allowed to preempt what is playing. Two rules follow from that, and both used to
 * be inline in MainActivity, where a JVM test cannot reach them:
 *
 *  - [enqueue]: alerts at the head, normal messages at the tail, under one monitor, so an alert
 *    can never land in the middle of another thread's head surgery.
 *  - [yieldBehindAlerts]: while the worker is loading a voice or synthesising — seconds of work —
 *    an alert has nothing playing to interrupt. Without this, the worker would play the message
 *    it was synthesising IN FULL first, pushing the alert behind it.
 *
 * The queue itself makes the mid-synthesis case detectable with no extra signalling: alerts are
 * enqueued at the head and the worker is the only consumer, so an alert found ahead of the worker
 * right after a synthesis must have arrived DURING that synthesis. Nothing of the in-flight
 * message has been heard yet, so it can step aside: it re-queues directly behind the alerts and
 * ahead of every later normal message, preserving arrival order for ordinary traffic.
 */
object SpeechQueueOrder {

    /** Add [msg]: alerts jump the head, everything else keeps arrival order at the tail. */
    fun enqueue(queue: LinkedBlockingDeque<Message>, msg: Message) {
        synchronized(queue) {
            if (msg.type == Message.Type.ALERT) queue.offerFirst(msg) else queue.offer(msg)
        }
    }

    /**
     * Re-queue [msg] behind the leading run of alerts, if one is waiting.
     *
     * Returns true when [msg] was re-queued — the caller must NOT play it now, and must not mark
     * it as played; the worker will pick it up again after the alerts. Returns false when nothing
     * was waiting, or when [msg] is itself an alert (alerts do not yield to other alerts: playing
     * it immediately is strictly better than re-synthesising it behind a newer one), leaving the
     * queue untouched.
     */
    fun yieldBehindAlerts(queue: LinkedBlockingDeque<Message>, msg: Message): Boolean {
        if (msg.type == Message.Type.ALERT) return false
        synchronized(queue) {
            if (queue.peekFirst()?.type != Message.Type.ALERT) return false
            val ahead = ArrayList<Message>()
            while (queue.peekFirst()?.type == Message.Type.ALERT) {
                ahead.add(queue.pollFirst()!!)
            }
            queue.offerFirst(msg)
            for (i in ahead.indices.reversed()) queue.offerFirst(ahead[i])
            return true
        }
    }
}
