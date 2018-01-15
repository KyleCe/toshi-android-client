/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.activity

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import com.toshi.R
import com.toshi.extensions.isVisible
import com.toshi.extensions.startActivityAndFinish
import com.toshi.extensions.startActivity
import com.toshi.extensions.toast
import com.toshi.model.local.User
import com.toshi.model.network.ReputationScore
import com.toshi.util.ImageUtil
import com.toshi.util.LogUtil
import com.toshi.util.RatingHandler
import com.toshi.util.SoundManager
import com.toshi.util.UserBlockingHandler
import com.toshi.util.UserReportingHandler
import com.toshi.viewModel.ViewUserViewModel
import kotlinx.android.synthetic.main.activity_view_bot.*

class ViewBotActivity : AppCompatActivity() {

    companion object {
        const val EXTRA__BOT_ADDRESS = "extra_bot_address"
        const val EXTRA__BOT_NAME = "extra_bot_name"
        const val EXTRA__PLAY_SCAN_SOUNDS = "play_scan_sounds"
    }

    private lateinit var viewModel: ViewUserViewModel

    private lateinit var blockingHandler: UserBlockingHandler
    private lateinit var ratingHandler: RatingHandler
    private lateinit var reportHandler: UserReportingHandler
    private var menu: Menu? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_bot)
        init()
    }

    private fun init() {
        initViewModel()
        initToolbar()
        initHandlers()
        initClickListeners()
        initObservers()
        processIntentData()
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(ViewUserViewModel::class.java)
    }

    private fun initToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun initHandlers() {
        blockingHandler = UserBlockingHandler(this, { blockUser() }, { unblockUser() })
        ratingHandler = RatingHandler(this, { viewModel.submitReview(it) })
        reportHandler = UserReportingHandler(this, { viewModel.submitReport(it) })
    }

    private fun blockUser() {
        val userAddress = viewModel.user.value?.toshiId
        userAddress?.let { viewModel.blockUser(it) }
    }

    private fun unblockUser() {
        val userAddress = viewModel.user.value?.toshiId
        userAddress?.let { viewModel.unblockUser(it) }
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { onBackPressed() }
        rateBot.setOnClickListener { showRatingDialog() }
        avatar.setOnClickListener { startFullscreenActivity() }
    }

    private fun startFullscreenActivity() {
        val avatar = viewModel.user.value?.avatar
        avatar?.let {
            startActivity<FullscreenImageActivity> {
                putExtra(FullscreenImageActivity.IMAGE_URL, it)
            }
        }
    }

    private fun initObservers() {
        viewModel.reputation.observe(this, Observer {
            reputation -> reputation?.let { handleReputation(it) }
        })
        viewModel.user.observe(this, Observer {
            user -> user?.let { handleUser(it) } ?: handleUserLoadingFailed()
        })
        viewModel.isLocalUser.observe(this, Observer {
            isLocalUser -> isLocalUser?.let { rateBot.isVisible(!it) }
        })
        viewModel.isUserBlocked.observe(this, Observer {
            isUserBlocked -> isUserBlocked?.let { updateMenu(it) }
        })
        viewModel.blocking.observe(this, Observer {
            blockingAction -> blockingAction?.let { blockingHandler.showConfirmationDialog(it) }
        })
        viewModel.review.observe(this, Observer {
            isSubmitted -> isSubmitted?.let { handleReviewSubmit(it) }
        })
        viewModel.report.observe(this, Observer {
            isReported -> isReported?.let { handleReportSubmit(it) }
        })
        viewModel.noUser.observe(this, Observer { handleUserLoadingFailed() })
    }

    private fun handleReputation(reputation: ReputationScore) {
        val revCount = reputation.reviewCount
        val ratingText = resources.getQuantityString(R.plurals.ratings, revCount, revCount)
        reviewCount.text = ratingText
        ratingView.setStars(reputation.averageRating)
        reputationScore.text = reputation.averageRating.toString()
        ratingInfo.setRatingInfo(reputation)
    }

    private fun handleUser(user: User) {
        updateUi(user)
        addClickListeners(user)
    }

    private fun updateUi(user: User) {
        toolbarTitle.text = user.displayName
        name.text = user.displayName
        username.text = user.username
        about.text = user.about
        about.isVisible(user.about != null)
        ImageUtil.load(user.avatar, avatar)
        if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR)
    }

    private fun addClickListeners(user: User) {
        messageContactButton.setOnClickListener { startChatActivity(user) }
    }

    private fun startChatActivity(user: User) = startActivityAndFinish<ChatActivity> {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(ChatActivity.EXTRA__THREAD_ID, user.toshiId)
    }

    private fun updateMenu(isUserBlocked: Boolean) {
        val menuItem = menu?.findItem(R.id.block) ?: return
        if (isUserBlocked) menuItem.title = getString(R.string.unblock)
        else menuItem.title = getString(R.string.block)
    }

    private fun handleReviewSubmit(isSubmitted: Boolean) {
        if (isSubmitted) toast(R.string.review_submitted)
        else toast(R.string.review_not_submitted)
    }

    private fun handleReportSubmit(isReported: Boolean) {
        if (isReported) reportHandler.showConfirmationDialog()
        else toast(R.string.report_error)
    }

    private fun processIntentData() {
        val botAddress = getBotAddressFromIntent()
        val username = getUsernameFromIntent()
        when {
            botAddress != null -> viewModel.getUserById(botAddress)
            username != null -> viewModel.tryLookupByUsername(username)
            else -> handleUserLoadingFailed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        this.menu = menu
        menuInflater.inflate(R.menu.profile, menu)
        val userAddress = getBotAddressFromIntent()
        val username = getUsernameFromIntent()

        if (userAddress != null) {
            val isLocalUser = viewModel.checkIfLocalUserFromId(userAddress)
            if (isLocalUser) menu?.clear()
        } else if (username != null) {
            val isLocalUser = viewModel.checkIfLocalUserFromUsername(username)
            if (isLocalUser) menu?.clear()
        } else {
            handleUserLoadingFailed()
        }

        return true
    }

    private fun handleUserLoadingFailed() {
        toast(R.string.error_unknown_user)
        finish()
        if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.rate -> showRatingDialog()
            R.id.block -> blockOrUnblock()
            R.id.report -> showReportDialog()
            else -> LogUtil.d(javaClass, "Not valid menu item")
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showRatingDialog() {
        val botAddress = viewModel.user.value?.toshiId
        botAddress?.let { ratingHandler.showRatingDialog(it) }
    }

    private fun blockOrUnblock() {
        val isBotBlocked = viewModel.isUserBlocked.value
        isBotBlocked?.let { blockingHandler.showDialog(it) }
    }

    private fun showReportDialog() {
        val botAddress = viewModel.user.value?.toshiId
        botAddress?.let { reportHandler.showReportDialog(it) }
    }

    private fun getBotAddressFromIntent(): String? = intent.getStringExtra(EXTRA__BOT_ADDRESS)

    private fun getUsernameFromIntent(): String? = intent.getStringExtra(ViewBotActivity.EXTRA__BOT_NAME)

    private fun shouldPlayScanSounds() = intent.getBooleanExtra(ViewBotActivity.EXTRA__PLAY_SCAN_SOUNDS, false)

    override fun onPause() {
        super.onPause()
        blockingHandler.clear()
        ratingHandler.clear()
        reportHandler.clear()
    }
}
