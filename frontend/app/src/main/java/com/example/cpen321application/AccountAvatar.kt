package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
    var showSources by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var capturePath by rememberSaveable(session.account.id) { mutableStateOf<String?>(null) }
    val account = session.account
    val bitmap by produceState<Bitmap?>(null, account.avatarDataUrl) {
        value = withContext(Dispatchers.Default) { decodeAvatar(account.avatarDataUrl) }
    }

    fun upload(uri: Uri, capture: File? = null) {
        if (uploading) return
        uploading = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val dataUrl = prepareAvatar(context, uri)
                    AccountApi.updateAccount(session.token, JSONObject().put("avatarDataUrl", dataUrl))
                }
                onUpdated(updated)
                onNotice(t("Profile photo updated."))
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) { if (e.status == 401) onSessionExpired() else onNotice(e.message ?: "Could not update your photo.") }
            catch (e: IOException) { onNotice(e.message ?: "Could not read this photo. Choose another image.") }
            catch (_: SecurityException) { onNotice("Permission to read this photo was denied. Choose it again.") }
            catch (e: IllegalArgumentException) { onNotice(t(e.message ?: "Choose a supported image.")) }
            catch (e: IllegalStateException) { onNotice(e.message ?: "Could not open this photo.") }
            catch (_: org.json.JSONException) { onNotice("The server returned an unreadable response. Try again.") }
            finally { capture?.delete(); capturePath = null; uploading = false }
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) upload(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = capturePath?.let(::File)
        if (success && file != null) upload(Uri.fromFile(file), file)
        else { file?.delete(); capturePath = null }
    }

    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = enabled && !uploading, role = Role.Button, onClickLabel = "Change profile photo") { showSources = true }
                    .semantics { contentDescription = "Change profile photo" }.testTag("account-avatar"), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else AvatarInitial(account.displayName)
                    if (uploading) CircularProgressIndicator(Modifier.size(32.dp))
                }
                BarButton(onClick = { showSources = true }, enabled = enabled && !uploading, modifier = Modifier.padding(top = 6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(t("Change photo"), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                Text(account.email, style = MaterialTheme.typography.bodyMedium)
            }
            BarButton(onClick = onEdit, enabled = enabled && !uploading, modifier = Modifier.testTag("edit-account")) { Text(t("Edit")) }
        }
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text(t("Profile photo")) }, text = {
        Column {
            BarButton(onClick = {
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
            }) { Text(t("Take a picture")) }
            BarButton(onClick = {
                showSources = false
                try { library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                catch (_: Exception) { onNotice("Could not open the photo library. Try again.") }
            }) { Text(t("Choose from a library")) }
        }
    }, confirmButton = { BarButton(onClick = { showSources = false }) { Text(t("Cancel")) } })
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

internal fun prepareAvatar(context: Context, uri: Uri): String {
    // Bounded decode, orientation correction, and JPEG re-encoding strip original photo metadata.
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
    } ?: error("Could not open this photo.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Could not decode this photo.")
    val orientation = bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val side = minOf(rotated.width, rotated.height)
    val square = Bitmap.createBitmap(rotated, (rotated.width - side) / 2, (rotated.height - side) / 2, side, side)
    val avatar = Bitmap.createScaledBitmap(square, minOf(side, 384), minOf(side, 384), true)
    val output = ByteArrayOutputStream()
    avatar.compress(Bitmap.CompressFormat.JPEG, 75, output)
    val encoded = "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    listOf(decoded, rotated, square, avatar).distinct().forEach { it.recycle() }
    require(encoded.length <= 180000) { "Choose a less detailed photo." }
    return encoded
}
