package com.banoon.vse.presentation.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.banoon.vse.domain.model.MediaInfo
import com.banoon.vse.domain.model.PickedVideoFile
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VideoPickerScreen()
                }
            }
        }
    }
}

@Composable
private fun VideoPickerScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onVideoUriPicked(it.toString()) }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.exportToGallery()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "محرر مقاطع الفيديو", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                pickVideoLauncher.launch(
                    arrayOf("video/mp4", "video/x-matroska", "video/quicktime", "video/*")
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading && !uiState.isProcessing
        ) {
            Text(text = "اختر فيديو")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isLoading) {
            CircularProgressIndicator()
        }

        uiState.errorMessage?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        uiState.mediaInfo?.let { info ->
            VideoSummary(uiState.pickedVideo, info)
            Spacer(modifier = Modifier.height(24.dp))
            ModeSelector(uiState.operationMode, viewModel::setOperationMode)
            Spacer(modifier = Modifier.height(16.dp))
            RangeEditor(uiState.ranges, viewModel)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.startProcessing() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isProcessing
            ) {
                Text(text = "ابدأ المعالجة")
            }

            if (uiState.isProcessing) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { uiState.processingProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(text = "${(uiState.processingProgress * 100).toInt()}%")
            }

            uiState.resultPaths?.let { paths ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "تمت المعالجة بنجاح ✅", style = MaterialTheme.typography.titleMedium)
                paths.forEach { path ->
                    Text(text = path, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.exportedUris == null) {
                    Button(
                        onClick = { viewModel.exportToGallery() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isExporting
                    ) {
                        Text(text = if (uiState.isExporting) "جارٍ الحفظ..." else "حفظ في المعرض")
                    }
                }

                if (uiState.needsLegacyPermission) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "هذا الإصدار من أندرويد يحتاج صلاحية تخزين لحفظ الفيديو بالمعرض",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            legacyPermissionLauncher.launch(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "منح صلاحية التخزين")
                    }
                }

                uiState.exportedUris?.let { uris ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "تم الحفظ بالمعرض ✅", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "video/*"
                                putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM,
                                    ArrayList(uris.map { Uri.parse(it) })
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "مشاركة الفيديو"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "مشاركة")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(current: OperationMode, onSelect: (OperationMode) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "نوع العملية", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current == OperationMode.REMOVE,
                onClick = { onSelect(OperationMode.REMOVE) },
                label = { Text("حذف المقاطع") }
            )
            FilterChip(
                selected = current == OperationMode.EXTRACT_MERGED,
                onClick = { onSelect(OperationMode.EXTRACT_MERGED) },
                label = { Text("استخراج (دمج)") }
            )
            FilterChip(
                selected = current == OperationMode.EXTRACT_SEPARATE,
                onClick = { onSelect(OperationMode.EXTRACT_SEPARATE) },
                label = { Text("استخراج (منفصل)") }
            )
        }
    }
}

@Composable
private fun RangeEditor(ranges: List<TimeRangeInput>, viewModel: MainViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "المدى الزمني", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "اكتب ثواني (300) أو د:ث (5:00) أو س:د:ث (1:05:00)",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        ranges.forEach { range ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = range.startSecondsText,
                    onValueChange = { viewModel.updateRangeStart(range.id, it) },
                    label = { Text("البداية") },
                    placeholder = { Text("5:00") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = range.endSecondsText,
                    onValueChange = { viewModel.updateRangeEnd(range.id, it) },
                    label = { Text("النهاية") },
                    placeholder = { Text("6:00") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.removeRange(range.id) }) {
                    Text(text = "✕")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(onClick = { viewModel.addRange() }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "+ أضف مدى زمني")
        }
    }
}

@Composable
private fun VideoSummary(video: PickedVideoFile?, info: MediaInfo?) {
    if (video == null || info == null) return
    val durationSeconds = info.durationUs.value / 1_000_000.0
    val videoStream = info.primaryVideoStream

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = video.displayName, style = MaterialTheme.typography.titleMedium)
        Text(text = "الحاوية: ${info.containerFormat}")
        Text(text = "المدة: ${"%.1f".format(durationSeconds)} ثانية")
        if (videoStream != null) {
            Text(text = "الدقة: ${videoStream.width}×${videoStream.height}")
            Text(text = "الكودك: ${videoStream.codec}")
            Text(text = "معدل الإطارات: ${"%.2f".format(videoStream.frameRate)} fps")
            Text(text = if (videoStream.isHdr) "HDR: نعم" else "HDR: لا")
            Text(text = "نقاط keyframe المكتشفة: ${videoStream.keyframes.size}")
            videoStream.keyframeExtractionError?.let { err ->
                Text(
                    text = "خطأ استخراج keyframe: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Text(text = "مسارات الصوت: ${info.audioStreams.size}")
        Text(text = "مسارات الترجمة: ${info.subtitleStreams.size}")
    }
}
