package com.vzor.ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vzor.ai.ui.navigation.VzorNavGraph
import com.vzor.ai.ui.theme.VzorTheme
import com.vzor.ai.vision.SharedImageHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var sharedImageHandler: SharedImageHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            VzorTheme {
                VzorNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (imageUri != null) {
                Log.d(TAG, "Received shared image: $imageUri")
                sharedImageHandler.handleSharedImage(imageUri, contentResolver)
            }
        }
    }
}
