package eu.kanade.tachiyomi.extension.pt.mugiwarasoficial

import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source

@Source
abstract class MugiwarasOficial : Aurora() {

    // The reading gate renders this widget when it answers `captcha_required` (issue #180).
    override val captchaSiteKey = "0x4AAAAAAGJWplaQoHofaEt0"

    override val captchaAction = "gate"
}
