package com.cold04.picomanage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import com.cold04.picomanage.ui.theme.PicoManageTheme

class UploadDirectoryActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DIRECTORY = "upload_directory"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                UploadDirectoryPicker(
                    onCancel = ::finish,
                    onChoose = { directory ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DIRECTORY, directory))
                        finish()
                    },
                )
            }
        }
    }
}
