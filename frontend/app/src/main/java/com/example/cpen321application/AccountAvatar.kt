package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@Composable
internal fun AccountProfileCard(session: Session, enabled: Boolean, onEdit: () -> Unit, onUpdated: (Account) -> Unit,
    onSessionExpired: () -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropUri by rememberSaveable(session.account.id) { mutableStateOf<String?>(null) }
    var successDialog by rememberSaveable { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var capturePath by rememberSaveable(session.account.id) { mutableStateOf<String?>(null) }
    val account = session.account
    val bitmap by produceState<Bitmap?>(null, account.avatarDataUrl) {
        value = withContext(Dispatchers.Default) { decodeAvatar(account.avatarDataUrl) }
    }

    fun upload(dataUrl: String) {
        if (uploading) return
        uploading = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    AccountApi.updateAccount(session.token, JSONObject().put("avatarDataUrl", dataUrl))
                }
                onUpdated(updated)
                successDialog = true
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) { if (e.status == 401) onSessionExpired() else onNotice(e.message ?: "Could not update your photo.") }
            catch (e: IOException) { onNotice(e.message ?: "Could not read this photo. Choose another image.") }
            catch (_: SecurityException) { onNotice("Permission to read this photo was denied. Choose it again.") }
            catch (e: IllegalArgumentException) { onNotice(t(e.message ?: "Choose a supported image.")) }
            catch (e: IllegalStateException) { onNotice(e.message ?: "Could not open this photo.") }
            catch (_: org.json.JSONException) { onNotice("The server returned an unreadable response. Try again.") }
            finally { capturePath?.let { File(it).delete() }; capturePath = null; uploading = false }
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropUri = uri.toString()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = capturePath?.let(::File)
        if (success && file != null) cropUri = Uri.fromFile(file).toString()
        else { file?.delete(); capturePath = null }
    }

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AvatarButton(account.displayName, bitmap, uploading, enabled = enabled && !uploading) { showSources = true }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                Text(account.email, style = MaterialTheme.typography.bodyMedium)
            }
            BarButton(onClick = onEdit, enabled = enabled && !uploading, modifier = Modifier.testTag("edit-account")) { Text(t("Edit")) }
        }
    }
    if (successDialog) AlertDialog(onDismissRequest = { successDialog = false }, text = { Text(t("Profile photo updated.")) },
        confirmButton = { BarButton(onClick = { successDialog = false }) { Text(t("OK")) } })
    cropUri?.let { selected -> AvatarCropDialog(Uri.parse(selected), onCancel = {
        cropUri = null; capturePath?.let { File(it).delete() }; capturePath = null
    }, onConfirm = { data -> cropUri = null; upload(data) }) }
    fun takePicture() {
        showSources = false
        try {
            val dir = File(context.cacheDir, "profile-captures").apply { mkdirs() }
            val file = File.createTempFile("avatar-", ".jpg", dir)
            capturePath = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.ledger.files", file))
        } catch (_: Exception) {
            capturePath?.let { File(it).delete() }; capturePath = null
            onNotice(t("No camera is available. Choose a photo from your library."))
        }
    }
    fun chooseFromLibrary() {
        showSources = false
        try { library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { onNotice("Could not open the photo library. Try again.") }
    }
    if (showSources) PhotoSourceDialog(onTakePicture = ::takePicture, onChooseLibrary = ::chooseFromLibrary) { showSources = false }
}

@Composable
private fun AvatarButton(name: String, bitmap: Bitmap?, uploading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = t("Change profile photo"), onClick = onClick)
        .semantics { contentDescription = t("Change profile photo") }.testTag("account-avatar")) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AvatarInitial(name)
            if (uploading) CircularProgressIndicator(Modifier.size(32.dp))
        }
        Surface(Modifier.align(Alignment.TopEnd).size(24.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_camera), contentDescription = null, modifier = Modifier.padding(3.dp))
        }
    }
}

@Composable
private fun PhotoSourceDialog(onTakePicture: () -> Unit, onChooseLibrary: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Profile photo")) }, text = {
        Column {
            BarButton(onClick = onTakePicture) { Text(t("Take a picture")) }
            BarButton(onClick = onChooseLibrary) { Text(t("Choose from a library")) }
        }
    }, confirmButton = { BarButton(onClick = onDismiss) { Text(t("Cancel")) } })
}

@Composable
private fun AvatarInitial(name: String) {
    Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer)
}

