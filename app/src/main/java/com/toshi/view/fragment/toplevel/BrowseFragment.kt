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

package com.toshi.view.fragment.toplevel

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo.IME_ACTION_DONE
import com.jakewharton.rxbinding.widget.RxTextView
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.isVisible
import com.toshi.extensions.startActivity
import com.toshi.model.local.DappLink
import com.toshi.model.local.ToshiEntity
import com.toshi.model.local.User
import com.toshi.model.network.App
import com.toshi.model.network.Dapp
import com.toshi.util.BrowseType
import com.toshi.util.BrowseType.VIEW_TYPE_FEATURED_DAPPS
import com.toshi.util.BrowseType.VIEW_TYPE_LATEST_PUBLIC_USERS
import com.toshi.util.BrowseType.VIEW_TYPE_TOP_RATED_APPS
import com.toshi.util.BrowseType.VIEW_TYPE_TOP_RATED_PUBLIC_USERS
import com.toshi.util.LogUtil
import com.toshi.view.activity.*
import com.toshi.view.adapter.HorizontalAdapter
import com.toshi.view.adapter.ToshiEntityAdapter
import com.toshi.view.adapter.listeners.OnItemClickListener
import com.toshi.viewModel.BrowseViewModel
import kotlinx.android.synthetic.main.fragment_browse.*
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.TimeUnit

class BrowseFragment : Fragment(), TopLevelFragment {

    companion object {
        private const val TAG = "BrowseFragment"
        private const val TOP_RATED_APPS_SCROLL_POSITION = "topRatedAppsScrollPosition"
        private const val FEATURED_APPS_SCROLL_POSITION = "featuredDappsScrollPosition"
        private const val TOP_RATED_USERS_SCROLL_POSITION = "topRatedUsersScrollPosition"
        private const val LATEST_USERS_SCROLL_POSITION = "latestUsersScrollPosition"
    }

    override fun getFragmentTag() = TAG

    private val subscriptions by lazy { CompositeSubscription() }
    private lateinit var searchAdapter: ToshiEntityAdapter
    private lateinit var viewModel: BrowseViewModel

