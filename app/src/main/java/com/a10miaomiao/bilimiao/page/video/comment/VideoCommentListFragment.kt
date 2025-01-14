package com.a10miaomiao.bilimiao.page.video.comment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.coroutineScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import bilibili.main.community.reply.v1.ReplyOuterClass
import cn.a10miaomiao.miao.binding.android.view._bottomPadding
import cn.a10miaomiao.miao.binding.android.view._leftPadding
import cn.a10miaomiao.miao.binding.android.view._rightPadding
import cn.a10miaomiao.miao.binding.android.view._topPadding
import com.a10miaomiao.bilimiao.MainNavGraph
import com.a10miaomiao.bilimiao.R
import com.a10miaomiao.bilimiao.comm.*
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MyPage
import com.a10miaomiao.bilimiao.comm.mypage.myMenuItem
import com.a10miaomiao.bilimiao.comm.mypage.myPageConfig
import com.a10miaomiao.bilimiao.comm.recycler.MiaoBindingAdapter
import com.a10miaomiao.bilimiao.comm.recycler._miaoAdapter
import com.a10miaomiao.bilimiao.comm.recycler._miaoLayoutManage
import com.a10miaomiao.bilimiao.comm.recycler.miaoBindingItemUi
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import com.a10miaomiao.bilimiao.comm.utils.NumberUtil
import com.a10miaomiao.bilimiao.commponents.comment.VideoCommentViewContent
import com.a10miaomiao.bilimiao.commponents.comment.videoCommentView
import com.a10miaomiao.bilimiao.commponents.loading.ListState
import com.a10miaomiao.bilimiao.commponents.loading.listStateView
import com.a10miaomiao.bilimiao.config.config
import com.a10miaomiao.bilimiao.store.WindowStore
import com.a10miaomiao.bilimiao.widget.expandabletext.ExpandableTextView
import com.a10miaomiao.bilimiao.widget.expandabletext.app.LinkType
import com.chad.library.adapter.base.listener.OnItemClickListener
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.toast.toast
import splitties.views.backgroundColor
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView

class VideoCommentListFragment : Fragment(), DIAware, MyPage {

    override val pageConfig = myPageConfig {
        title = "评论列表"
        menus = listOf(
            myMenuItem {
                key = 0
                iconResource = R.drawable.ic_baseline_filter_list_grey_24
                title = SortOrderPopupMenu.getText(viewModel.sortOrder)
            }
        )
    }

    override fun onMenuItemClick(view: View, menuItem: MenuItemPropInfo) {
        super.onMenuItemClick(view, menuItem)
        when (menuItem.key) {
            0 -> {
                val pm = SortOrderPopupMenu(
                    activity = requireActivity(),
                    anchor = view,
                    checkedValue = viewModel.sortOrder
                )
                pm.setOnMenuItemClickListener(handleMenuItemClickListener)
                pm.show()
            }
        }
    }

    override val di: DI by lazyUiDi(ui = { ui })

    private val viewModel by diViewModel<VideoCommentListViewModel>(di)

    private val windowStore by instance<WindowStore>()

    private var mAdapter: MiaoBindingAdapter<ReplyOuterClass.ReplyInfo>? = null

    private val handleMenuItemClickListener = PopupMenu.OnMenuItemClickListener {
        it.isChecked = true
        viewModel.sortOrder = it.itemId
        pageConfig.notifyConfigChanged()
        viewModel.refreshList()
        false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycle.coroutineScope.launch {
            windowStore.connectUi(ui)
        }
    }

    private fun toSelfLink (view: View, url: String) {
        val urlInfo = BiliUrlMatcher.findIDByUrl(url)
        val urlType = urlInfo[0]
        var urlId = urlInfo[1]
        if (urlType == "BV") {
            urlId = "BV$urlId"
        }
        val args = bundleOf(
            MainNavGraph.args.id to urlId
        )
        when(urlType){
            "AV", "BV" -> {
                args.putString(MainNavGraph.args.type, urlType)
                Navigation.findNavController(view)
                    .navigate(MainNavGraph.action.videoCommentList_to_videoInfo, args)
            }
        }
    }

    private val handleUserClick = View.OnClickListener {
        val id = it.tag
        if (id != null) {
            val args = bundleOf(
                MainNavGraph.args.id to id
            )
            Navigation.findNavController(it)
                .navigate(MainNavGraph.action.videoCommentList_to_user, args)
        }
    }

