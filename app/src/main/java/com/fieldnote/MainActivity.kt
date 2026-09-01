package com.fieldnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.fieldnote.ui.navigation.FieldNoteApp
import com.fieldnote.ui.theme.FieldNoteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FieldNoteTheme {
                FieldNoteApp(viewModel = viewModel)
            }
        }
    }
}
