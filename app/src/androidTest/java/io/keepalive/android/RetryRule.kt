package io.keepalive.android

import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit rule that re-runs a failing test up to [attempts] times, passing as soon
 * as one attempt succeeds. Intended for tests that are correct but
 * environment-flaky on a headless CI emulator — e.g. [OverlayInstrumentedTest],
 * where the overlay reliably renders ("Overlay shown" logs) but UiAutomator's
 * accessibility tree intermittently omits the overlay window. A genuine
 * regression still fails every attempt, so this masks flakiness, not breakage.
 *
 * `base.evaluate()` runs the whole method statement (including @Before/@After),
 * so each attempt gets a fresh setUp/tearDown.
 */
class RetryRule(
    private val attempts: Int = 3,
    private val delayMsBetween: Long = 1500L,
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                var last: Throwable? = null
                for (attempt in 1..attempts) {
                    try {
                        base.evaluate()
                        return
                    } catch (t: Throwable) {
                        last = t
                        Log.w(
                            "RetryRule",
                            "${description.displayName} failed (attempt $attempt/$attempts)",
                            t
                        )
                        if (attempt < attempts) Thread.sleep(delayMsBetween)
                    }
                }
                throw last ?: AssertionError("RetryRule: no attempts run")
            }
        }
    }
}
