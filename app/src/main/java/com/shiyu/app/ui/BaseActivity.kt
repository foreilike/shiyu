package com.shiyu.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shiyu.app.AppGraph

/**
 * 所有页面的基类，集中解决两件事：
 *
 * 1. **系统标题栏冲突**。设计稿每一屏都自带页头（"今日 / 晚上好，xx" 一类），
 *    所以主题里已经关掉 ActionBar；这里进一步把窗口设成 edge-to-edge
 *    （状态栏/导航栏透明、内容铺满），再由 [fitTopInset] / [fitBottomInset]
 *    把系统栏高度还给具体视图。不这么做的话，要么页头被状态栏压住，
 *    要么状态栏是一块与暖纸底色对不上的灰。
 * 2. [AppGraph] 的幂等初始化（每个 Activity 都要调一次）。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppGraph.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
    }

    /** 顶部视图吃掉状态栏高度（在其原有 paddingTop 之上叠加） */
    protected fun fitTopInset(view: View) {
        val base = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, base + bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 底部视图吃掉导航栏高度（手势条 / 三键栏都适用） */
    protected fun fitBottomInset(view: View) {
        val base = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * 同上，但键盘弹出时改吃键盘高度（取两者较大值）。
     * 用在带输入框的页面，避免输入框被软键盘盖住。
     */
    protected fun fitBottomInsetWithIme(view: View) {
        val base = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + maxOf(nav, ime))
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
