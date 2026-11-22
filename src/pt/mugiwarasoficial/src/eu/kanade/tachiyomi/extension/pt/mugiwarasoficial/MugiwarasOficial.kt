package eu.kanade.tachiyomi.extension.pt.mugiwarasoficial

import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source

@Source
abstract class MugiwarasOficial : Aurora() {

    // The reading gate renders this widget when it answers `captcha_required` (issue #180).
    override val captchaSiteKey = "0x4AAAAAAGJWplaQoHofaEt0"

    override val captchaAction = "gate"

    // Measured with the site's own reader: `gate/start` declares `minWaitSeconds` 0 (or omits it) while the
    // callback answers `too_fast` until ~9s after issue. Start at 5s and let the escalation find the real floor.
    override val gateFallbackSeconds = 5

    override val gateTrustsPartnerZero = false
}
