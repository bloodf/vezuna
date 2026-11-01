package app.vezuna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

class ThirdPartyNoticesActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notices = runCatching {
            assets.open("ThirdPartyNotices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { nativeMessage(NativeKey.THIRD_PARTY_NOTICES_UNAVAILABLE).resolve(this) }
        setContent {
            MaterialTheme {
                val paragraphs = remember(notices) {
                    notices.replace("\r\n", "\n").split("\n\n").flatMap { it.chunked(8192) }
                }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(nativeMessage(NativeKey.THIRD_PARTY_NOTICES).resolve(this@ThirdPartyNoticesActivity)) },
                        actions = {
                            TextButton(onClick = ::finish) {
                                Text(nativeMessage(NativeKey.CLOSE).resolve(this@ThirdPartyNoticesActivity))
                            }
                        },
                    )
                }) { padding ->
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(paragraphs) { paragraph ->
                                Text(paragraph, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
