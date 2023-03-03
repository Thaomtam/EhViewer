/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.LinearDividerItemDecoration
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.util.getParcelableCompat
import com.hippo.view.ViewTransition
import com.hippo.yorozuya.AnimationUtils
import com.hippo.yorozuya.LayoutUtils
import com.hippo.yorozuya.MathUtils
import com.hippo.yorozuya.SimpleAnimatorListener
import com.hippo.yorozuya.ViewUtils
import rikka.core.res.resolveColor

class SearchBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MaterialCardView(context, attrs), View.OnClickListener, TextView.OnEditorActionListener,
        TextWatcher, SearchEditText.SearchEditTextListener {
    private val mRect = Rect()
    private val mSearchDatabase by lazy { SearchDatabase.getInstance(context) }
    private var mState = STATE_NORMAL
    private var mBaseHeight = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mProgress = 0f
    private var mMenuButton: ImageView? = null
    private var mTitleTextView: TextView? = null
    private var mActionButton: ImageView? = null
    private var mEditText: SearchEditText? = null
    private var mListContainer: View? = null
    private var mListView: EasyRecyclerView? = null
    private var mListHeader: View? = null
    private var mViewTransition: ViewTransition? = null
    private var mSuggestionList: List<Suggestion>? = null
    private var mSuggestionAdapter: SuggestionAdapter? = null
    private var mHelper: Helper? = null
    private var mSuggestionProvider: SuggestionProvider? = null
    private var mOnStateChangeListener: OnStateChangeListener? = null
    private var mAllowEmptySearch = true
    private var mInAnimation = false

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.widget_search_bar, this)
        mMenuButton = ViewUtils.`$$`(this, R.id.search_menu) as ImageView
        mTitleTextView = ViewUtils.`$$`(this, R.id.search_title) as TextView
        mActionButton = ViewUtils.`$$`(this, R.id.search_action) as ImageView
        mEditText = ViewUtils.`$$`(this, R.id.search_edit_text) as SearchEditText
        mListContainer = ViewUtils.`$$`(this, R.id.list_container)
        mListView = ViewUtils.`$$`(mListContainer, R.id.search_bar_list) as EasyRecyclerView
        mListHeader = ViewUtils.`$$`(mListContainer, R.id.list_header)
        mViewTransition = ViewTransition(mTitleTextView, mEditText)
        mTitleTextView!!.setOnClickListener(this)
        mMenuButton!!.setOnClickListener(this)
        mActionButton!!.setOnClickListener(this)
        mEditText!!.setSearchEditTextListener(this)
        mEditText!!.setOnEditorActionListener(this)
        mEditText!!.addTextChangedListener(this)

        // Get base height
        ViewUtils.measureView(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mBaseHeight = measuredHeight

        mSuggestionList = ArrayList()
        mSuggestionAdapter = SuggestionAdapter(LayoutInflater.from(context))
        mListView!!.setAdapter(mSuggestionAdapter)
        val decoration = LinearDividerItemDecoration(
            LinearDividerItemDecoration.VERTICAL,
            context.theme.resolveColor(R.attr.dividerColor),
            LayoutUtils.dp2pix(context, 1f)
        )
        decoration.setShowLastDivider(false)
        mListView!!.addItemDecoration(decoration)
        mListView!!.setLayoutManager(LinearLayoutManager(context))
    }

    private fun addListHeader() {
        mListHeader!!.visibility = View.VISIBLE
    }

    private fun removeListHeader() {
        mListHeader!!.visibility = View.GONE
    }

    private fun updateSuggestions(scrollToTop: Boolean = true) {
        val suggestions = ArrayList<Suggestion>()
        val text = mEditText!!.text.toString()
        mSuggestionProvider?.run {
            providerSuggestions(text)?.let {
                suggestions.addAll(it)
            }
        }
        mSearchDatabase.getSuggestions(text, 128).forEach {
            suggestions.add(KeywordSuggestion(it))
        }
        EhTagDatabase.takeIf { it.isInitialized() }?.run {
            if (!TextUtils.isEmpty(text) && !text.endsWith(" ")) {
                val keyword = text.substringAfterLast(" ")
                val translate = Settings.getShowTagTranslations() && isTranslatable(context)
                suggest(keyword, translate).forEach {
                    suggestions.add(TagSuggestion(it.first, it.second))
                }
            }
        }
        mSuggestionList = suggestions
        if (mSuggestionList?.size == 0) {
            removeListHeader()
        } else {
            addListHeader()
        }
        mSuggestionAdapter?.notifyDataSetChanged()
        if (scrollToTop) {
            mListView!!.scrollToPosition(0)
        }
    }

    fun setAllowEmptySearch(allowEmptySearch: Boolean) {
        mAllowEmptySearch = allowEmptySearch
    }

    fun setEditTextHint(hint: CharSequence) {
        mEditText!!.hint = hint
    }

    fun setHelper(helper: Helper) {
        mHelper = helper
    }

    fun setOnStateChangeListener(listener: OnStateChangeListener) {
        mOnStateChangeListener = listener
    }

    fun setSuggestionProvider(suggestionProvider: SuggestionProvider) {
        mSuggestionProvider = suggestionProvider
    }

    fun getText(): String {
        return mEditText!!.text.toString()
    }

    fun setText(text: String) {
        mEditText!!.setText(text)
    }

    fun cursorToEnd() {
        mEditText!!.setSelection(mEditText!!.text!!.length)
    }

    fun setTitle(title: String) {
        mTitleTextView!!.text = title
    }

    fun setSearch(search: String) {
        mTitleTextView!!.text = search
        mEditText!!.setText(search)
    }

    fun setLeftDrawable(drawable: Drawable) {
        mMenuButton!!.setImageDrawable(drawable)
    }

    fun setRightDrawable(drawable: Drawable) {
        mActionButton!!.setImageDrawable(drawable)
    }

    fun applySearch() {
        val query = mEditText!!.text.toString().trim { it <= ' ' }
        if (!mAllowEmptySearch && TextUtils.isEmpty(query)) {
            return
        }
        // Put it into db
        mSearchDatabase.addQuery(query)
        // Callback
        mHelper?.onApplySearch(query)
    }

    override fun onClick(v: View) {
        if (v === mTitleTextView) {
            mHelper?.onClickTitle()
        } else if (v === mMenuButton) {
            mHelper?.onClickLeftIcon()
        } else if (v === mActionButton) {
            mHelper?.onClickRightIcon()
        }
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (v === mEditText) {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) {
                applySearch()
                return true
            }
        }
        return false
    }

    fun getState(): Int {
        return mState
    }

    fun setState(state: Int, animation: Boolean = true) {
        if (mState != state) {
            val oldState = mState
            mState = state
            when (oldState) {
                STATE_NORMAL -> {
                    mViewTransition!!.showView(1, animation)
                    mEditText!!.requestFocus()
                    if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
                STATE_SEARCH -> {
                    if (state == STATE_NORMAL) {
                        mViewTransition!!.showView(0, animation)
                    } else if (state == STATE_SEARCH_LIST) {
                        showImeAndSuggestionsList(animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
                STATE_SEARCH_LIST -> {
                    hideImeAndSuggestionsList(animation)
                    if (state == STATE_NORMAL) {
                        mViewTransition!!.showView(0, animation)
                    }
                    mOnStateChangeListener?.onStateChange(this, state, oldState, animation)
                }
            }
        }
    }

    fun showImeAndSuggestionsList(animation: Boolean) {
        // Show ime
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(mEditText, 0)
        // update suggestion for show suggestions list
        updateSuggestions()
        // Show suggestions list
        if (animation) {
            val oa = ObjectAnimator.ofFloat(this, "progress", 1f)
            oa.duration = ANIMATE_TIME
            oa.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
            oa.addListener(object : SimpleAnimatorListener() {
                override fun onAnimationStart(animation: Animator) {
                    mListContainer!!.visibility = View.VISIBLE
                    mInAnimation = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    mInAnimation = false
                }
            })
            oa.setAutoCancel(true)
            oa.start()
        } else {
            mListContainer!!.visibility = View.VISIBLE
            setProgress(1f)
        }
    }

    fun hideImeAndSuggestionsList(animation: Boolean) {
        // Hide ime
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        // Hide suggestions list
        if (animation) {
            val oa = ObjectAnimator.ofFloat(this, "progress", 0f)
            oa.duration = ANIMATE_TIME
            oa.interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
            oa.addListener(object : SimpleAnimatorListener() {
                override fun onAnimationStart(animation: Animator) {
                    mInAnimation = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    mListContainer!!.visibility = View.GONE
                    mInAnimation = false
                }
            })
            oa.setAutoCancel(true)
            oa.start()
        } else {
            setProgress(0f)
            mListContainer!!.visibility = View.GONE
        }
    }

    override protected fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (mListContainer!!.visibility == View.VISIBLE && (!mInAnimation || mHeight == 0)) {
            mWidth = right - left
            mHeight = bottom - top
        }
    }

    override fun getProgress(): Float {
        return mProgress
    }

    override fun setProgress(progress: Float) {
        mProgress = progress
        invalidate()
    }

    fun getEditText(): SearchEditText {
        return mEditText!!
    }

    override fun draw(canvas: Canvas) {
        if (mInAnimation && mHeight != 0) {
            val state = canvas.save()
            val bottom = MathUtils.lerp(mBaseHeight, mHeight, mProgress)
            mRect.set(0, 0, mWidth, bottom)
            setClipBounds(mRect)
            canvas.clipRect(mRect)
            super.draw(canvas)
            canvas.restoreToCount(state)
        } else {
            setClipBounds(null)
            super.draw(canvas)
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        // Empty
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // Empty
    }

    override fun afterTextChanged(s: Editable) {
        updateSuggestions()
    }

    override fun onClick() {
        mHelper?.onSearchEditTextClick()
    }

    override fun onBackPressed() {
        mHelper?.onSearchEditTextBackPressed()
    }

    override fun onReceiveContent(uri: Uri?) {
        mHelper?.onReceiveContent(uri)
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putInt(STATE_KEY_STATE, mState)
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelableCompat(STATE_KEY_SUPER))
            setState(state.getInt(STATE_KEY_STATE), false)
        }
    }

    private fun wrapTagKeyword(keyword: String): String {
        return if (keyword.endsWith(':')) {
            keyword
        } else if (keyword.contains(" ")) {
            val tag = keyword.substringAfter(':')
            val prefix = keyword.dropLast(tag.length)
            "$prefix\"$tag$\" "
        } else {
            "$keyword$ "
        }
    }

    interface Helper {
        fun onClickTitle()
        fun onClickLeftIcon()
        fun onClickRightIcon()
        fun onSearchEditTextClick()
        fun onApplySearch(query: String)
        fun onSearchEditTextBackPressed()
        fun onReceiveContent(uri: Uri?)
    }

    interface OnStateChangeListener {
        fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean)
    }

    interface SuggestionProvider {
        fun providerSuggestions(text: String): List<Suggestion>?
    }

    abstract class Suggestion {
        abstract fun getText(textView: TextView): CharSequence?
        abstract fun onClick()
        open fun onLongClick(): Boolean {
            return false
        }
    }

    private class SuggestionHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1 = itemView.findViewById(android.R.id.text1) as TextView
        val text2 = itemView.findViewById(android.R.id.text2) as TextView
    }

    private inner class SuggestionAdapter constructor(
        private val mInflater: LayoutInflater
    ) : RecyclerView.Adapter<SuggestionHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionHolder {
            return SuggestionHolder(mInflater.inflate(R.layout.item_simple_list_2, parent, false))
        }

        override fun onBindViewHolder(holder: SuggestionHolder, position: Int) {
            val suggestion = mSuggestionList?.get(position)
            val text1 = suggestion?.getText(holder.text1)
            val text2 = suggestion?.getText(holder.text2)
            holder.text1.text = text1
            if (text2 == null) {
                holder.text2.visibility = View.GONE
                holder.text2.text = ""
            } else {
                holder.text2.visibility = View.VISIBLE
                holder.text2.text = text2
            }

            holder.itemView.setOnClickListener {
                mSuggestionList?.run {
                    if (position < size) {
                        this[position].onClick()
                    }
                }
            }
            holder.itemView.setOnLongClickListener {
                mSuggestionList?.run {
                    if (position < size) {
                        return@setOnLongClickListener this[position].onLongClick()
                    }
                }
                return@setOnLongClickListener false
            }
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getItemCount(): Int {
            return mSuggestionList?.size ?: 0
        }
    }

    inner class TagSuggestion constructor(
        private var mHint: String?,
        private var mKeyword: String
    ) : SearchBar.Suggestion() {
        override fun getText(textView: TextView): CharSequence? {
            return if (textView.id == android.R.id.text1) {
                mKeyword
            } else {
                mHint
            }
        }

        override fun onClick() {
            val editable = mEditText!!.text as Editable
            val keywords = editable.toString().substringBeforeLast(" ", "")
            val keyword = wrapTagKeyword(mKeyword)
            val newKeywords = if (keywords.isNotEmpty()) "$keywords $keyword" else keyword
            mEditText!!.setText(newKeywords)
            mEditText!!.setSelection(newKeywords.length)
        }
    }

    inner class KeywordSuggestion constructor(
        private val mKeyword: String
    ) : Suggestion() {
        override fun getText(textView: TextView): CharSequence? {
            return if (textView.id == android.R.id.text1) {
                mKeyword
            } else {
                null
            }
        }

        override fun onClick() {
            mEditText!!.setText(mKeyword)
            mEditText!!.setSelection(mEditText!!.length())
        }

        override fun onLongClick(): Boolean {
            mSearchDatabase.deleteQuery(mKeyword)
            updateSuggestions(false)
            return true
        }
    }

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_SEARCH = 1
        const val STATE_SEARCH_LIST = 2
        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_STATE = "state"
        private const val ANIMATE_TIME = 300L
    }
}