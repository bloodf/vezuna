package app.vezuna

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals

@OptIn(ExperimentalEncodingApi::class)
class GoldenFixtureTest {
    @Test
    fun fixtureFileMatchesTheEmbeddedVector() {
        val file = javaClass.getResourceAsStream("/golden-v1.vhbak")!!.use { it.readBytes() }
        assertContentEquals(Base64.decode(GoldenBackup.FILE_BASE64), file)
    }
}