    private var topRatedAppsScrollPosition = 0
    private var featuredDappsScrollPosition = 0
    private var topRatedUsersScrollPosition = 0
    private var latestUsersScrollPosition = 0

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, inState: Bundle?): View? {
        return inflater?.inflate(R.layout.fragment_browse, container, false)
    }

    override fun onViewCreated(view: View?, inState: Bundle?) = initView(inState)

    private fun initView(inState: Bundle?) {
        restoreScrollPosition(inState)
        initViewModel()
        initClickListeners()
        initSearchAppsRecyclerView()
        iniTopRatedAppsRecycleView()
        initFeaturedDappsRecycleView()
        initTopRatedPublicUsersRecyclerView()
        initLatestPublicUsersRecyclerView()
        initSearchView()
        initObservers()
    }

    private fun restoreScrollPosition(inState: Bundle?) {
        inState?.let {
            topRatedAppsScrollPosition = it.getInt(TOP_RATED_APPS_SCROLL_POSITION, 0)
            featuredDappsScrollPosition = it.getInt(FEATURED_APPS_SCROLL_POSITION, 0)
            topRatedUsersScrollPosition = it.getInt(TOP_RATED_USERS_SCROLL_POSITION, 0)
            latestUsersScrollPosition = it.getInt(LATEST_USERS_SCROLL_POSITION, 0)
        }
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(activity).get(BrowseViewModel::class.java)
    }

    private fun initClickListeners() {
        moreTopRatedApps.setOnClickListener { startBrowseActivity(VIEW_TYPE_TOP_RATED_APPS) }
        moreFeaturedDapps.setOnClickListener { startBrowseActivity(VIEW_TYPE_FEATURED_DAPPS) }
        moreTopRatedPublicUsers.setOnClickListener { startBrowseActivity(VIEW_TYPE_TOP_RATED_PUBLIC_USERS) }
        moreLatestPublicUsers.setOnClickListener { startBrowseActivity(VIEW_TYPE_LATEST_PUBLIC_USERS) }
        clearButton.setOnClickListener { search.text = null }
    }

    private fun startBrowseActivity(@BrowseType.Type viewType: Int) = startActivity<BrowseMoreActivity> {
        putExtra(BrowseMoreActivity.VIEW_TYPE, viewType)
    }

    private fun initSearchAppsRecyclerView() {
        searchAdapter = ToshiEntityAdapter()
                .apply {
                    itemClickListener = OnItemClickListener { startProfileActivity(it) }
                    dappLaunchClicked = OnItemClickListener {
                        startActivity<WebViewActivity> { putExtra(WebViewActivity.EXTRA__ADDRESS, it.address) }
                    }
                }

        searchList.apply {
            adapter = searchAdapter
            layoutManager = LinearLayoutManager(context)
            addHorizontalLineDivider()
        }
    }

    private fun iniTopRatedAppsRecycleView() {
        val recyclerView = initRecyclerView(
                topRatedApps,
                HorizontalAdapter<App>(5),
                OnItemClickListener { startBotProfileActivity(it) }
        )
        recyclerView.layoutManager.scrollToPosition(topRatedAppsScrollPosition)
    }

    private fun initFeaturedDappsRecycleView() {
        val recyclerView = initRecyclerView(
                featuredDapps,
                HorizontalAdapter<Dapp>(4, false),
                OnItemClickListener { startDappLaunchActivity(it) }
        )
        recyclerView.layoutManager.scrollToPosition(featuredDappsScrollPosition)
    }

    private fun initTopRatedPublicUsersRecyclerView() {
        val recyclerView = initRecyclerView(
                topRatedPublicUsers,
                HorizontalAdapter<User>(5),
                OnItemClickListener { startProfileActivity(it) }
        )
        recyclerView.layoutManager.scrollToPosition(topRatedUsersScrollPosition)
    }

    private fun initLatestPublicUsersRecyclerView() {
        val recyclerView = initRecyclerView(
                latestPublicUsers,
                HorizontalAdapter<User>(6),
                OnItemClickListener { startProfileActivity(it) }
        )
        recyclerView.layoutManager.scrollToPosition(latestUsersScrollPosition)
    }

    private fun startProfileActivity(toshiEntity: ToshiEntity?) {
        toshiEntity?.toshiId?.let {
            startActivity<ViewUserActivity> { putExtra(ViewUserActivity.EXTRA__USER_ADDRESS, it) }
        }
    }

    private fun startBotProfileActivity(toshiEntity: ToshiEntity?) {
        toshiEntity?.toshiId?.let {
            startActivity<ViewBotActivity> { putExtra(ViewBotActivity.EXTRA__BOT_ADDRESS, it) }
        }
    }

    private fun startDappLaunchActivity(toshiEntity: ToshiEntity?) {
        if (toshiEntity is Dapp) {
            startActivity<ViewDappActivity> {
                putExtra(ViewDappActivity.EXTRA__DAPP_ADDRESS, toshiEntity.address)
                putExtra(ViewDappActivity.EXTRA__DAPP_AVATAR, toshiEntity.avatar)
                putExtra(ViewDappActivity.EXTRA__DAPP_ABOUT, toshiEntity.about)
                putExtra(ViewDappActivity.EXTRA__DAPP_NAME, toshiEntity.name)
            }
        }
    }

    private fun <T : ToshiEntity> initRecyclerView(recyclerView: RecyclerView,
                                                   adapter: HorizontalAdapter<T>,
                                                   onItemClickListener: OnItemClickListener<T>): RecyclerView {
        recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter.setOnItemClickListener(onItemClickListener)
        recyclerView.adapter = adapter
        recyclerView.isNestedScrollingEnabled = false
        return recyclerView
    }

    private fun initSearchView() {
        val searchSub = RxTextView.textChanges(search)
                .skip(1)
                .debounce(400, TimeUnit.MILLISECONDS)
                .map { it.toString() }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { updateViewState() }
                .doOnNext { tryRenderDappLink(it) }
                .subscribe(
                        { viewModel.runSearchQuery(it) },
                        { LogUtil.exception(javaClass, it) }
                )

        val enterSub = RxTextView.editorActions(search)
                .filter { it == IME_ACTION_DONE }
                .toCompletable()
                .subscribe(
                        { handleSearchPressed() },
                        { LogUtil.exception(javaClass, it) }
                )

        updateViewState()
        subscriptions.addAll(searchSub, enterSub)
    }

    private fun handleSearchPressed() {
        if (searchAdapter.numberOfApps != 1) return
        val appToLaunch = searchAdapter.firstApp
        if (appToLaunch is DappLink) {
            startActivity<WebViewActivity> { putExtra(WebViewActivity.EXTRA__ADDRESS, appToLaunch.address) }
        }
    }

    private fun updateViewState() {
        val isEmpty = search.text.toString().isEmpty()
        searchList.isVisible(!isEmpty)
        clearButton.isVisible(!isEmpty)
        scrollView.isVisible(isEmpty)
    }

    private fun tryRenderDappLink(searchString: String) {
        if (!Patterns.WEB_URL.matcher(searchString.trim()).matches()) {
            searchAdapter.removeDapp()
            return
        }

        searchAdapter.addDapp(searchString)
    }

    private fun initObservers() {
        viewModel.search.observe(this, Observer {
            searchResult -> searchResult?.let { searchAdapter.addItems(it) }
        })
        viewModel.topRatedApps.observe(this, Observer {
            topRatedApps -> topRatedApps?.let { handleTopRatedApps(it) }
        })
        viewModel.featuredDapps.observe(this, Observer {
            featuredDapps -> featuredDapps?.let { handleFeaturedDapps(it) }
        })
        viewModel.topRatedPublicUsers.observe(this, Observer {
            topRatedPublicUsers -> topRatedPublicUsers?.let { handleTopRatedPublicUser(it) }
        })
        viewModel.latestPublicUsers.observe(this, Observer {
            latestPublicUsers -> latestPublicUsers?.let { handleLatestPublicUser(it) }
        })
    }

    private fun handleTopRatedApps(apps: List<App>) {
        val adapter = topRatedApps.adapter as HorizontalAdapter<ToshiEntity>
        adapter.setItems(apps)
    }

    private fun handleFeaturedDapps(apps: List<com.toshi.model.network.Dapp>) {
        val adapter = featuredDapps.adapter as HorizontalAdapter<ToshiEntity>
        adapter.setItems(apps)
    }

    private fun handleTopRatedPublicUser(users: List<User>) {
        val adapter = topRatedPublicUsers.adapter as HorizontalAdapter<ToshiEntity>
        adapter.setItems(users)
    }

    private fun handleLatestPublicUser(users: List<User>) {
        val adapter = latestPublicUsers.adapter as HorizontalAdapter<ToshiEntity>
        adapter.setItems(users)
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(setOutState(outState))
    }

    private fun setOutState(outState: Bundle?): Bundle? {
        val topRatedAppsLayoutManager = topRatedApps.layoutManager as LinearLayoutManager
        topRatedAppsScrollPosition = topRatedAppsLayoutManager.findFirstCompletelyVisibleItemPosition()
        val featuredAppsLayoutManager = featuredDapps.layoutManager as LinearLayoutManager
        featuredDappsScrollPosition = featuredAppsLayoutManager.findFirstCompletelyVisibleItemPosition()
        val topRatedUsersLayoutManager = topRatedPublicUsers.layoutManager as LinearLayoutManager
        topRatedUsersScrollPosition = topRatedUsersLayoutManager.findFirstCompletelyVisibleItemPosition()
        val featuredUsersLayoutManager = latestPublicUsers.layoutManager as LinearLayoutManager
        latestUsersScrollPosition = featuredUsersLayoutManager.findFirstCompletelyVisibleItemPosition()

        return outState?.apply {
            putInt(TOP_RATED_APPS_SCROLL_POSITION, topRatedAppsScrollPosition)
            putInt(FEATURED_APPS_SCROLL_POSITION, featuredDappsScrollPosition)
            putInt(TOP_RATED_USERS_SCROLL_POSITION, topRatedUsersScrollPosition)
            putInt(LATEST_USERS_SCROLL_POSITION, latestUsersScrollPosition) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        subscriptions.clear()
    }
}