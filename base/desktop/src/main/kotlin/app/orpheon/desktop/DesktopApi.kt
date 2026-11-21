// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop

import app.myspoti.api.MyspotiApi
import app.myspoti.api.NeutralApi
import app.myspoti.api.Player
import app.myspoti.catalog.DEFAULT_ORDER
import app.myspoti.catalog.MetadataApi
import app.myspoti.catalog.ProviderChain
import app.myspoti.catalog.accountlessProviders
import app.myspoti.catalog.chainOf
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * The same API the phone's UI talks to, with the desktop's player: searches, albums and artists from
 * the metadata providers of `:catalog` (Deezer, iTunes, MusicBrainz...), no account anywhere, and
 * [player] playing the audio the user's extensions give.
 */
class DesktopApi private constructor(metadata: MyspotiApi, override val player: Player) : MyspotiApi by metadata {
    companion object {
        /** The metadata half, which the player needs (its catalog) before the player itself exists. */
        fun metadata(http: OkHttpClient = OkHttpClient()): MyspotiApi {
            val country = Locale.getDefault().country.ifBlank { "US" }
            val providers = accountlessProviders(http, "Orpheon-Desktop/1.0 (https://github.com/kota-zo/Orpheon)", country)
            val chain = ProviderChain(chainOf(providers, DEFAULT_ORDER))
            return MetadataApi(NeutralApi(), { chain })
        }

        fun of(metadata: MyspotiApi, player: Player): MyspotiApi = DesktopApi(metadata, player)
    }
}
