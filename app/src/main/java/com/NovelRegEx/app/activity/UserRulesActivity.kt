package com.NovelRegEx.app.activity

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.filter.FilterRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserRulesActivity : AppCompatActivity() {
  companion object {
    private const val MENU_ADD = 1
    private const val MENU_EDIT_ALL = 2
  }

  private lateinit var listView: ListView
  private lateinit var emptyView: TextView
  private lateinit var adapter: RulesAdapter
  private var rules: List<String> = emptyList()
  private var disabledRules: Set<String> = emptySet()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = "사용자 규칙"
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    setContentView(createContentView())
    adapter = RulesAdapter()
    listView.adapter = adapter
  }

  override fun onResume() {
    super.onResume()
    reloadRules()
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menu.add(Menu.NONE, MENU_ADD, 0, "추가").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    menu.add(Menu.NONE, MENU_EDIT_ALL, 1, "전체 편집").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean =
    when (item.itemId) {
      MENU_ADD -> {
        showSingleRuleEditor(index = null)
        true
      }

      MENU_EDIT_ALL -> {
        showAllRulesEditor()
        true
      }

      else -> super.onOptionsItemSelected(item)
    }

  private fun createContentView(): View {
    val density = resources.displayMetrics.density
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (6 * density).toInt(), 0, 0)
      }

    root.addView(
      TextView(this).apply {
        text = "규칙을 눌러 편집하고, 길게 눌러 삭제할 수 있습니다."
        textSize = 13f
        alpha = 0.7f
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
      },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )

    val listFrame = FrameLayout(this)
    listView =
      ListView(this).apply {
        dividerHeight = 0
        clipToPadding = false
        setPadding(0, 0, 0, (12 * density).toInt())
      }
    emptyView =
      TextView(this).apply {
        text = "등록된 사용자 규칙이 없습니다."
        gravity = Gravity.CENTER
        textSize = 15f
        alpha = 0.7f
        visibility = View.GONE
      }

    listFrame.addView(
      listView,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    listFrame.addView(
      emptyView,
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
    )
    root.addView(
      listFrame,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
    )
    return root
  }

  private fun reloadRules() {
    rules = FilterPreferences.getUserRuleLines(this)
    disabledRules = FilterPreferences.getDisabledUserRuleLines(this).toSet()
    adapter.notifyDataSetChanged()
    emptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
  }

  private fun setRuleEnabled(
    rule: String,
    enabled: Boolean,
  ) {
    FilterPreferences.setUserRuleEnabled(this, rule, enabled)
    reloadRules()
    refreshFilterEngine()
  }

  private fun showSingleRuleEditor(index: Int?) {
    val existing = index?.let(rules::getOrNull)
    val editText =
      EditText(this).apply {
        setText(existing.orEmpty())
        gravity = Gravity.TOP or Gravity.START
        minLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        setSelection(text.length)
      }

    val dialog =
      AlertDialog
        .Builder(this)
        .setTitle(if (index == null) "규칙 추가" else "규칙 편집")
        .setView(wrapWithDialogPadding(editText))
        .setPositiveButton("저장", null)
        .setNegativeButton("취소", null)
        .apply {
          if (index != null) {
            setNeutralButton("삭제", null)
          }
        }.create()

    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val newRule = editText.text.toString().trim()
        if (newRule.isEmpty()) {
          editText.error = "규칙을 입력해 주세요."
          return@setOnClickListener
        }

        val oldRules = rules
        val oldDisabled = disabledRules
        val updated = oldRules.toMutableList()
        if (index == null) {
          if (newRule in updated) {
            editText.error = "이미 등록된 규칙입니다."
            return@setOnClickListener
          }
          updated += newRule
        } else {
          val oldRule = updated[index]
          if (newRule != oldRule && newRule in updated) {
            editText.error = "이미 등록된 규칙입니다."
            return@setOnClickListener
          }
          updated[index] = newRule
        }

        FilterPreferences.setUserRuleLines(this, updated)
        if (index != null) {
          val oldRule = oldRules[index]
          if (oldRule in oldDisabled && newRule != oldRule) {
            val disabled = FilterPreferences.getDisabledUserRuleLines(this).toMutableSet()
            disabled.remove(oldRule)
            disabled += newRule
            FilterPreferences.setDisabledUserRuleLines(this, disabled.toList())
          }
        }
        reloadRules()
        refreshFilterEngine()
        dialog.dismiss()
      }

      if (index != null) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
          confirmDelete(index)
          dialog.dismiss()
        }
      }
    }
    dialog.show()
  }

  private fun confirmDelete(index: Int) {
    val rule = rules.getOrNull(index) ?: return
    AlertDialog
      .Builder(this)
      .setTitle("규칙 삭제")
      .setMessage("${index + 1}번 규칙을 삭제할까요?\n\n$rule")
      .setPositiveButton("삭제") { _, _ ->
        val updated = rules.toMutableList().apply { removeAt(index) }
        FilterPreferences.setUserRuleLines(this, updated)
        reloadRules()
        refreshFilterEngine()
      }.setNegativeButton("취소", null)
      .show()
  }

  private fun showAllRulesEditor() {
    val density = resources.displayMetrics.density
    val numbers =
      TextView(this).apply {
        textSize = 14f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.END
        setPadding((8 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        alpha = 0.55f
      }
    val editor =
      EditText(this).apply {
        setText(rules.joinToString("\n"))
        textSize = 14f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(true)
        isVerticalScrollBarEnabled = true
      }

    fun updateNumbers() {
      val count = editor.text.toString().count { it == '\n' } + 1
      numbers.text = (1..count).joinToString("\n")
    }
    updateNumbers()
    editor.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateNumbers()
        override fun afterTextChanged(s: Editable?) = Unit
      },
    )
    editor.setOnScrollChangeListener { _, _, scrollY, _, _ -> numbers.scrollY = scrollY }

    val editorRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
          numbers,
          LinearLayout.LayoutParams((46 * density).toInt(), (420 * density).toInt()),
        )
        addView(
          editor,
          LinearLayout.LayoutParams(0, (420 * density).toInt(), 1f),
        )
      }

    val oldRules = rules.toList()
    val oldDisabled = disabledRules.toSet()
    val dialog =
      AlertDialog
        .Builder(this)
        .setTitle("사용자 규칙 전체 편집")
        .setView(wrapWithDialogPadding(editorRow))
        .setPositiveButton("저장", null)
        .setNegativeButton("취소", null)
        .create()

    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val newRules =
          editor.text
            .toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        FilterPreferences.setUserRuleLines(this, newRules)

        // Exact matches keep their state. If an OFF rule is edited in place,
        // preserve the OFF state for the rule occupying the same line.
        val transferredDisabled = linkedSetOf<String>()
        newRules.forEachIndexed { newIndex, newRule ->
          if (newRule in oldDisabled) {
            transferredDisabled += newRule
          } else if (newIndex < oldRules.size && oldRules[newIndex] in oldDisabled) {
            transferredDisabled += newRule
          }
        }
        FilterPreferences.setDisabledUserRuleLines(this, transferredDisabled.toList())

        reloadRules()
        refreshFilterEngine()
        Toast.makeText(this, "사용자 규칙을 저장했습니다.", Toast.LENGTH_SHORT).show()
        dialog.dismiss()
      }
    }
    dialog.show()
  }

  private fun wrapWithDialogPadding(child: View): View {
    val density = resources.displayMetrics.density
    return LinearLayout(this).apply {
      setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
      addView(
        child,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
      )
    }
  }

  private fun refreshFilterEngine() {
    lifecycleScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          FilterRuntime.getInstance(applicationContext).refreshEngine(force = true)
        }
      }
    }
  }

  private fun ruleTypeLabel(rule: String): String =
    when {
      "#@#" in rule -> "요소 숨김 예외"
      "##" in rule -> "요소 숨김"
      rule.startsWith("@@") -> "네트워크 예외"
      else -> "네트워크"
    }

  private inner class RulesAdapter : BaseAdapter() {
    override fun getCount(): Int = rules.size
    override fun getItem(position: Int): String = rules[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(
      position: Int,
      convertView: View?,
      parent: ViewGroup,
    ): View {
      val row = convertView as? LinearLayout ?: createRuleRow(parent)
      val number = row.getChildAt(0) as TextView
      val textContainer = row.getChildAt(1) as LinearLayout
      val ruleText = textContainer.getChildAt(0) as TextView
      val typeText = textContainer.getChildAt(1) as TextView
      val switch = row.getChildAt(2) as SwitchCompat

      val rule = getItem(position)
      val enabled = rule !in disabledRules
      number.text = (position + 1).toString()
      ruleText.text = rule
      typeText.text = ruleTypeLabel(rule)
      ruleText.alpha = if (enabled) 1f else 0.48f
      typeText.alpha = if (enabled) 0.62f else 0.35f

      switch.setOnCheckedChangeListener(null)
      switch.isChecked = enabled
      switch.contentDescription = "${position + 1}번 규칙 ${if (enabled) "사용" else "사용 안 함"}"
      switch.setOnCheckedChangeListener { _, checked -> setRuleEnabled(rule, checked) }

      row.setOnClickListener { showSingleRuleEditor(position) }
      row.setOnLongClickListener {
        confirmDelete(position)
        true
      }
      return row
    }

    private fun createRuleRow(parent: ViewGroup): LinearLayout {
      val density = parent.resources.displayMetrics.density
      return LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (70 * density).toInt()
        setPadding((8 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
        isClickable = true
        isFocusable = true

        addView(
          TextView(parent.context).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            alpha = 0.65f
          },
          LinearLayout.LayoutParams((42 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
        )

        addView(
          LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
              TextView(parent.context).apply {
                textSize = 14f
                typeface = Typeface.MONOSPACE
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
              },
              LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
              TextView(parent.context).apply {
                textSize = 12f
                setPadding(0, (3 * density).toInt(), 0, 0)
              },
              LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
          },
          LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        addView(
          SwitchCompat(parent.context),
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
      }
    }
  }
}
