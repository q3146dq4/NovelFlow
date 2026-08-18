package com.NovelRegEx.app.activity

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.NovelRegEx.app.R
import com.NovelRegEx.app.tts.TtsKoreanNumber
import com.NovelRegEx.app.tts.TtsRegexRule
import com.NovelRegEx.app.tts.TtsRegexStore
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.roundToInt

class TtsRegexSettingsActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var rulesContainer: LinearLayout
    private lateinit var testInput: EditText
    private lateinit var testOutput: TextView
    private lateinit var testButton: Button
    private lateinit var testTtsButton: Button
    private lateinit var stopTtsButton: Button
    private lateinit var countText: TextView

    private var rules = mutableListOf<TtsRegexRule>()

    private var previewTts: TextToSpeech? = null
    private var previewTtsReady = false
    private var pendingPreviewText: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportTo(uri)
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importFrom(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 이 화면은 메인 Activity의 edge-to-edge 설정을 상속하지 않도록 한다.
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_tts_regex_settings)

        bindViews()
        setupInsets()

        rules = TtsRegexStore.load(this)
        renderRules()

        findViewById<Button>(R.id.button_add_rule).setOnClickListener {
            editRule(null)
        }

        findViewById<Button>(R.id.button_reset).setOnClickListener {
            confirmReset()
        }

        findViewById<Button>(R.id.button_import).setOnClickListener {
            importLauncher.launch(
                arrayOf(
                    "application/json",
                    "text/json",
                    "text/plain",
                    "*/*"
                )
            )
        }

        findViewById<Button>(R.id.button_export).setOnClickListener {
            exportLauncher.launch("NovelRegEx-tts-regex.json")
        }

        testButton.setOnClickListener {
            runTest()
        }

        testTtsButton.setOnClickListener {
            speakTestText()
        }

        stopTtsButton.setOnClickListener {
            stopPreviewTts()
        }

        testInput.setText(
            "3.14kg, 14.5km, 1,000원, 8,884,844원, 1$, ${'$'}2, 72%, 3.1/100"
        )

        initPreviewTts()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.tts_regex_root)
        rulesContainer = findViewById(R.id.rules_container)
        testInput = findViewById(R.id.test_input)
        testOutput = findViewById(R.id.test_output)
        testButton = findViewById(R.id.button_test)
        testTtsButton = findViewById(R.id.button_test_tts)
        stopTtsButton = findViewById(R.id.button_stop_tts)
        countText = findViewById(R.id.rule_count)
    }

    private fun setupInsets() {
        val horizontal = dp(16)
        val vertical = dp(12)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                horizontal + bars.left,
                vertical + bars.top,
                horizontal + bars.right,
                vertical + bars.bottom
            )
            insets
        }

        ViewCompat.requestApplyInsets(rootView)
    }

    private fun initPreviewTts() {
        previewTts = TextToSpeech(this) { status ->
            runOnUiThread {
                if (status != TextToSpeech.SUCCESS) {
                    previewTtsReady = false
                    return@runOnUiThread
                }

                val languageResult =
                    previewTts?.setLanguage(Locale.KOREAN)
                        ?: TextToSpeech.ERROR

                previewTtsReady =
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                previewTts?.setSpeechRate(1.0f)

                pendingPreviewText?.let { pending ->
                    pendingPreviewText = null
                    speakPreviewNow(pending)
                }
            }
        }
    }

    // ============================================================
    // 규칙 목록
    // ============================================================

    private fun renderRules() {
        rulesContainer.removeAllViews()
        countText.text = "총 ${rules.size}개 규칙"
        rulesContainer.addView(createKoreanNumberToggleCard())

        if (rules.isEmpty()) {
            rulesContainer.addView(
                TextView(this).apply {
                    text = "등록된 규칙이 없습니다.\n'정규식 추가'로 규칙을 만들어 보세요."
                    textSize = 15f
                    setTextColor(0xFF666666.toInt())
                    setPadding(dp(12), dp(20), dp(12), dp(20))
                }
            )
            return
        }

        rules.forEachIndexed { index, rule ->
            rulesContainer.addView(createRuleCard(index, rule))
        }
    }

    private fun createKoreanNumberToggleCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFE8F0FE.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val toggle = Switch(this).apply {
            text = "한국어 숫자 발음 변환"
            textSize = 16f
            isChecked = TtsRegexStore.isKoreanNumberEnabled(this@TtsRegexSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                TtsRegexStore.setKoreanNumberEnabled(
                    this@TtsRegexSettingsActivity,
                    checked,
                )
            }
        }

        val description = TextView(this).apply {
            text =
                "ON: ${'$'}{ko-number:1} 치환이 숫자를 한국어 발음으로 바꿉니다. " +
                    "예: 3.14kg → 삼점일사킬로그램, 1,000 → 천.\n" +
                    "OFF: 숫자 원문을 유지합니다. 예: 3.14kg → 3.14킬로그램."
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(6), 0, 0)
        }

        card.addView(toggle)
        card.addView(description)
        return card
    }

    private fun createRuleCard(
        index: Int,
        rule: TtsRegexRule
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF1F1F1.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "${index + 1}. ${rule.name}"
            textSize = 17f
            setTextColor(0xFF202020.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val enabledSwitch = Switch(this).apply {
            text = "사용"
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                if (index in rules.indices) {
                    rules[index] = rules[index].copy(enabled = checked)
                    saveRules()
                }
            }
        }

        header.addView(title)
        header.addView(enabledSwitch)
        card.addView(header)

        val detail = TextView(this).apply {
            text = buildString {
                append("패턴: ")
                append(rule.pattern)
                append("\n치환: ")
                append(rule.replacement.ifEmpty { "(삭제)" })
                append("\n")
                append(if (rule.isRegex) "정규식" else "일반 문자열")
                append(" · ")
                append(if (rule.ignoreCase) "대소문자 무시" else "대소문자 구분")
            }
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }
        card.addView(detail)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        buttons.addView(
            smallButton("↑") {
                moveRule(index, index - 1)
            },
            weightParams()
        )

        buttons.addView(
            smallButton("↓") {
                moveRule(index, index + 1)
            },
            weightParams()
        )

        buttons.addView(
            smallButton("편집") {
                editRule(index)
            },
            weightParams()
        )

        buttons.addView(
            smallButton("삭제") {
                confirmDelete(index)
            },
            weightParams()
        )

        card.addView(buttons)

        return card
    }

    private fun smallButton(
        text: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            minHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            dp(42),
            1f
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
    }

    private fun moveRule(from: Int, to: Int) {
        if (from !in rules.indices) return
        if (to !in rules.indices) return

        val item = rules.removeAt(from)
        rules.add(to, item)
        saveRules()
        renderRules()
    }

    private fun saveRules() {
        TtsRegexStore.save(this, rules)
    }

    // ============================================================
    // 추가 / 편집
    // ============================================================

    private fun editRule(index: Int?) {
        val old = index?.let { rules.getOrNull(it) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }

        val name = EditText(this).apply {
            hint = "규칙 이름"
            setText(old?.name ?: "새 규칙")
        }

        val pattern = EditText(this).apply {
            hint = "패턴 / 정규식"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(old?.pattern ?: "")
            minLines = 2
        }

        val replacement = EditText(this).apply {
            hint = "치환 결과 (비우면 삭제)"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(old?.replacement ?: "")
        }

        val regex = CheckBox(this).apply {
            text = "정규식으로 처리"
            isChecked = old?.isRegex ?: true
        }

        val ignoreCase = CheckBox(this).apply {
            text = "대소문자 무시"
            isChecked = old?.ignoreCase ?: false
        }

        val enabled = CheckBox(this).apply {
            text = "사용"
            isChecked = old?.enabled ?: true
        }

        val testHint = TextView(this).apply {
            text =
                "그룹: \$1, \$2 등 · 한국어 숫자: ${'$'}{ko-number:1}\n" +
                    "한국어 숫자 기능을 끄면 매크로는 캡처 숫자 원문을 그대로 사용합니다."
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }

        container.addView(name)
        container.addView(pattern)
        container.addView(replacement)
        container.addView(regex)
        container.addView(ignoreCase)
        container.addView(enabled)
        container.addView(testHint)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (old == null) "정규식 추가" else "정규식 편집")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rule = TtsRegexRule(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { "규칙" },
                    pattern = pattern.text.toString(),
                    replacement = replacement.text.toString(),
                    ignoreCase = ignoreCase.isChecked,
                    isRegex = regex.isChecked,
                    enabled = enabled.isChecked
                )

                if (rule.pattern.isBlank()) {
                    Toast.makeText(
                        this,
                        "패턴을 입력하세요.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                if (rule.isRegex) {
                    runCatching {
                        val flags = if (rule.ignoreCase) {
                            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                        } else {
                            Pattern.UNICODE_CASE
                        }
                        Pattern.compile(rule.pattern, flags)
                    }.onFailure {
                        Toast.makeText(
                            this,
                            "정규식 오류: ${it.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                }

                if (index == null) {
                    rules.add(rule)
                } else if (index in rules.indices) {
                    rules[index] = rule
                }

                saveRules()
                renderRules()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun confirmDelete(index: Int) {
        val rule = rules.getOrNull(index) ?: return

        AlertDialog.Builder(this)
            .setTitle("규칙 삭제")
            .setMessage("'${rule.name}' 규칙을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                if (index in rules.indices) {
                    rules.removeAt(index)
                    saveRules()
                    renderRules()
                }
            }
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("기본 규칙으로 복원")
            .setMessage("현재 사용자 규칙을 모두 기본 규칙으로 교체할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("복원") { _, _ ->
                TtsRegexStore.reset(this)
                rules = TtsRegexStore.load(this)
                renderRules()
            }
            .show()
    }

    // ============================================================
    // 문자열 테스트
    // ============================================================

    private fun runTest(): String {
        val original = testInput.text.toString()
        val result =
            try {
                prepareSpeechText(original)
            } catch (error: Throwable) {
                Toast.makeText(
                    this,
                    "정규식 변환 오류: ${error.message.orEmpty()}",
                    Toast.LENGTH_LONG
                ).show()
                original
            }

        testOutput.text = if (result.isBlank()) {
            "(모든 텍스트가 규칙에 의해 제거되었습니다.)"
        } else {
            result
        }
        return result
    }

    private fun prepareSpeechText(original: String): String {
        var result = original.trim()
        if (result.isEmpty()) return result

        val koreanNumberEnabled = TtsRegexStore.isKoreanNumberEnabled(this)

        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue

            result =
                try {
                    val flags = if (rule.ignoreCase) {
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    } else {
                        Pattern.UNICODE_CASE
                    }
                    val pattern = if (rule.isRegex) {
                        Pattern.compile(rule.pattern, flags)
                    } else {
                        Pattern.compile(Pattern.quote(rule.pattern), flags)
                    }
                    val replacement = if (rule.isRegex) {
                        rule.replacement
                    } else {
                        java.util.regex.Matcher.quoteReplacement(rule.replacement)
                    }

                    if (rule.isRegex && rule.replacement.contains("\${ko-number:")) {
                        TtsKoreanNumber.replaceAll(
                            pattern,
                            result,
                            replacement,
                            koreanNumberEnabled,
                        )
                    } else {
                        pattern.matcher(result).replaceAll(replacement)
                    }
                } catch (_: Throwable) {
                    // One bad user rule must not close the settings screen.
                    result
                }
        }

        return result.trim()
    }

    // ============================================================
    // TTS 테스트 재생
    // ============================================================

    private fun speakTestText() {
        val text = runTest()

        if (text.isBlank()) {
            Toast.makeText(
                this,
                "읽을 텍스트가 없습니다.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!previewTtsReady) {
            pendingPreviewText = text
            Toast.makeText(
                this,
                "TTS 엔진을 준비하는 중입니다.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        speakPreviewNow(text)
    }

    private fun speakPreviewNow(text: String) {
        val result =
            try {
                previewTts?.stop()
                previewTts?.setLanguage(Locale.KOREAN)
                previewTts?.setSpeechRate(1.0f)
                previewTts?.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "np_regex_preview"
                ) ?: TextToSpeech.ERROR
            } catch (error: Throwable) {
                Toast.makeText(
                    this,
                    "TTS 테스트 오류: ${error.message.orEmpty()}",
                    Toast.LENGTH_LONG
                ).show()
                TextToSpeech.ERROR
            }

        if (result == TextToSpeech.ERROR) {
            Toast.makeText(
                this,
                "TTS 테스트 재생에 실패했습니다.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopPreviewTts() {
        previewTts?.stop()
    }

    // ============================================================
    // JSON
    // ============================================================

    private fun exportTo(uri: Uri) {
        runCatching {
            val json = TtsRegexStore.exportJson(this)
            contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("파일을 저장할 수 없습니다.")
        }.onSuccess {
            Toast.makeText(
                this,
                "정규식 설정을 내보냈습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure {
            Toast.makeText(
                this,
                "내보내기 실패: ${it.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun importFrom(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: error("파일을 읽을 수 없습니다.")
        }.fold(
            onSuccess = { json ->
                TtsRegexStore.importJson(this, json).fold(
                    onSuccess = { count ->
                        rules = TtsRegexStore.load(this)
                        renderRules()
                        Toast.makeText(
                            this,
                            "${count}개 규칙을 불러왔습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            "JSON 형식이 올바르지 않습니다: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            },
            onFailure = { error ->
                Toast.makeText(
                    this,
                    "가져오기 실패: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        stopPreviewTts()
        previewTts?.shutdown()
        previewTts = null
        previewTtsReady = false
        super.onDestroy()
    }

}