    private val handleRefresh = SwipeRefreshLayout.OnRefreshListener {
        viewModel.refreshList()
    }

    private val handleItemClick = OnItemClickListener { adapter, view, position ->
        val item = adapter.getItem(position) as ReplyOuterClass.ReplyInfo

        val reply = VideoCommentDetailParam(
            oid = item.oid,
            rpid = item.id,
            mid = item.member.mid,
            uname = item.member.name,
            avatar = item.member.face,
            ctime = item.ctime,
            floor = 0,
            location = item.replyControl.location,
            content = VideoCommentViewContent(
                message = item.content.message,
                emote = item.content.emoteMap.values.map {
                    VideoCommentViewContent.Emote(
                        it.id, it.text, it.url
                    )
                },
            ),
            like = item.like,
            count = item.count,
        )
        val args = bundleOf(
            MainNavGraph.args.reply to reply
        )
        Navigation.findNavController(view)
            .navigate(MainNavGraph.action.videoCommentList_to_videoCommentDetail, args)
    }

    private val handleLinkClickListener = ExpandableTextView.OnLinkClickListener { view, linkType, content, selfContent -> //根据类型去判断
        when (linkType) {
            LinkType.LINK_TYPE -> {
                val url = content
                val re = BiliNavigation.navigationTo(view, url)
                if (!re) {
                    if (url.indexOf("bilibili://") == 0) {
                        toast("不支持打开的链接：$url")
                    } else {
                        BiliUrlMatcher.toUrlLink(view, url)
                    }
                }
            }
            LinkType.MENTION_TYPE -> {
//                toast("你点击了@用户 内容是：$content")
            }
            LinkType.SELF -> {
                toSelfLink(view, selfContent)
            }
        }
    }


    private val handleLikeClick = View.OnClickListener {
        requireActivity().toast("暂不支持此操作")
//        val index = it.tag
//        if (it is ImageView && index is Int && index >= 0) {
//            viewModel.setLike(index) { item ->
//                mAdapter?.setData(index, item)
//            }
//        }
    }

    val itemUi = miaoBindingItemUi<ReplyOuterClass.ReplyInfo> { item, index ->
        videoCommentView(
            index = index,
            mid = item.mid,
            uname = item.member.name,
            avatar = item.member.face,
            time = NumberUtil.converCTime(item.ctime),
            location = item.replyControl.location,
            floor = 0,
            content = VideoCommentViewContent(
                message = item.content.message,
                emote = item.content.emoteMap.values.map {
                    VideoCommentViewContent.Emote(
                        it.id, it.text, it.url
                    )
                },
            ),
            like = item.like,
            count = item.count,
            isLike = item.replyControl.action == 1L,
            onUpperClick = handleUserClick,
            onLinkClick = handleLinkClickListener,
            onLikeClick = handleLikeClick,
        ).apply {
            layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
        }
    }

    val ui = miaoBindingUi {
        val contentInsets = windowStore.getContentInsets(parentView)

        recyclerView {
            _leftPadding = contentInsets.left
            _rightPadding = contentInsets.right

            backgroundColor = config.windowBackgroundColor
            _miaoLayoutManage(
                LinearLayoutManager(requireContext())
            )

            val headerView = frameLayout {
                _topPadding = contentInsets.top
            }
            val footerView = listStateView(
                when {
                    viewModel.triggered -> ListState.NORMAL
                    viewModel.list.loading -> ListState.LOADING
                    viewModel.list.fail -> ListState.FAIL
                    viewModel.list.finished -> ListState.NOMORE
                    else -> ListState.NORMAL
                }
            ) {
                _bottomPadding = contentInsets.bottom
            }
            footerView.layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)

            _miaoAdapter(
                items = viewModel.list.data,
                itemUi = itemUi,
            ) {
                mAdapter = this
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                setOnItemClickListener(handleItemClick)
                loadMoreModule.setOnLoadMoreListener {
                    viewModel.loadMode()
                }
                addHeaderView(headerView)
                addFooterView(footerView)
            }
        }.wrapInSwipeRefreshLayout {
            setColorSchemeResources(config.themeColorResource)
            setOnRefreshListener(handleRefresh)
            _isRefreshing = viewModel.triggered
        }
    }

}