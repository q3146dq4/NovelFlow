package com.NovelRegEx.app.activity

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.NovelRegEx.app.R
import com.NovelRegEx.app.bookmark.BookmarkItem
import com.NovelRegEx.app.bookmark.BookmarkRepository
import java.util.Collections

class BookmarkOrderActivity : AppCompatActivity() {
  private lateinit var repository: BookmarkRepository
  private lateinit var adapter: OrderAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_bookmark_order)

    repository = BookmarkRepository(this)
    adapter = OrderAdapter(repository.getAll().toMutableList())

    setupInsets()
    setupToolbar()
    setupList()
  }

  private fun setupInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bookmark_order_root)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  private fun setupToolbar() {
    val backButton = findViewById<ImageButton>(R.id.bookmark_order_back)
    TooltipCompat.setTooltipText(backButton, getString(R.string.tooltip_back))
    backButton.setOnClickListener { finish() }
  }

  private fun setupList() {
    val recyclerView = findViewById<RecyclerView>(R.id.bookmark_order_list)
    recyclerView.adapter = adapter

    ItemTouchHelper(
      object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled(): Boolean = true

        override fun onMove(
          recyclerView: RecyclerView,
          viewHolder: RecyclerView.ViewHolder,
          target: RecyclerView.ViewHolder,
        ): Boolean {
          adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
          return true
        }

        override fun onSwiped(
          viewHolder: RecyclerView.ViewHolder,
          direction: Int,
        ) = Unit

        override fun onSelectedChanged(
          viewHolder: RecyclerView.ViewHolder?,
          actionState: Int,
        ) {
          super.onSelectedChanged(viewHolder, actionState)
          if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
          }
        }

        override fun clearView(
          recyclerView: RecyclerView,
          viewHolder: RecyclerView.ViewHolder,
        ) {
          super.clearView(recyclerView, viewHolder)
          repository.saveAll(adapter.items)
        }
      },
    ).attachToRecyclerView(recyclerView)
  }

  private class OrderAdapter(
    val items: MutableList<BookmarkItem>,
  ) : RecyclerView.Adapter<OrderAdapter.ViewHolder>() {
    override fun onCreateViewHolder(
      parent: ViewGroup,
      viewType: Int,
    ): ViewHolder {
      val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark_order, parent, false)
      return ViewHolder(view)
    }

    override fun onBindViewHolder(
      holder: ViewHolder,
      position: Int,
    ) {
      val item = items[position]
      holder.title.text = item.title
      holder.url.text = item.url
    }

    override fun getItemCount(): Int = items.size

    fun move(
      fromPosition: Int,
      toPosition: Int,
    ) {
      if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) return
      if (fromPosition < toPosition) {
        for (index in fromPosition until toPosition) {
          Collections.swap(items, index, index + 1)
        }
      } else {
        for (index in fromPosition downTo toPosition + 1) {
          Collections.swap(items, index, index - 1)
        }
      }
      notifyItemMoved(fromPosition, toPosition)
    }

    class ViewHolder(
      view: View,
    ) : RecyclerView.ViewHolder(view) {
      val title: TextView = view.findViewById(R.id.bookmark_order_title)
      val url: TextView = view.findViewById(R.id.bookmark_order_url)
    }
  }
}
