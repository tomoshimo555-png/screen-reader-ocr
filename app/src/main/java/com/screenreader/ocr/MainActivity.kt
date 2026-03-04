package com.screenreader.ocr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.screenreader.ocr.data.SessionRepository
import com.screenreader.ocr.data.SessionFolder
import com.screenreader.ocr.service.FloatingOverlayService
import com.screenreader.ocr.service.ScreenCaptureService
import com.screenreader.ocr.ui.screens.HistoryScreen
import com.screenreader.ocr.ui.screens.HomeScreen
import com.screenreader.ocr.ui.screens.SettingsScreen
import com.screenreader.ocr.ui.theme.ScreenReaderOCRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var isCapturing by mutableStateOf(false)
    private var captureCount by mutableIntStateOf(0)
    private var skipCount by mutableIntStateOf(0)
    private var statusText by mutableStateOf("")
    private var captureIntervalSeconds by mutableIntStateOf(5)
    private var similarityThreshold by mutableFloatStateOf(0.80f)
    private var geminiApiKey by mutableStateOf("")
    private var captureMode by mutableStateOf("text")

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "画面キャプチャの許可が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "オーバーレイ表示の許可が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                statusText = it.getStringExtra(ScreenCaptureService.EXTRA_STATUS_TEXT) ?: ""
                captureCount = it.getIntExtra(ScreenCaptureService.EXTRA_CAPTURE_COUNT, 0)
                skipCount = it.getIntExtra(ScreenCaptureService.EXTRA_SKIP_COUNT, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Load settings from SharedPreferences
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        captureIntervalSeconds = prefs.getInt("capture_interval", 5)
        similarityThreshold = prefs.getFloat("similarity_threshold", 0.80f)
        geminiApiKey = prefs.getString("gemini_api_key", "") ?: ""

        // Register broadcast receiver for capture status updates
        val filter = IntentFilter(ScreenCaptureService.BROADCAST_STATUS)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        setContent {
            ScreenReaderOCRTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: "home"

        val sessionRepo = remember { SessionRepository(this@MainActivity) }
        var sessions by remember { mutableStateOf<List<SessionFolder>>(emptyList()) }
        val scope = rememberCoroutineScope()

        // Load sessions when screen is shown
        LaunchedEffect(Unit) {
            sessions = withContext(Dispatchers.IO) { sessionRepo.getAllSessions() }
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("ホーム") },
                        selected = currentRoute == "home",
                        onClick = {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = {
                            BadgedBox(
                                badge = {
                                    val totalFiles = sessions.sumOf { it.textFiles.size }
                                    if (totalFiles > 0) {
                                        Badge { Text("$totalFiles") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.History, contentDescription = null)
                            }
                        },
                        label = { Text("履歴") },
                        selected = currentRoute == "history",
                        onClick = {
                            navController.navigate("history") {
                                popUpTo("home")
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("設定") },
                        selected = currentRoute == "settings",
                        onClick = {
                            navController.navigate("settings") {
                                popUpTo("home")
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("home") {
                    HomeScreen(
                        isCapturing = isCapturing,
                        captureCount = captureCount,
                        skipCount = skipCount,
                        statusText = statusText,
                        captureIntervalSeconds = captureIntervalSeconds,
                        captureMode = captureMode,
                        onCaptureIntervalChange = { captureIntervalSeconds = it },
                        onCaptureModeChange = { captureMode = it },
                        onStartCapture = { startCapture() },
                        onStopCapture = { stopCapture() },
                        onOpenSaved = {
                            if (captureMode == "screenshot") {
                                openScreenshotFolder()
                            } else {
                                navController.navigate("history") {
                                    popUpTo("home")
                                }
                            }
                        }
                    )
                }
                composable("history") {
                    // Reload sessions when entering history screen
                    LaunchedEffect(Unit) {
                        sessions = withContext(Dispatchers.IO) { sessionRepo.getAllSessions() }
                    }
                    HistoryScreen(
                        sessions = sessions,
                        onDeleteSession = { sessionId ->
                            scope.launch {
                                withContext(Dispatchers.IO) { sessionRepo.deleteSession(sessionId) }
                                sessions = withContext(Dispatchers.IO) { sessionRepo.getAllSessions() }
                            }
                        },
                        onDeleteAll = {
                            scope.launch {
                                withContext(Dispatchers.IO) { sessionRepo.deleteAllSessions() }
                                sessions = withContext(Dispatchers.IO) { sessionRepo.getAllSessions() }
                            }
                        },
                        onShareText = { text -> shareText(text) }
                    )
                }
                composable("settings") {
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    SettingsScreen(
                        captureIntervalSeconds = captureIntervalSeconds,
                        similarityThreshold = similarityThreshold,
                        geminiApiKey = geminiApiKey,
                        onCaptureIntervalChange = { 
                            captureIntervalSeconds = it
                            prefs.edit().putInt("capture_interval", it).apply()
                        },
                        onSimilarityThresholdChange = { 
                            similarityThreshold = it 
                            prefs.edit().putFloat("similarity_threshold", it).apply()
                        },
                        onGeminiApiKeyChange = {
                            geminiApiKey = it
                            prefs.edit().putString("gemini_api_key", it).apply()
                        }
                    )
                }
            }
        }
    }

    private fun startCapture() {
        if (captureMode == "text" && geminiApiKey.isBlank()) {
            Toast.makeText(this, "まず設定画面でGemini APIキーを入力してください", Toast.LENGTH_LONG).show()
            return
        }

        // Step 1: Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Step 2: Request media projection
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val sessionRepo = SessionRepository(this)
        val sessionId = sessionRepo.generateSessionId()

        // Start capture service
        val captureIntent = ScreenCaptureService.createStartIntent(
            this,
            resultCode,
            resultData,
            interval = captureIntervalSeconds * 1000L,
            sessionId = sessionId,
            captureMode = captureMode,
            apiKey = geminiApiKey
        )
        startForegroundService(captureIntent)

        // Start overlay service
        val overlayIntent = FloatingOverlayService.createShowIntent(this)
        startService(overlayIntent)

        isCapturing = true
        captureCount = 0
        skipCount = 0
        statusText = "キャプチャ開始..."
    }

    private fun stopCapture() {
        val captureStopIntent = ScreenCaptureService.createStopIntent(this)
        startService(captureStopIntent)

        val overlayHideIntent = FloatingOverlayService.createHideIntent(this)
        startService(overlayHideIntent)

        isCapturing = false
        statusText = ""
    }

    private fun shareText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, "テキストを共有"))
    }

    private fun openScreenshotFolder() {
        try {
            // Try to open the Pictures/ScreenReaderOCR folder in gallery
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open file manager
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    data = uri
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Pictures/ScreenReaderOCR フォルダを確認してください", Toast.LENGTH_LONG).show()
            }
        }
    }
}
