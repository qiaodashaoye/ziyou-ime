package com.ziyou.ime.ime

import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 面板协调器的公共宿主契约：视图容器访问与面板打开前的状态清理。
 * 所有面板（技能 / AI / 涂鸦 / 粘贴板 / 工具 / 键盘选择 / 语音）的 Host 接口
 * 均继承此基接口，Service 端通过 [ZiYouInputMethodService.basePanelHost] 提供
 * 统一实现，各具体 Host 只需补充自身独有的方法。
 */
interface BasePanelHost {
    /** 输入视图内容根容器（面板挂载点） */
    fun contentLayout(): LinearLayout?

    /** 键盘容器（面板展开时隐藏/恢复） */
    fun keyboardContainer(): FrameLayout?

    /** 候选区容器（编码区 + 候选词列表） */
    fun candidatesContainer(): LinearLayout?

    /** 当前键盘视图引用 */
    fun keyboardView(): BaseKeyboardView?

    /** 面板即将打开时的统一清理（清除活跃编码与候选/编码区展示） */
    fun onPanelWillOpen()
}
