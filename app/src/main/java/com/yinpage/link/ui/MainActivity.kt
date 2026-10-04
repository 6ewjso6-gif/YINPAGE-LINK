package com.yinpage.link.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yinpage.link.R
import com.yinpage.link.core.AppState
import com.yinpage.link.ui.theme.YinpageLinkTheme

/**
 * 单 Activity 入口。
 *
 * 只做三件事：
 *  1. 开启 edge-to-edge（Compose 自己处理系统栏）；
 *  2. 套上 Material3 主题；
 *  3. 在 UI 之前套一层蓝牙运行时权限关卡，权限没给之前用悬浮卡片提示。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YinpageLinkTheme {
                BluetoothPermissionGate { granted ->
                    App(permissionGranted = granted)
                }
            }
        }
    }
}

/**
 * 蓝牙运行时权限关卡。
 *
 * - Android 12（API 31）及以上：BLUETOOTH_SCAN + BLUETOOTH_CONNECT（「附近的设备」）；
 * - Android 11 及以下：经典蓝牙发现需要 ACCESS_FINE_LOCATION。
 *
 * 首次进入自动弹一次系统权限框；被拒绝后卡片常驻，点「授予权限」可再次拉起。
 * content 会收到「当前是否已授权」，UI 据此展示设备页的引导文案。
 */
@Composable
fun BluetoothPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val required = remember { requiredBluetoothPermissions() }
    val notifications = remember { requiredNotificationPermissions() }

    var granted by remember { mutableStateOf(hasAllPermissions(context, required)) }
    // 蓝牙权限的结果单独记录：通知权限的申请要等它落定，避免两个系统弹窗互相打断
    var bluetoothSettled by remember { mutableStateOf(granted) }
    var asked by rememberSaveable { mutableStateOf(false) }

    // 从「设置」里改完权限回到 App 时，重新读取一次授权状态，避免 UI 与实际不符（P1-13）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasAllPermissions(context, required)
                AppState.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = required.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        bluetoothSettled = true
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 通知权限被拒不影响蓝牙功能，只是收不到通知栏电量卡片 */ }

    // 首次进入：先要蓝牙权限（核心），拿到结果后再要通知权限（附加）
    LaunchedEffect(Unit) {
        if (!granted && !asked && required.isNotEmpty()) {
            asked = true
            bluetoothLauncher.launch(required)
        } else {
            bluetoothSettled = true
        }
    }

    LaunchedEffect(bluetoothSettled) {
        if (bluetoothSettled && notifications.isNotEmpty() &&
            !hasAllPermissions(context, notifications)
        ) {
            notificationLauncher.launch(notifications)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content(granted)

        if (!granted) {
            PermissionCard(
                onRequest = { if (required.isNotEmpty()) bluetoothLauncher.launch(required) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.perm_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    stringResource(R.string.perm_desc_android12)
                } else {
                    stringResource(R.string.perm_desc_legacy)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.perm_denied_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = onRequest) {
                Text(text = stringResource(R.string.perm_grant))
            }
        }
    }
}

/** 当前系统版本下需要申请的蓝牙相关权限。 */
private fun requiredBluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean =
    permissions.isNotEmpty() && permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 通知权限（Android 13+ 才需要）。
 * 用于通知栏的耳机电量卡片 —— 不给也能用 App，只是没有卡片，所以单独申请。
 */
private fun requiredNotificationPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }
