package dev.snaipdefix.opencflink

/**
 * Process-global handle to the bike PXC client ([EasyConnProber]).
 *
 * held here, not only as a [MainActivity] field, so the Android Auto → bike hand-off and the
 * stop control survive a [MainActivity] recreation. Triggering Google Android Auto (self-mode)
 * brings Gearhead to the foreground, which can destroy and recreate [MainActivity] while the AA
 * receiver keeps running in [AndroidAutoService]. The bike connection must not be torn down or
 * orphaned when that happens: a fresh activity re-reads the SAME prober instance from here instead
 * of constructing a new one that would leave the running one leaked and unstoppable.
 *
 * Matches the existing process-global style ([AaVideoBridge], [ProjectionHolder], [BikeProfileHolder]).
 */
object BikeLink {
    @Volatile var prober: EasyConnProber? = null
}
