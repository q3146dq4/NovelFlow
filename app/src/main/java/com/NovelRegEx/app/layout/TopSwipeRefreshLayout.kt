package com.NovelRegEx.app.layout

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.withStyledAttributes
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.NovelRegEx.app.R

class TopSwipeRefreshLayout
  @JvmOverloads
  constructor(
    context: Context,
    attrs: AttributeSet? = null,
  ) : SwipeRefreshLayout(context, attrs) {
    companion object {
      const val DEFAULT_TRIGGER_FRACTION = 0.15f
    }

    var triggerFraction: Float = DEFAULT_TRIGGER_FRACTION
      set(value) {
        field = value.coerceIn(0f, 1f)
      }

    init {
      attrs?.let {
        context.withStyledAttributes(it, R.styleable.TopSwipeRefreshLayout) {
          triggerFraction = getFraction(R.styleable.TopSwipeRefreshLayout_triggerFraction, 1, 1, DEFAULT_TRIGGER_FRACTION)
        }
      }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
      if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.y > height * triggerFraction) {
        return false
      }
      return super.onInterceptTouchEvent(ev)
    }
  }
