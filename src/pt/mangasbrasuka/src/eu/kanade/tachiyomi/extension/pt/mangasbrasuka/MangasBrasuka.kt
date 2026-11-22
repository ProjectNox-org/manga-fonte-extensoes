package eu.kanade.tachiyomi.extension.pt.mangasbrasuka

import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source

@Source
abstract class MangasBrasuka : Aurora() {

    // Sample of this site's own gate screen: the widget is rendered with this key and action "gate".
    override val captchaSiteKey = "0x4AAAAAAGJWjt4GW9mm0m-J"

    override val captchaAction = "gate"

    // Measured on this site: `gate/start` declares `minWaitSeconds` 0 (partner gate) or 8 (link gate) and the
    // callback answers `too_fast` until ~7.6s after issue, so the partner zero cannot be trusted. 8s matches the
    // floor and the declared value; the escalation still learns a higher floor if the server raises it.
    override val gateFallbackSeconds = 8

    override val gateTrustsPartnerZero = false
}