private fun decodeAvatar(value: String): Bitmap? = try {
    if (value.isBlank() || value.length > 180000) null
    else Base64.decode(value.substringAfter(","), Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
} catch (_: IllegalArgumentException) { null }

internal fun readAvatarBitmap(context: Context, uri: Uri): Bitmap {
    // Bound the source size and decoded dimensions before cropping.
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count != -1) {
            require(output.size() + count <= 20 * 1024 * 1024) { "Choose a photo smaller than 20 MB." }
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        output.toByteArray()
    } ?: throw IOException("Could not open this photo.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("Could not decode this photo.")
}

internal fun prepareAvatar(context: Context, uri: Uri): String {
    val bitmap = readAvatarBitmap(context, uri)
    return try { encodeAvatar(bitmap, bitmap.width / 2f, bitmap.height / 2f, minOf(bitmap.width, bitmap.height)) }
        finally { bitmap.recycle() }
}

private fun encodeAvatar(bitmap: Bitmap, centerX: Float, centerY: Float, side: Int): String {
    val square = Bitmap.createBitmap(bitmap, (centerX - side / 2f).toInt().coerceIn(0, bitmap.width - side),
        (centerY - side / 2f).toInt().coerceIn(0, bitmap.height - side), side, side)
    val avatar = Bitmap.createScaledBitmap(square, 384, 384, true)
    try {
        for (quality in listOf(85, 75, 60, 45)) {
            val output = ByteArrayOutputStream()
            if (!avatar.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw IOException("Could not read this photo. Choose another image.")
            }
            val encoded = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            if (encoded.length <= 180000) return encoded
        }
        throw IllegalArgumentException("Choose a less detailed photo.")
    } finally {
        if (avatar !== bitmap) avatar.recycle()
        if (square !== bitmap && square !== avatar) square.recycle()
    }
}

@Composable
internal fun AvatarCropDialog(uri: Uri, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, uri) {
        try { value = withContext(Dispatchers.IO) { readAvatarBitmap(context, uri) } }
        catch (e: CancellationException) { throw e }
        catch (e: IOException) { error = e.message ?: "Could not read this photo. Choose another image." }
        catch (_: SecurityException) { error = "Permission to read this photo was denied. Choose it again." }
        catch (e: IllegalArgumentException) { error = e.message ?: "Choose a supported image." }
    }
    DisposableEffect(bitmap) { val loaded = bitmap; onDispose { loaded?.recycle() } }
    AlertDialog(onDismissRequest = onCancel, title = { Text(t("Crop profile photo")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("Drag to move. Pinch to zoom."))
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (bitmap == null && error == null) CircularProgressIndicator()
            bitmap?.let { source -> AvatarCropCanvas(source, uri.toString(), onConfirm, onError = { error = it }) }
        }
    }, confirmButton = {}, dismissButton = { BarButton(onClick = onCancel) { Text(t("Cancel")) } })
}

@Composable
private fun AvatarCropCanvas(bitmap: Bitmap, key: String, onConfirm: (String) -> Unit, onError: (String) -> Unit) {
    var zoom by rememberSaveable(key) { mutableFloatStateOf(1f) }
    var centerX by rememberSaveable(key) { mutableFloatStateOf(bitmap.width / 2f) }
    var centerY by rememberSaveable(key) { mutableFloatStateOf(bitmap.height / 2f) }
    val minimum = minOf(bitmap.width, bitmap.height).toFloat()
    val side = (minimum / zoom).toInt().coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("avatar-crop").pointerInput(bitmap) {
        detectTransformGestures { _, pan, scale, _ ->
            zoom = (zoom * scale).coerceIn(1f, 8f)
            val half = minimum / zoom / 2
            centerX = (centerX - pan.x / size.width * half * 2).coerceIn(half, bitmap.width - half)
            centerY = (centerY - pan.y / size.height * half * 2).coerceIn(half, bitmap.height - half)
        }
    }) {
        drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset((centerX - side / 2f).toInt().coerceIn(0, bitmap.width - side),
            (centerY - side / 2f).toInt().coerceIn(0, bitmap.height - side)), srcSize = IntSize(side, side),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        val mask = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)); addOval(Rect(Offset.Zero, size)) }
        drawPath(mask, Color.Black.copy(alpha = 0.55f))
        drawCircle(Color.White, radius = size.width / 2 - 2, style = Stroke(3f))
    }
    PrimaryBarButton(onClick = {
        val encoded = try { encodeAvatar(bitmap, centerX, centerY, side) }
        catch (e: IOException) {
            onError(e.message ?: "Could not read this photo. Choose another image.")
            null
        }
        catch (e: IllegalArgumentException) {
            onError(e.message ?: "Choose a supported image.")
            null
        }
        if (encoded != null) onConfirm(encoded)
    }, modifier = Modifier.fillMaxWidth()) { Text(t("Use photo")) }
}
