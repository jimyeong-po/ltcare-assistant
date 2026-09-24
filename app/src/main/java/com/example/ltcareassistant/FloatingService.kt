package com.example.ltcareassistant

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""

    // 백엔드 API 주소 (실제 서버 IP/도메인으로 변경)
    private val SERVER_URL = "http://your-server-ip:8000/analyze-recording"
    private val okHttpClient = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        btnRecord = floatingView.findViewById(R.id.btnRecord)
        tvStatus = floatingView.findViewById(R.id.tvStatus)
        val ivDragHandle = floatingView.findViewById(R.id.ivDragHandle)

        // 윈도우 파라미터 구성
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        windowManager.addView(floatingView, params)

        // 제스처로 화면 드래그 이동
        setupDragTouch(ivDragHandle, params)

        // 녹음 버튼 클릭 리스너
        btnRecord.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecordingAndUpload()
            }
        }
    }

    private fun startRecording() {
        try {
            audioFilePath = "${externalCacheDir?.absolutePath}/temp_counsel.m4a"
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            isRecording = true
            btnRecord.text = "종료 및 분석"
            btnRecord.setBackgroundColor(0xFFD32F2F.toInt()) // 붉은색
            tvStatus.text = "녹음 중..."
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "녹음 시작 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndUpload() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            btnRecord.text = "분석 중..."
            btnRecord.isEnabled = false
            tvStatus.text = "AI 정리 중..."

            // 서버로 전송
            uploadAudioFile(File(audioFilePath))

        } catch (e: Exception) {
            e.printStackTrace()
            resetUi()
        }
    }

    private fun uploadAudioFile(file: File) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("audio/mp4".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(SERVER_URL)
                    .post(requestBody)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseData = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && responseData != null) {
                        val json = JSONObject(responseData)
                        val summaryText = json.getString("result_text")

                        // 1. 클립보드에 결과 자동 복사
                        copyToClipboard(summaryText)

                        // 2. 안내 토스트
                        Toast.makeText(
                            this@FloatingService,
                            "3번 문항 내용이 복사되었습니다! 입력창에 붙여넣으세요.",
                            Toast.LENGTH_LONG
                        ).show()

                        // 3. 스마트장기요양 앱 띄우기 (설치된 경우)
                        launchTargetApp("kr.or.nhis.smartlongterm") // 실제 패키지명
                    } else {
                        Toast.makeText(this@FloatingService, "분석 실패: 서버 응답 오류", Toast.LENGTH_SHORT).show()
                    }
                    resetUi()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloatingService, "전송 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                    resetUi()
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("3번 심신상태", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun launchTargetApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun resetUi() {
        btnRecord.text = "녹음 시작"
        btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
        btnRecord.isEnabled = true
        tvStatus.text = "완료/대기"
    }

    private fun setupDragTouch(handle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun startAsForeground() {
        val channelId = "floating_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "일지 도우미 실행 중",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("장기요양 일지 도우미")
            .setContentText("플로팅 버튼이 활성화되어 있습니다.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
