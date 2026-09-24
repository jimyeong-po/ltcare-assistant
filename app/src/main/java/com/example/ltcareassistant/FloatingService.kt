package com.example.ltcareassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

class FloatingService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var btnAction: Button
    private lateinit var tvStatus: TextView

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""

    // 백엔드 Gemini 분석 API 주소
    private val SERVER_URL = "http://your-server-ip:8000/analyze-recording"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var cachedData: JSONObject? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingWidget()
    }

    private fun showFloatingWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        btnAction = floatingView.findViewById(R.id.btnRecord)
        tvStatus = floatingView.findViewById(R.id.tvStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 260
        }

        windowManager.addView(floatingView, params)

        btnAction.setOnClickListener {
            when {
                !isRecording && cachedData == null -> startRecording()
                isRecording -> stopAndAnalyze()
                cachedData != null -> fillSection3()
            }
        }
    }

    private fun startRecording() {
        audioFilePath = "${externalCacheDir?.absolutePath}/record.m4a"
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFilePath)
            prepare()
            start()
        }
        isRecording = true
        btnAction.text = "분석 종료"
        btnAction.setBackgroundColor(0xFFD32F2F.toInt()) // 빨간색
        tvStatus.text = "어르신 상담 녹음 중..."
    }

    private fun stopAndAnalyze() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
        isRecording = false
        btnAction.isEnabled = false
        tvStatus.text = "3번 문항 AI 분석 중..."

        serviceScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(SERVER_URL)
                    .post(
                        MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("file", "record.m4a", File(audioFilePath).asRequestBody("audio/mp4".toMediaTypeOrNull()))
                            .build()
                    ).build()

                val res = OkHttpClient().newCall(req).execute()
                val body = res.body?.string() ?: ""
                cachedData = JSONObject(body)

                withContext(Dispatchers.Main) {
                    btnAction.isEnabled = true
                    btnAction.text = "자동 입력"
                    btnAction.setBackgroundColor(0xFF1976D2.toInt()) // 파란색
                    tvStatus.text = "준비 완료! 터치 시 입력"
                    Toast.makeText(this@FloatingService, "분석 완료! 장기요양 3번 화면에서 [자동 입력]을 누르세요.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnAction.isEnabled = true
                    btnAction.text = "녹음 시작"
                    btnAction.setBackgroundColor(0xFF2E7D32.toInt())
                    tvStatus.text = "분석 오류 발생"
                }
            }
        }
    }

    /** 3번 심신상태 및 환경변화 (10개 문항) 화면 자동 입력 **/
    private fun fillSection3() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Toast.makeText(this, "장기요양 앱 화면을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val data = cachedData ?: return

        // 10개 세부 문항 매핑 목록 (화면 키워드, JSON 키값)
        val targets = listOf(
            Pair("식사 및 영양", "meals"),
            Pair("보행", "body_walk"),
            Pair("신체기능", "body_function"),
            Pair("배뇨", "excretion"),
            Pair("위생관리", "adl_hygiene"),
            Pair("일상생활수행", "adl_action"),
            Pair("인지기능", "cognitive"),
            Pair("행동증상", "behavior"),
            Pair("생활 환경", "environment")
        )

        var filledCount = 0

        for ((label, jsonKey) in targets) {
            val itemData = data.optJSONObject(jsonKey) ?: continue
            val status = itemData.optString("status", "유지")
            val reason = itemData.optString("reason", "특이사항 없이 기존 상태 유지됨")

            // 1. 해당 문항 제목 텍스트 탐색
            val labelNodes = rootNode.findAccessibilityNodeInfosByText(label)
            if (labelNodes.isNotEmpty()) {
                val blockContainer = labelNodes[0].parent ?: continue

                // 2. 라디오 버튼(유지/악화/호전) 클릭
                val radioNode = blockContainer.findAccessibilityNodeInfosByText(status).firstOrNull()
                radioNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 3. 판단근거 입력칸에 내용 주입
                fillEditText(blockContainer, reason)
                filledCount++
            }
        }

        // 9. 기타 및 종합의견 처리
        val summaryOpinion = data.optString("summary_opinion", "")
        if (summaryOpinion.isNotEmpty()) {
            val summaryNode = rootNode.findAccessibilityNodeInfosByText("종합의견").firstOrNull()
            if (summaryNode != null) {
                val container = summaryNode.parent ?: summaryNode
                fillEditText(container, summaryOpinion)
            }
        }

        // 입력 완료 처리 및 상태 리셋
        Toast.makeText(this, "3번 문항 작성이 완료되었습니다! 확인 후 저장하세요.", Toast.LENGTH_LONG).show()
        cachedData = null
        btnAction.text = "녹음 시작"
        btnAction.setBackgroundColor(0xFF2E7D32.toInt()) // 초록색 복귀
        tvStatus.text = "대기중"
    }

    /** 인접/하위의 EditText를 찾아 텍스트를 채워 넣는 함수 **/
    private fun fillEditText(container: AccessibilityNodeInfo, text: String) {
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            if (child.className == "android.widget.EditText") {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                child.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}
