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

import android.Manifest
import android.app.Activity
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import com.toshi.BuildConfig
import com.toshi.R
import com.toshi.exception.PermissionException
import com.toshi.extensions.getPxSize
import com.toshi.extensions.isGroupId
import com.toshi.extensions.isVisible
import com.toshi.extensions.toast
import com.toshi.model.local.Conversation
import com.toshi.model.local.Networks
import com.toshi.model.local.Recipient
import com.toshi.model.sofa.Control
import com.toshi.model.sofa.PaymentRequest
import com.toshi.model.sofa.SofaAdapters
import com.toshi.model.sofa.SofaMessage
import com.toshi.presenter.chat.ChatViewModel
import com.toshi.presenter.chat.ChatViewModelFactory
import com.toshi.presenter.chat.ConfirmPaymentInfo
import com.toshi.presenter.chat.ResendPaymentInfo
import com.toshi.util.BuildTypes
import com.toshi.util.ChatNavigation
import com.toshi.util.FileUtil
import com.toshi.util.KeyboardUtil
import com.toshi.util.LogUtil
import com.toshi.util.OnSingleClickListener
import com.toshi.util.PaymentType
import com.toshi.util.PermissionUtil
import com.toshi.util.ResendHandler
import com.toshi.util.SoundManager
import com.toshi.view.BaseApplication
import com.toshi.view.adapter.MessageAdapter
import com.toshi.view.custom.SpeedyLinearLayoutManager
import kotlinx.android.synthetic.main.activity_chat.*
import java.io.File
import java.io.IOException

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RESULT_CODE = 1
        private const val PAY_RESULT_CODE = 2
        private const val PICK_ATTACHMENT = 3
        private const val CAPTURE_IMAGE = 4
        private const val CONFIRM_ATTACHMENT = 5
        private const val LAST_VISIBLE_MESSAGE_POSITION = "lastVisibleMessagePosition"

        const val EXTRA__THREAD_ID = "remote_user_owner_address"
        const val EXTRA__PAYMENT_ACTION = "payment_action"
        const val EXTRA__ETH_AMOUNT = "eth_amount"
        const val EXTRA__PLAY_SCAN_SOUNDS = "play_scan_sounds"
        const val EXTRA__MESSAGE_ID = "message_id"
    }

    private var lastVisibleMessagePosition: Int = 0

    private val chatNavigation by lazy { ChatNavigation() }
    private val resendHandler by lazy { ResendHandler(this) }

    private lateinit var viewModel: ChatViewModel
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var layoutManager: SpeedyLinearLayoutManager

    override fun onCreate(inState: Bundle?) {
        super.onCreate(inState)
        setContentView(R.layout.activity_chat)
        init(inState)
    }

    private fun init(inState: Bundle?) {
        initViewModel()
        restoreLastVisibleMessagePosition(inState)
        initNetworkView()
        initRecyclerView()
        initControlView()
        initKeyboardListener()
        initClickListeners()
        loadConversation()
        initObservers()
        processPaymentFromIntent()
    }

    private fun initViewModel() {
        val threadId = getThreadIdFromIntent()
        viewModel = ViewModelProviders.of(
                this,
                ChatViewModelFactory(threadId)
        ).get(ChatViewModel::class.java)
    }

    private fun getThreadIdFromIntent(): String? {
        val threadId = intent.getStringExtra(ChatActivity.EXTRA__THREAD_ID)
        if (threadId == null) handleRecipientLoadFailed(R.string.error__app_loading)
        return threadId
    }

    private fun restoreLastVisibleMessagePosition(inState: Bundle?) {
        inState?.let {
            lastVisibleMessagePosition = it.getInt(LAST_VISIBLE_MESSAGE_POSITION)
        }
    }

    private fun initNetworkView() {
        val showNetwork = BuildConfig.BUILD_TYPE == BuildTypes.DEBUG
        networkView.isVisible(showNetwork)
        if (showNetwork) {
            val network = Networks.getInstance().currentNetwork
            networkView.text = network.name
        }
    }

    private fun initRecyclerView() {
        messageAdapter = initMessageAdapter()
        layoutManager = SpeedyLinearLayoutManager(this)
        messagesList.adapter = messageAdapter
        messagesList.layoutManager = layoutManager
        messagesList.isScrollContainer = true
    }

    private fun initMessageAdapter(): MessageAdapter {
        return MessageAdapter()
                .addOnPaymentRequestApproveListener { showPaymentRequestConfirmationDialog(it) }
                .addOnPaymentRequestRejectListener { viewModel.updatePaymentRequestState(it, PaymentRequest.REJECTED) }
                .addOnUsernameClickListener { chatNavigation.startProfileActivityWithUsername(this, it) }
                .addOnImageClickListener { chatNavigation.startImageActivity(this, it) }
                .addOnFileClickListener { chatNavigation.startAttachmentPicker(this, it) }
                .addOnResendListener {
                    resendHandler.showResendDialog(
                            { viewModel.resendMessage(it) },
                            { viewModel.deleteMessage(it) }
                    )
                }
                .addOnResendPaymentListener { sofaMessage ->
                    resendHandler.showResendDialog(
                            { viewModel.showResendPaymentConfirmationDialog(sofaMessage) },
                            { viewModel.deleteMessage(sofaMessage) }
                    )
                }
    }

    private fun initControlView() {
        controlView.setOnSizeChangedListener { setPadding(it) }
        conversationRequestView.onSizeChangedListener = { setPadding(it) }
    }

    private fun initClickListeners() {
        chatInput
                .setOnSendMessageClicked { viewModel.sendMessage(it); forceScrollToBottom(false) }
                .setOnAttachmentClicked { checkExternalStoragePermission() }
                .setOnCameraClickedListener { checkCameraPermission() }
        controlView.setOnControlClickedListener { handleControlClicked(it) }
        closeButton.setOnClickListener { hideKeyboard(); finish() }
    }

    private fun initKeyboardListener() {
        val rect = Rect()
        val threshold = getPxSize(R.dimen.keyboard_threshold)
        val activityRoot = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        var wasOpened = false

        activityRoot.viewTreeObserver.addOnGlobalLayoutListener {
            activityRoot.getWindowVisibleDisplayFrame(rect)
            val heightDiff = activityRoot.rootView.height - rect.height()
            val isOpen = heightDiff > threshold
            if (isOpen == wasOpened) return@addOnGlobalLayoutListener
            wasOpened = isOpen
            if (isOpen) forceScrollToBottom(false)
        }
    }

    private fun forceScrollToBottom(animate: Boolean) {
        if (messageAdapter.itemCount == 0) return
        val bottomPosition = messageAdapter.itemCount - 1
        if (animate) messagesList.smoothScrollToPosition(bottomPosition)
        else messagesList.scrollToPosition(bottomPosition)
    }

    private fun checkExternalStoragePermission() {
        PermissionUtil.hasPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                PermissionUtil.READ_EXTERNAL_STORAGE_PERMISSION,
                { startAttachmentActivity() }
        )
    }

    private fun checkCameraPermission() {
        PermissionUtil.hasPermission(
                this,
                Manifest.permission.CAMERA,
                PermissionUtil.CAMERA_PERMISSION,
                { startCameraActivity() }
        )
    }

    private val requestButtonClicked = object : OnSingleClickListener() {
        override fun onSingleClick(v: View) {
            chatNavigation.startPaymentRequestActivityForResult(this@ChatActivity, REQUEST_RESULT_CODE)
        }
    }

    private val payButtonClicked = object : OnSingleClickListener() {
        override fun onSingleClick(v: View) {
            chatNavigation.startPaymentActivityForResult(this@ChatActivity, PAY_RESULT_CODE)
        }
    }

    private fun handleControlClicked(control: Control) {
        if (control.hasAction()) {
            chatNavigation.startWebViewActivity(this, control.actionUrl)
        } else {
            controlView.hideView()
            removePadding()
            viewModel.sendCommandMessage(control)
        }
    }

    private fun loadConversation() = viewModel.loadConversation()

    private fun initObservers() {
        initRecipientObservers()
        initConfirmationObservers()
        initMessageObservers()
        initAcceptConversationObservers()
        initErrorObservers()
    }

    private fun initRecipientObservers() {
        viewModel.isLoading.observe(this, Observer {
            isLoading -> isLoading?.let { handleIsLoading(it) }
        })
        viewModel.recipient.observe(this, Observer {
            recipient -> recipient?.let { handleRecipient(it) }
        })
        viewModel.viewProfileWithId.observe(this, Observer {
            profileId -> profileId?.let { viewProfileWithId(it) }
        })
    }

    private fun handleIsLoading(isLoading: Boolean) {
        loadingViewContainer.isVisible(isLoading)
        if (isLoading) {
            val rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate)
            loadingView.startAnimation(rotateAnimation)
        } else {
            loadingView.clearAnimation()
        }
    }

    private fun handleRecipient(recipient: Recipient) {
        messageAdapter.setRecipient(recipient)
        initToolbar(recipient)
    }

    private fun initToolbar(recipient: Recipient) {
        toolbarTitle.text = recipient.displayName
        avatar.setOnClickListener { viewModel.viewRecipientProfile() }
        recipient.loadAvatarInto(avatar)
        if (recipient.isUser) {
            balanceBar.setOnRequestClicked(requestButtonClicked)
            balanceBar.setOnPayClicked(payButtonClicked)
        }
    }

    private fun viewProfileWithId(threadId: String) {
        if (threadId.isGroupId()) chatNavigation.startGroupInfoActivityWithId(this, threadId)
        else chatNavigation.startProfileActivityWithId(this, threadId)
    }

    private fun initConfirmationObservers() {
        viewModel.confirmPayment.observe(this, Observer {
            confirmPaymentInfo -> confirmPaymentInfo?.let { showPaymentConfirmationDialog(it) }
        })
        viewModel.resendPayment.observe(this, Observer {
            resendPaymentInfo -> resendPaymentInfo?.let { showResendPaymentConfirmationDialog(it) }
        })
        viewModel.respondToPaymentRequest.observe(this, Observer {
            sofaMessage -> sofaMessage?.let { showPaymentRequestConfirmationDialog(it) }
        })
    }

    private fun showPaymentConfirmationDialog(confirmPaymentInfo: ConfirmPaymentInfo) {
        val receiver = confirmPaymentInfo.receiver
        val amount = confirmPaymentInfo.amount
        resendHandler.showPaymentConfirmationDialog(receiver, amount) { paymentTask ->
            viewModel.sendPayment(paymentTask)
        }
    }

    private fun showResendPaymentConfirmationDialog(resendPaymentInfo: ResendPaymentInfo) {
        try {
            val sofaMessage = resendPaymentInfo.sofaMessage
            val receiver = resendPaymentInfo.receiver
            val payment = SofaAdapters.get().paymentFrom(sofaMessage.payload)
            resendHandler.showResendPaymentConfirmationDialog(receiver, payment) { paymentTask ->
                viewModel.resendPayment(sofaMessage, payment, paymentTask)
            }
        } catch (e: IOException) {
            LogUtil.e(javaClass, "Error while resending payment $e")
        }
    }

    private fun showPaymentRequestConfirmationDialog(existingMessage: SofaMessage) {
        try {
            val paymentRequest = SofaAdapters.get().txRequestFrom(existingMessage.payload)
            resendHandler.showPaymentRequestConfirmationDialog(existingMessage.sender, paymentRequest) { paymentTask ->
                viewModel.updatePaymentRequestState(existingMessage, PaymentRequest.ACCEPTED)
                viewModel.sendPayment(paymentTask)
            }
        } catch (e: IOException) {
            LogUtil.exception(javaClass, "Error while showing payment request confirmation dialog $e")
        }
    }

    private fun initMessageObservers() {
        viewModel.newMessage.observe(this, Observer {
            newMessage -> newMessage?.let { handleNewMessage(it) }
        })
        viewModel.updateMessage.observe(this, Observer {
            updatedMessage -> updatedMessage?.let { messageAdapter.updateMessage(it) }
        })
        viewModel.updateConversation.observe(this, Observer {
            updatedMessage -> updatedMessage?.let { initToolbar(it.recipient) }
        })
        viewModel.deleteMessage.observe(this, Observer {
            deletedMessage -> deletedMessage?.let { messageAdapter.deleteMessage(it) }
        })
        viewModel.conversation.observe(this, Observer {
            conversation -> conversation?.let { handleConversation(it) }
        })
    }

    private fun handleNewMessage(sofaMessage: SofaMessage) {
        val isAccepted = viewModel.conversation.value?.conversationStatus?.isAccepted
        val isLocalUser = sofaMessage.isSentBy(viewModel.getCurrentLocalUser())
        messageAdapter.updateMessage(sofaMessage)
        updateControlView(isAccepted == true)
        updateEmptyState()
        tryScrollToBottom(true)
        playNewMessageSound(isLocalUser)
        handleKeyboardVisibility(sofaMessage)
    }

    private fun handleConversation(conversation: Conversation) {
        messageAdapter.setMessages(conversation.allMessages)
        val unreadScrollPosition = messageAdapter.itemCount - conversation.numberOfUnread
        val scrollPosition = if (conversation.numberOfUnread == 0) getSafePosition() else unreadScrollPosition
        messagesList.scrollToPosition(scrollPosition)
        updateControlView(conversation.conversationStatus.isAccepted)
        initConversationRequestView(conversation)
        updateEmptyState()
    }

    // Returns last known scroll position, or last position if unknown
    private fun getSafePosition(): Int {
        if (lastVisibleMessagePosition > 0) return lastVisibleMessagePosition
        return if (messageAdapter.itemCount - 1 > 0) messageAdapter.itemCount - 1 else 0
    }

    private fun initAcceptConversationObservers() {
        viewModel.acceptConversation.observe(this, Observer {
            hideConversationRequestView()
        })
        viewModel.declineConversation.observe(this, Observer {
            hideConversationRequestView(); finish()
        })
    }

    private fun initErrorObservers() {
        viewModel.recipientError.observe(this, Observer {
            errorMessage -> errorMessage?.let { handleRecipientLoadFailed(it) }
        })
        viewModel.error.observe(this, Observer {
            errorMessage -> errorMessage?.let { toast(it) }
        })
    }

    private fun processPaymentFromIntent() {
        val value = intent.getStringExtra(ChatActivity.EXTRA__ETH_AMOUNT)
        val paymentAction = intent.getIntExtra(ChatActivity.EXTRA__PAYMENT_ACTION, -1)
        val messageId = intent.getStringExtra(ChatActivity.EXTRA__MESSAGE_ID)

        intent.removeExtra(ChatActivity.EXTRA__ETH_AMOUNT)
        intent.removeExtra(ChatActivity.EXTRA__PAYMENT_ACTION)
        intent.removeExtra(ChatActivity.EXTRA__MESSAGE_ID)

        if (paymentAction == -1) return

        if (messageId != null && paymentAction == PaymentType.TYPE_SEND) {
            viewModel.respondToPaymentRequest(messageId)
        } else if (value != null && paymentAction == PaymentType.TYPE_SEND) {
            viewModel.sendPaymentWithValue(value)
        } else if (value != null && paymentAction == PaymentType.TYPE_REQUEST) {
            viewModel.sendPaymentRequestWithValue(value)
        }
    }

    private fun updateControlView(isConversationAccepted: Boolean) {
        val sofaMessage = messageAdapter.lastPlainTextSofaMessage
        if (sofaMessage == null || TextUtils.isEmpty(sofaMessage.payload)) return

        try {
            val message = SofaAdapters.get().messageFrom(sofaMessage.payload)
            val notNullAndNotZero = message.controls != null && message.controls.size > 0
            controlView.hideView()
            if (notNullAndNotZero && isConversationAccepted) controlView.showControls(message.controls)
            else removePadding()
        } catch (e: IOException) {
            LogUtil.e(javaClass, "Error while updating control view $e")
        }
    }

    private fun initConversationRequestView(conversation: Conversation) {
        val isConversationAccepted = conversation.conversationStatus.isAccepted
        conversationRequestView.isVisible(!isConversationAccepted)
        if (!isConversationAccepted) {
            conversationRequestView.onAcceptClickListener = { viewModel.acceptConversation(conversation) }
            conversationRequestView.onDeclineClickListener = { viewModel.declineConversation(conversation) }
        }
    }

    private fun hideConversationRequestView() {
        removePadding()
        conversationRequestView.visibility = View.GONE
    }

    private fun handleRecipientLoadFailed(errorMessage: Int) {
        if (shouldPlayScanSounds()) SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR)
        toast(errorMessage)
        finish()
    }

    private fun shouldPlayScanSounds() = intent?.getBooleanExtra(EXTRA__PLAY_SCAN_SOUNDS, false) ?: false

    private fun playNewMessageSound(sentByLocal: Boolean) {
        if (sentByLocal) SoundManager.getInstance().playSound(SoundManager.SEND_MESSAGE)
        else SoundManager.getInstance().playSound(SoundManager.RECEIVE_MESSAGE)
    }

    private fun handleKeyboardVisibility(sofaMessage: SofaMessage) {
        if (chatInput == null || sofaMessage.isSentBy(viewModel.getCurrentLocalUser())) return
        try {
            val message = SofaAdapters.get().messageFrom(sofaMessage.payload)
            if (message.shouldHideKeyboard()) hideKeyboard()
        } catch (e: IOException) {
            LogUtil.exception(javaClass, "Error during handling visibility of keyboard", e)
        }
    }

    private fun hideKeyboard() {
        if (chatInput == null || chatInput?.inputView == null) return
        KeyboardUtil.hideKeyboard(chatInput.inputView)
    }

    private fun removePadding() {
        val paddingRight = messagesList.paddingRight
        val paddingLeft = messagesList.paddingLeft
        val paddingBottom = getPxSize(R.dimen.message_list_bottom_padding)
        messagesList.setPadding(paddingLeft, 0, paddingRight, paddingBottom)
    }

    private fun setPadding(height: Int): Unit? {
        val paddingBottom = getPxSize(R.dimen.chat_button_padding) + height
        val paddingRight = messagesList.paddingRight
        val paddingLeft = messagesList.paddingLeft
        messagesList.setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        tryScrollToBottom(true)
        return null
    }

    private fun tryScrollToBottom(animate: Boolean) {
        if (messageAdapter.itemCount == 0) return
        // Only animate if we're already near the bottom
        if (layoutManager.findLastVisibleItemPosition() < messageAdapter.itemCount - 2) return
        val bottomPosition = messageAdapter.itemCount - 1
        if (animate) messagesList.smoothScrollToPosition(bottomPosition)
        else messagesList.scrollToPosition(bottomPosition)
    }

    private fun updateEmptyState() {
        val showingEmptyState = emptyStateSwitcher.currentView.id == emptyState.id
        val shouldShowEmptyState = messageAdapter.itemCount == 0
        if (shouldShowEmptyState && !showingEmptyState) emptyStateSwitcher.showPrevious()
        else if (!shouldShowEmptyState && showingEmptyState) emptyStateSwitcher.showNext()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultIntent: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultIntent)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_RESULT_CODE -> {
                val value = resultIntent?.getStringExtra(AmountActivity.INTENT_EXTRA__ETH_AMOUNT)
                value?.let { viewModel.sendPaymentRequestWithValue(it) }
            }
            PAY_RESULT_CODE -> {
                val value = resultIntent?.getStringExtra(AmountActivity.INTENT_EXTRA__ETH_AMOUNT)
                value?.let { viewModel.sendPaymentWithValue(it) }
            }
            PICK_ATTACHMENT -> {
                val attachment = resultIntent?.data
                attachment?.let { chatNavigation.startAttachmentConfirmationActivity(this, it , CONFIRM_ATTACHMENT) }
            }
            CAPTURE_IMAGE -> {
                val fileName = viewModel.capturedImageName
                fileName?.let { viewModel.sendMediaMessage(File(BaseApplication.get().filesDir, fileName)) }
            }
            CONFIRM_ATTACHMENT -> {
                val filePath = resultIntent?.getStringExtra(AttachmentConfirmationActivity.ATTACHMENT_PATH)
                filePath?.let { viewModel.sendMediaMessage(File(filePath)) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!PermissionUtil.isPermissionGranted(grantResults)) return

        when (requestCode) {
            PermissionUtil.CAMERA_PERMISSION -> startCameraActivity()
            PermissionUtil.READ_EXTERNAL_STORAGE_PERMISSION -> startAttachmentActivity()
            else -> throw PermissionException("This permission doesn't belong in this context")
        }
    }

    private fun startAttachmentActivity() = chatNavigation.startAttachmentActivity(this, PICK_ATTACHMENT)

    private fun startCameraActivity() {
        val photoFile = FileUtil.createImageFileWithRandomName()
        viewModel.capturedImageName = photoFile.name
        val photoUri = FileUtil.getUriFromFile(photoFile)
        chatNavigation.startCameraActivity(this, photoUri, CAPTURE_IMAGE)
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        outState?.putInt(LAST_VISIBLE_MESSAGE_POSITION, layoutManager.findLastVisibleItemPosition())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        this.messageAdapter.clear()
    }
}