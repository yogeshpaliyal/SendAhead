package com.yogeshpaliyal.sendahead

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.yogeshpaliyal.sendahead.databinding.ActivityIntroBinding
import com.yogeshpaliyal.sendahead.databinding.ItemIntroPageBinding
import com.google.android.material.R as M

/** Three short screens shown once, on first launch. */
class IntroActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "settings"
        private const val KEY_DONE = "intro_done"

        fun shouldShow(ctx: Context) =
            !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }

    private data class Page(
        val title: String,
        val body: String,
        @DrawableRes val icon: Int,
        @AttrRes val shapeColor: Int,
        @AttrRes val iconColor: Int,
    )

    private val pages = listOf(
        Page(
            "Write it now, send it later",
            "Schedule WhatsApp messages for any time. SendAhead sends them for you.",
            R.drawable.ic_schedule, M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer,
        ),
        Page(
            "Photos, files and groups",
            "Add attachments, and pick a group or contact straight from WhatsApp.",
            R.drawable.ic_attach, M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer,
        ),
        Page(
            "Stays on your phone",
            "No account and no internet permission. Your messages and files never leave this phone.",
            R.drawable.ic_lock, M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer,
        ),
    )

    private lateinit var binding: ActivityIntroBinding
    private var spin: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = PageAdapter()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render(position)
        })
        buildDots()
        render(0)

        binding.skip.setOnClickListener { done() }
        binding.next.setOnClickListener {
            val i = binding.pager.currentItem
            if (i < pages.lastIndex) binding.pager.currentItem = i + 1 else done()
        }
    }

    override fun onDestroy() {
        spin?.cancel()
        super.onDestroy()
    }

    private fun render(position: Int) {
        val last = position == pages.lastIndex
        binding.next.text = if (last) "Get started" else "Next"
        binding.skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        updateDots(position)
    }

    private fun done() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // Page indicator: the current page's dot stretches into a pill.
    private fun buildDots() {
        binding.dots.removeAllViews()
        pages.indices.forEach { _ ->
            binding.dots.addView(View(this).apply {
                background = getDrawable(R.drawable.bg_pill)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
            })
        }
    }

    private fun updateDots(position: Int) {
        val active = themeColor(this, androidx.appcompat.R.attr.colorPrimary)
        val idle = themeColor(this, M.attr.colorOutlineVariant)
        for (i in 0 until binding.dots.childCount) {
            val dot = binding.dots.getChildAt(i)
            val target = if (i == position) dp(28) else dp(8)
            dot.backgroundTintList = ColorStateList.valueOf(if (i == position) active else idle)
            val lp = dot.layoutParams
            if (!ValueAnimator.areAnimatorsEnabled()) {
                lp.width = target
                dot.layoutParams = lp
                continue
            }
            ValueAnimator.ofInt(lp.width, target).apply {
                duration = 250
                addUpdateListener {
                    lp.width = it.animatedValue as Int
                    dot.layoutParams = lp
                }
            }.start()
        }
        binding.dots.contentDescription = "Page ${position + 1} of ${pages.size}"
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(val b: ItemIntroPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemIntroPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = pages[position]
            val ctx = holder.b.root.context
            holder.b.title.text = p.title
            holder.b.body.text = p.body
            holder.b.icon.setImageResource(p.icon)
            holder.b.icon.imageTintList = ColorStateList.valueOf(themeColor(ctx, p.iconColor))
            holder.b.shape.imageTintList = ColorStateList.valueOf(themeColor(ctx, p.shapeColor))
            // The one moving thing: the scalloped shape turns slowly. Off when animations are off.
            if (ValueAnimator.areAnimatorsEnabled()) {
                holder.b.shape.animate().cancel()
                ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 24_000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { holder.b.shape.rotation = it.animatedValue as Float }
                    start()
                    holder.b.shape.setTag(R.id.shape, this)
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            (holder.b.shape.getTag(R.id.shape) as? ValueAnimator)?.cancel()
        }
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
