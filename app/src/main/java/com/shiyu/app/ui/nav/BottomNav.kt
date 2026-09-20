package com.shiyu.app.ui.nav

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shiyu.app.R
import com.shiyu.app.databinding.ViewBottomNavBinding
import com.shiyu.app.ui.chat.ChatListActivity
import com.shiyu.app.ui.home.HomeActivity
import com.shiyu.app.ui.langs.LanguagesActivity
import com.shiyu.app.ui.profile.ProfileActivity
import com.shiyu.app.ui.review.ReviewActivity

/**
 * 底部导航的唯一接线处。
 *
 * 改造前每个 Activity 里都抄了一份 when(itemId) 分发，加一个 Tab 要改五处；
 * 现在统一收口在这里，Activity 只需要声明"我是哪个 Tab"。
 */
object BottomNav {

    enum class Tab { HOME, LANGS, TALK, REVIEW, PROFILE }

    fun bind(activity: Activity, nav: ViewBottomNavBinding, current: Tab) {
        val clay = ContextCompat.getColor(activity, R.color.clay)
        val mute = ContextCompat.getColor(activity, R.color.mute)

        paint(nav.navHomeIcon, nav.navHomeText, current == Tab.HOME, clay, mute)
        paint(nav.navLangsIcon, nav.navLangsText, current == Tab.LANGS, clay, mute)
        paint(nav.navReviewIcon, nav.navReviewText, current == Tab.REVIEW, clay, mute)
        paint(nav.navProfileIcon, nav.navProfileText, current == Tab.PROFILE, clay, mute)
        nav.navTalkText.setTextColor(if (current == Tab.TALK) clay else mute)

        wire(activity, nav.navHome, current, Tab.HOME, HomeActivity::class.java)
        wire(activity, nav.navLangs, current, Tab.LANGS, LanguagesActivity::class.java)
        wire(activity, nav.navReview, current, Tab.REVIEW, ReviewActivity::class.java)
        wire(activity, nav.navProfile, current, Tab.PROFILE, ProfileActivity::class.java)
        wire(activity, nav.navMic, current, Tab.TALK, ChatListActivity::class.java)
        wire(activity, nav.navTalkSlot, current, Tab.TALK, ChatListActivity::class.java)
    }

    private fun paint(icon: ImageView, label: TextView, active: Boolean, on: Int, off: Int) {
        val color = if (active) on else off
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    private fun wire(
        activity: Activity,
        view: View,
        current: Tab,
        target: Tab,
        clazz: Class<out Activity>
    ) {
        view.setOnClickListener {
            if (current == target) return@setOnClickListener
            activity.startActivity(
                Intent(activity, clazz).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            activity.overridePendingTransition(0, 0)
        }
    }
}
