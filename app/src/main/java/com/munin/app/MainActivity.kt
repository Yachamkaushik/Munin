package com.munin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.munin.app.ui.IndexScreen
import com.munin.app.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableStateOf(0) }
                    var indexVersion by rememberSaveable { mutableIntStateOf(0) }
                    Scaffold(bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0; indexVersion++ }, icon = {}, label = { Text("Search") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Index") })
                        }
                    }) { padding ->
                        Surface(Modifier.padding(padding)) {
                            if (tab == 0) SearchScreen(indexVersion = indexVersion) else IndexScreen()
                        }
                    }
                }
            }
        }
    }
}
