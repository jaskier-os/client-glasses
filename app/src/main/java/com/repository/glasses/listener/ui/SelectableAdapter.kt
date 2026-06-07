package com.repository.glasses.listener.ui

interface SelectableAdapter {
    val selectedPosition: Int
    val adapterItemCount: Int
    fun moveSelectionUp()
    fun moveSelectionDown()
    fun setFocused(isFocused: Boolean)
    fun selectPosition(pos: Int)
}
