package app.vezuna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PermissionsRationaleActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val policy = runCatching {
            assets.open("PrivacyPolicy.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { nativeMessage(NativeKey.PRIVACY_POLICY_UNAVAILABLE).resolve(this) }
        val paragraphs = policy.trim().split("\n\n")
        setContent {
            MaterialTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(nativeMessage(NativeKey.PRIVACY_POLICY).resolve(this@PermissionsRationaleActivity)) },
                        actions = {
                            TextButton(onClick = ::finish) {
                                Text(nativeMessage(NativeKey.CLOSE).resolve(this@PermissionsRationaleActivity))
                            }
                        },
                    )
                }) { padding ->
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(paragraphs) { index, paragraph ->
                                Text(paragraph, style = when {
                                    index == 0 -> MaterialTheme.typography.titleLarge
                                    index > 1 && paragraph.length < 65 -> MaterialTheme.typography.titleMedium
                                    else -> MaterialTheme.typography.bodyMedium
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
